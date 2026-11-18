from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import re
from statistics import median
from time import perf_counter_ns


STRATEGIES = ("front", "window", "sections")
HEADING = re.compile(r" {0,3}(#{1,6})[ \t]+(.+?)\s*$")
FENCE = re.compile(r" {0,3}(`{3,}|~{3,})(.*)$")


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class Note:
    id: str
    source: str
    revision: str

    @classmethod
    def from_text(cls, id, text):
        return cls(id, text, digest(text))


@dataclass(frozen=True)
class Query:
    id: str
    group: str
    text: str
    terms: tuple[str, ...]
    relevant: dict[str, tuple[str, ...]]


@dataclass(frozen=True)
class Chunk:
    note: str
    start: int
    end: int
    text: str

    @property
    def id(self):
        return f"doc:{self.note}:{self.start}:{self.end}"


def load_corpus(directory):
    files = sorted((directory / "notes").glob("*.md"))
    notes = {file.stem: Note.from_text(file.stem, file.read_bytes().decode("utf-8")) for file in files}
    raw = json.loads((directory / "queries.json").read_text(encoding="utf-8"))
    queries = [
        Query(row["id"], row["group"], row["text"], tuple(row["terms"]),
              {key: tuple(anchors) for key, anchors in row["relevant"].items()})
        for row in raw
    ]
    if len(notes) < 2 or not queries or len({q.id for q in queries}) != len(queries):
        raise ValueError("Expected at least two notes and nonempty, uniquely named queries")
    for query in queries:
        if not query.id or not query.group or not query.text.strip() or not query.terms or not all(query.terms):
            raise ValueError(f"Invalid query {query.id}")
        if not query.relevant or not query.relevant.keys() <= notes.keys():
            raise ValueError(f"Unknown or missing relevant notes for {query.id}")
        for note_id, anchors in query.relevant.items():
            for anchor in anchors:
                if not anchor or notes[note_id].source.count(anchor) != 1:
                    raise ValueError(f"Expected a unique evidence anchor for {query.id} in {note_id}")
    return notes, queries


def body_start(source):
    first = source.splitlines(keepends=True)
    if not first or first[0].strip() != "---":
        return 0
    offset = len(first[0])
    for line in first[1:]:
        offset += len(line)
        if line.strip() in ("---", "..."):
            return offset
    return 0


def section_spans(source, begin):
    headings = {}
    offset = start = begin
    fence = None
    for line in source[begin:].splitlines(keepends=True):
        bare = line.rstrip("\r\n")
        marker = FENCE.fullmatch(bare)
        if fence:
            if marker and marker[1][0] == fence[0] and len(marker[1]) >= len(fence) and not marker[2].strip():
                fence = None
        elif marker:
            fence = marker[1]
        elif heading := HEADING.fullmatch(bare):
            if start < offset:
                yield start, offset, " / ".join(headings.values())
            level = len(heading[1])
            headings = {depth: title for depth, title in headings.items() if depth < level}
            headings[level] = heading[2].rstrip("# ").strip()
            start = offset
        offset += len(line)
    if start < len(source):
        yield start, len(source), " / ".join(headings.values())


