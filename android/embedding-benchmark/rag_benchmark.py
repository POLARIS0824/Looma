import argparse
from itertools import chain
import json
from pathlib import Path
import re
import sqlite3
from statistics import mean

from assets import MODELS
from backends import Device, android_inference, stage_app
from benchmark import ROOT, make_request, metadata, write
from corpus import Chunk, Query, body_start, chunk_map, coverage, digest, load_corpus, metrics, section_spans, validate_vectors


LIMIT = 240
OVERLAP = 48
NEIGHBOR_THRESHOLDS = (0.60, 0.85)
PARAGRAPH_GAP = re.compile(r"\s*\n[ \t]*\n\s*")
QUERY_TERM = re.compile(r"[\u3400-\u9fff]+|[a-z0-9]+", re.IGNORECASE)
ASCII_TERM = re.compile(r"[a-z0-9]+")
SEARCH_STOP_WORDS = set("a an and are as at be by can did do does for from had has have how i if in is it me my of on or our so than that the their then there they this to was were what when where which who why will with would you your".split())


def passage(note, start, end, heading):
    return Chunk(note.id, start, end, (heading[-48:] + "\n" if heading else "") + note.source[start:end].strip())


def paragraphs(note):
    for start, end, heading in section_spans(note.source, body_start(note.source)):
        section = start
        width = LIMIT - (min(48, len(heading)) + 1 if heading else 0)
        gaps = [*PARAGRAPH_GAP.finditer(note.source, start, end), None]
        for gap in gaps:
            stop = gap.start() if gap else end
            while start < stop:
                boundary = min(start + width, stop)
                if note.source[start:boundary].strip():
                    yield passage(note, start, boundary, heading), heading, section
                start = boundary
            if gap:
                start = gap.end()


def dot(left, right):
    return sum(x * y for x, y in zip(left, right, strict=True))


def packed(note, blocks, vectors=None, threshold=None):
    current = previous = None
    for block, heading, section in blocks:
        candidate = passage(note, current.start, block.end, heading) if current else block
        same_section = current and section == previous_section
        fits = len(candidate.text) <= LIMIT
        if same_section and fits and vectors and current.end - current.start >= 70:
            fits = dot(vectors[previous.id], vectors[block.id]) >= threshold
        if same_section and fits:
            current = candidate
        else:
            if current:
                yield current
            current = block
        previous, previous_section = block, section
    if current:
        yield current


def query_terms(text):
    terms = []
    for token in QUERY_TERM.findall(text.lower()):
        if ASCII_TERM.fullmatch(token):
            if token not in SEARCH_STOP_WORDS:
                terms.append(token)
        else:
            terms.extend(token[i:i + 3] for i in range(max(1, len(token) - 2)))
    return tuple(dict.fromkeys(terms))


def lexical_rank(db, terms, chunks, raw):
    scores = {i: sum(term in text for term in terms) for i, text in enumerate(raw)}
    contains = sorted((i for i, score in scores.items() if score), key=lambda i: (-scores[i], i))
    long_terms = [term for term in terms if len(term) >= 3]
    short_terms = [term for term in terms if len(term) < 3]
    expression = " OR ".join(f'"{term}"' for term in long_terms)
    matches = []
    if expression:
        matches = [row[0] for row in db.execute(
            "SELECT rowid FROM passages WHERE passages MATCH ? ORDER BY rank, rowid", (expression,),
        )]
    short_matches = sorted((i for i, text in enumerate(raw) if any(term in text for term in short_terms)),
                           key=lambda i: (-scores[i], i))
    fts = list(dict.fromkeys(matches + short_matches))
    return [chunks[i] for i in contains], [chunks[i] for i in fts]


def by_note(chunks):
    top = {}
    for chunk in chunks:
        top.setdefault(chunk.note, chunk)
    return top


def rrf(first, second, limit=20):
    scores = {}
    for ranking in (first, second):
        for rank, note in enumerate(list(ranking)[:limit], 1):
            scores[note] = scores.get(note, 0) + 1 / (60 + rank)
    return scores