def split(note, policy, limit, overlap):
    source = note.source
    begin = body_start(source)
    spans = section_spans(source, begin) if policy == "sections" else [(begin, len(source), "")]
    for start, stop, heading in spans:
        prefix = heading[-min(48, limit // 5):] + "\n" if heading else ""
        width = limit - len(prefix)
        while start < stop:
            end = min(start + width, stop)
            body = source[start:end].strip()
            if body:
                yield Chunk(note.id, start, end, prefix + body)
            if end == stop or policy == "front":
                break
            start = end - overlap


def chunk_map(notes, policy, limit, overlap):
    if not 32 <= limit <= 450 or not 0 <= overlap <= limit // 2:
        raise ValueError("Use a 32–450 code point window with overlap at most half the window")
    return {key: list(split(note, policy, limit, overlap)) for key, note in notes.items()}


def cache_counts(note, policy, limit, overlap):
    start = body_start(note.source)
    body = note.source[start:]
    first_heading = re.search(r"(?m)^ {0,3}#{1,6}[ \t]+[^\r\n]+", body)
    first_paragraph = re.search(r"(?m)^[^\s#`~>|-][^\r\n]+", body)
    changed = {
        "unchanged": note,
        "renamedPath": Note.from_text("different-path", note.source),
        "frontmatter": Note.from_text(note.id, "---\nrevision: benchmark\n---\n" + note.source[start:]),
        "bodyInsertion": Note.from_text(note.id, note.source[:start] + "本段补充一条观察。\n\n" + note.source[start:]),
        "appendedSection": Note.from_text(note.id, note.source + "\n\n## Addendum\n\nA later observation.\n"),
    }
    if first_heading:
        at = start + first_heading.end()
        changed["headingEdit"] = Note.from_text(note.id, note.source[:at] + " (revised)" + note.source[at:])
    if first_paragraph:
        at = start + first_paragraph.end()
        changed["paragraphEdit"] = Note.from_text(note.id, note.source[:at] + " Updated." + note.source[at:])
    original = {digest(chunk.text) for chunk in split(note, policy, limit, overlap)}
    counts = {"coldUniqueInputs": len(original), "modelFingerprintChanged": len(original)}
    for key, revised in changed.items():
        inputs = {digest(chunk.text) for chunk in split(revised, policy, limit, overlap)}
        counts[key] = dict(newInputs=len(inputs - original), totalInputs=len(inputs),
                           sourceRevisionChanged=revised.revision != note.revision)
    return counts


def coverage(notes, queries, chunks):
    hits = labels = 0
    for query in queries:
        for note, anchors in query.relevant.items():
            for anchor in anchors:
                start = notes[note].source.index(anchor)
                hits += any(row.start <= start and row.end >= start + len(anchor) for row in chunks[note])
                labels += 1
    return dict(anchorsCovered=hits, totalAnchors=labels)


def measure_chunking(notes, queries, limit, overlap):
    results = {}
    for policy in STRATEGIES:
        chunk_map(notes, policy, limit, overlap)
        samples = []
        for _ in range(5):
            start = perf_counter_ns()
            chunks = chunk_map(notes, policy, limit, overlap)
            samples.append((perf_counter_ns() - start) / 1e6)
        all_chunks = [chunk for rows in chunks.values() for chunk in rows]
        longest = max(notes.values(), key=lambda note: len(note.source))
        results[policy] = dict(
            extractionMs=median(samples), samplesMs=samples, chunks=len(all_chunks),
            uniqueInputs=len({digest(chunk.text) for chunk in all_chunks}),
            inputCodePoints=sum(len(chunk.text) for chunk in all_chunks),
            **coverage(notes, queries, chunks),
            cacheProjectionOnLongestNote={"note": longest.id, **cache_counts(longest, policy, limit, overlap)},
        )
    return results


def metrics(ranking, relevant):
    labels = set(relevant)
    return dict(
        precisionAt3=sum(key in labels for key in ranking[:3]) / 3,
        recallAt5=sum(key in labels for key in ranking[:5]) / len(labels),
        mrr=next((1 / (index + 1) for index, key in enumerate(ranking) if key in labels), 0),
        precisionCeilingAt3=min(3, len(labels)) / 3,
    )


def retrieval(notes, queries, chunks, vectors):
    results = []
    for query in queries:
        q = vectors[f"query:{query.id}"]
        ranked = []
        for note, candidates in chunks.items():
            if candidates:
                score, winner = max(
                    ((sum(a * b for a, b in zip(q, vectors[chunk.id], strict=True)), chunk) for chunk in candidates),
                    key=lambda match: match[0],
                )
                ranked.append((score, note, winner))
        ranked.sort(key=lambda hit: (-hit[0], hit[1]))
        lexical = [
            (sum(term.casefold() in note.source.casefold() for term in query.terms), note.id)
            for note in notes.values()
        ]
        lexical = [key for score, key in sorted(lexical, key=lambda item: (-item[0], item[1])) if score]
        top = [
            dict(note=key, score=score, start=chunk.start, end=chunk.end,
                 sourceRevision=notes[key].revision, excerpt=notes[key].source[chunk.start:chunk.end],
                 matchedAnchors=[anchor for anchor in query.relevant.get(key, ())
                                 if anchor in notes[key].source[chunk.start:chunk.end]])
            for score, key, chunk in ranked[:5]
        ]
        total_anchors = sum(map(len, query.relevant.values()))
        evidence_recall = sum(len(hit["matchedAnchors"]) for hit in top) / total_anchors if total_anchors else None
        results.append(dict(id=query.id, group=query.group, top=top, lexicalTop=lexical[:5],
                            semantic=metrics([key for _, key, _ in ranked], query.relevant),
                            lexical=metrics(lexical, query.relevant), evidenceRecallAt5=evidence_recall))
    return results


def validate_vectors(inputs, vectors, dimensions):
    if set(vectors) != {row["id"] for row in inputs}:
        raise ValueError("Returned embedding IDs differ from requested IDs")
    for name, vector in vectors.items():
        if len(vector) != dimensions or not all(math.isfinite(value) for value in vector):
            raise ValueError(f"Wrong dimensions or non-finite values for {name}")
        norm = math.sqrt(sum(value * value for value in vector))
        if not 0.99 <= norm <= 1.01:
            raise ValueError(f"Expected unit-normalized vector for {name}, got norm {norm}")