def evaluate(notes, queries, chunks, vectors):
    flat = list(chain.from_iterable(chunks.values()))
    raw = [notes[chunk.note].source[chunk.start:chunk.end].lower() for chunk in flat]
    db = sqlite3.connect(":memory:")
    try:
        db.execute("CREATE VIRTUAL TABLE passages USING fts5(text, tokenize='trigram')")
        db.executemany("INSERT INTO passages(rowid, text) VALUES (?, ?)", enumerate(raw))
        results = []
        for query in queries:
            terms = query_terms(query.text)
            by_substring, by_fts = lexical_rank(db, terms, flat, raw)
            substring = by_note(by_substring)
            fts = by_note(by_fts)
            q = vectors[f"query:{query.id}"]
            similarity = {chunk.id: dot(q, vectors[chunk.id]) for chunk in flat}
            ranked = sorted(flat, key=lambda chunk: -similarity[chunk.id])
            dense = by_note(ranked)
            scores = rrf(dense, fts)
            hybrid = sorted(scores, key=lambda note: (-scores[note], note))
            prefix = sorted(hybrid[:12], key=lambda note: -similarity[dense[note].id])
            literals = [term.lower().strip() for term in re.findall(r"`([^`]+)`", query.text) if term.strip()]
            literal_hits = by_note(chunk for chunk in ranked if literals and all(
                term in notes[chunk.note].source[chunk.start:chunk.end].lower() for term in literals
            ))
            gated = by_note(chain(list(literal_hits.values())[:1], dense.values()))
            results.append(dict(id=query.id, group=query.group, rankings={
                "substring": list(substring.values())[:5], "trigramBm25": list(fts.values())[:5],
                "dense": list(dense.values())[:5], "rrf": [dense[note] for note in hybrid[:5]],
                "rrfThenCosine": [dense[note] for note in (prefix + hybrid[12:])[:5]],
                "densePlusLiteral": list(gated.values())[:5],
            }))
        return results
    finally:
        db.close()


def make_score(query, hits, notes):
    excerpts = {chunk.note: notes[chunk.note].source[chunk.start:chunk.end] for chunk in hits}
    anchors = sum(map(len, query.relevant.values()))
    covered = {key: sum(anchor in excerpts.get(key, "") for anchor in labels)
               for key, labels in query.relevant.items()}
    return dict(**metrics(list(excerpts), query.relevant), evidenceRecallAt5=sum(covered.values()) / anchors,
                allRequiredNotesHaveEvidence=all(covered.values()),
                hits=[dict(note=chunk.note, start=chunk.start, end=chunk.end,
                           excerpt=excerpts[chunk.note], sourceRevision=notes[chunk.note].revision)
                      for chunk in hits])


METRIC_KEYS = ("precisionAt3", "recallAt5", "mrr", "evidenceRecallAt5", "allRequiredNotesHaveEvidence")


def summarize(queries, results, notes, parts):
    by_id = {query.id: query for query in queries}
    by_result = {row["id"]: row for row in results}
    rows = []
    for row in results:
        if row["group"] == "subquery":
            continue
        query = by_id[row["id"]]
        scores = {name: make_score(query, hits, notes) for name, hits in row["rankings"].items()}
        if query.id in parts:
            lists = [by_result[part]["rankings"]["dense"] for part in parts[query.id]]
            interleaved = by_note(chunk for pair in zip(*lists, strict=True) for chunk in pair)
            scores["manualQuerySplit"] = make_score(query, list(interleaved.values())[:5], notes)
        rows.append(dict(id=query.id, group=query.group, scores=scores))
    groups = {group: [row for row in rows if row["group"] == group] for group in {row["group"] for row in rows}}
    groups["original32"] = [row for row in rows if row["id"] not in parts and row["group"] != "literal"]
    aggregate = {}
    for group, members in sorted(groups.items()):
        aggregate[group] = {name: {key: mean(row["scores"][name][key] for row in members)
                                   for key in METRIC_KEYS}
                            for name in members[0]["scores"]}
    return aggregate, rows


def comparisons(notes, base_queries):
    fixtures = json.loads((ROOT / "corpus/rag-questions.json").read_text(encoding="utf-8"))
    by_id = {row.id: row for row in base_queries}
    queries = list(base_queries)
    parts = {}
    for fixture in fixtures:
        id = fixture["id"]
        relevant = {}
        parts[id] = []
        for index, part in enumerate(fixture["parts"]):
            original = by_id[part["reference"]]
            note = part["note"]
            labels = {note: original.relevant[note]}
            if note in relevant:
                raise ValueError(f"Expected evidence from distinct notes for {id}")
            relevant.update(labels)
            subquery = f"{id}-{index + 1}"
            queries.append(Query(subquery, "subquery", part["query"], (), labels))
            parts[id].append(subquery)
        queries.append(Query(id, "multi-source", fixture["question"], (), relevant))
    literals = json.loads((ROOT / "corpus/rag-literals.json").read_text(encoding="utf-8"))
    for fixture in literals:
        note = fixture["note"]
        anchor = fixture["anchor"]
        if notes[note].source.count(anchor) != 1:
            raise ValueError(f"Expected one evidence anchor in {note}")
        queries.append(Query(fixture["id"], "literal", fixture["question"], (), {note: (anchor,)}))
    return queries, parts


def embeddings(args, device, chunks, queries, weights, tokenizer, output):
    request = make_request(args, {}, queries, weights, tokenizer)
    rows = [dict(id=f"{strategy}/{chunk.id}", text=chunk.text, role="doc")
            for strategy, notes in chunks.items() for passages in notes.values() for chunk in passages]
    rows += request["inputs"]
    inputs = {}
    for row in rows:
        inputs.setdefault((row["role"], row["text"]), row)
    request = {**request, "inputs": list(inputs.values())}
    runtime = android_inference(device, request, output)
    vectors = runtime.pop("vectors")
    validate_vectors(request["inputs"], vectors, MODELS[args.model].dimensions)
    aliases = {row["id"]: inputs[row["role"], row["text"]]["id"]
               for row in rows}
    return {id: vectors[first] for id, first in aliases.items()}, dict(
        device=runtime["device"], abi=runtime["abi"], api=runtime["api"], uniqueInputs=len(vectors),
    )


def main():
    parser = argparse.ArgumentParser(description="Compare RAG evidence retrieval on Looma benchmark notes.")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--model", choices=("gemma-cpu", "gemma-gpu", "bekko"), default="gemma-cpu")
    parser.add_argument("--assets", type=Path, default=ROOT / "build/assets")
    parser.add_argument("--apk", type=Path, default=ROOT / "build/outputs/apk/debug/embedding-benchmark-debug.apk")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    args.threads, args.rounds, args.workloads = 2, 1, []
    model = MODELS[args.model]
    assets = args.assets.resolve()
    weights = model.weights.find(assets, args.offline)
    tokenizer = model.tokenizer.find(assets, args.offline) if model.tokenizer else None
    device = Device(args.serial)
    stage_app(device, args.apk, [weights] + ([tokenizer] if tokenizer else []))
    output = ROOT / "build/reports"
    output.mkdir(parents=True, exist_ok=True)
    notes, original_queries = load_corpus(ROOT / "corpus")
    queries, parts = comparisons(notes, original_queries)
    blocks = {key: list(paragraphs(note)) for key, note in notes.items()}
    boundary_chunks = {key: [chunk for chunk, _, _ in rows] for key, rows in blocks.items()}
    boundary_output, boundary_runtime = embeddings(args, device, {"boundary": boundary_chunks},
                                                   original_queries, weights, tokenizer,
                                                   output / f"rag-{args.model}-boundaries.log")
    boundaries = {chunk.id: boundary_output[f"boundary/{chunk.id}"]
                  for rows in boundary_chunks.values() for chunk in rows}
    policies = {policy: chunk_map(notes, policy, LIMIT, OVERLAP) for policy in ("front", "window", "sections")}
    policies["paragraphs"] = {key: list(packed(note, blocks[key])) for key, note in notes.items()}
    for threshold in NEIGHBOR_THRESHOLDS:
        policies[f"semantic{threshold:.2f}"] = {key: list(packed(note, blocks[key], boundaries, threshold))
                                               for key, note in notes.items()}
    vectors, runtime = embeddings(args, device, policies, queries, weights, tokenizer, output / f"rag-{args.model}.log")
    tables = {}
    for policy, chunks in policies.items():
        policy_vectors = {f"query:{q.id}": vectors[f"query:{q.id}"] for q in queries}
        policy_vectors.update({chunk.id: vectors[f"{policy}/{chunk.id}"]
                               for rows in chunks.values() for chunk in rows})
        rows = evaluate(notes, queries, chunks, policy_vectors)
        aggregate, per_query = summarize(queries, rows, notes, parts)
        tables[policy] = dict(chunks=sum(map(len, chunks.values())),
                              coverage=coverage(notes, original_queries, chunks), byGroup=aggregate, rows=per_query)
    write(output / f"rag-{args.model}.json", dict(
        **metadata(notes, original_queries), **runtime, model=args.model, modelSha256=model.weights.sha256,
        sqliteVersion=sqlite3.sqlite_version, chunkChars=LIMIT, overlapChars=OVERLAP,
        semanticNeighborCosine=NEIGHBOR_THRESHOLDS,
        boundaryDocInputs=len(boundaries), boundaryBatchUniqueInputs=boundary_runtime["uniqueInputs"],
        compositeQuestions=len(parts), semanticExperiment="Adjacent paragraph embeddings with a fixed threshold",
        benchmarkSourceHash=digest(Path(__file__).read_text(encoding="utf-8")),
        compositeQuestionsHash=digest((ROOT / "corpus/rag-questions.json").read_text(encoding="utf-8")),
        literalQuestionsHash=digest((ROOT / "corpus/rag-literals.json").read_text(encoding="utf-8")),
        strategies=tables,
    ))


if __name__ == "__main__":
    main()
