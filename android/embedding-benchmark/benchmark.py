import argparse
import json
from pathlib import Path
from statistics import median, mean
import sys

from assets import MODELS, llama_binary
from backends import Device, android_inference, llama_inference, stage_app, stage_llama
from corpus import STRATEGIES, chunk_map, coverage, digest, load_corpus, measure_chunking, retrieval, validate_vectors


ROOT = Path(__file__).resolve().parent
PAIRED_MODEL = "gemma-pair"


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(path, flush=True)


def metadata(notes, queries):
    return dict(notes=len(notes), queries=len(queries),
                codePoints=sum(len(note.source) for note in notes.values()),
                longestNoteCodePoints=max(len(note.source) for note in notes.values()),
                notesHash=digest(json.dumps({key: note.revision for key, note in notes.items()}, sort_keys=True)),
                judgmentsHash=digest(json.dumps([query.__dict__ for query in queries], ensure_ascii=False, sort_keys=True)))


def make_request(args, chunks, queries, weights, tokenizer):
    inputs = [
        dict(id=chunk.id, text=chunk.text, role="doc")
        for candidates in chunks.values() for chunk in candidates
    ]
    query_inputs = [dict(id=f"query:{row.id}", text=row.text, role="query") for row in queries]
    by_group = {}
    for query, row in zip(queries, query_inputs, strict=True):
        by_group.setdefault(query.group, []).append(row)
    latency = [rows[round_index] for round_index in range(2)
               for rows in by_group.values() if round_index < len(rows)][:8]
    references = []
    if tokenizer:
        fixture = json.loads((ROOT / "tokenizer-references.json").read_text(encoding="utf-8"))
        references = [dict(text=row["text"], ids=row[args.model]) for row in fixture]
    return dict(model=args.model, weights=weights.name, tokenizer=tokenizer.name if tokenizer else None,
                threads=args.threads, inputs=inputs + query_inputs, latency=latency, repeats=args.rounds,
                workload=args.workloads, tokenizerReferences=references)


def summary(rows):
    by_group = {}
    for row in rows:
        by_group.setdefault(row["group"], []).append(row)
    return {
        group: {
            "queries": len(members),
            **{kind: {key: mean(row[kind][key] for row in members) for key in members[0][kind]}
               for kind in ("semantic", "lexical")},
            "evidenceRecallAt5": mean(evidence) if (evidence := [
                row["evidenceRecallAt5"] for row in members if row["evidenceRecallAt5"] is not None
            ]) else None,
        }
        for group, members in by_group.items()
    }


def paired_gemma(device, request, output_dir, model_sha):
    request = {**request, "inputs": request["latency"][:1]}
    results = []
    for trial in range(5):
        order = ["gemma-cpu", "gemma-gpu"]
        if trial % 2:
            order.reverse()
        for delegate in order:
            output = android_inference(device, {**request, "model": delegate},
                                       output_dir / f"pair-{trial + 1}-{delegate}.log")
            vectors = output.pop("vectors")
            validate_vectors(request["inputs"], vectors, 768)
            results.append(dict(output, trial=trial + 1, model=delegate, p50Ms=median(output["samplesMs"])))
            write(output_dir / "gemma-delegates-paired.json", dict(
                scope="Alternating fresh processes, query-role latency only",
                modelSha256=model_sha, latencyInputs=request["latency"],
                roundsPerProcess=request["repeats"], runs=results,
            ))


def run(args, notes, queries, output_dir):
    qwen = args.model.startswith("qwen")
    if args.target == "host" and (not qwen or sys.platform != "win32"):
        raise ValueError("The pinned host runtime supports Qwen on Windows x64")
    model = MODELS["gemma-cpu" if args.model == PAIRED_MODEL else args.model]
    device = Device(args.serial) if args.target == "android" else None
    weights = model.weights.find(args.assets, args.offline)
    tokenizer = model.tokenizer.find(args.assets, args.offline) if model.tokenizer else None
    assets = [weights] + ([tokenizer] if tokenizer else [])
    if qwen:
        executable = llama_binary(args.assets, args.target if device else "win", args.offline)
        staged = stage_llama(device, executable, weights, args.model) if device else None
    else:
        stage_app(device, args.apk, assets)

    if args.model == PAIRED_MODEL:
        request = make_request(args, {}, queries, weights, tokenizer)
        paired_gemma(device, request, output_dir, model.weights.sha256)
        return
    for strategy in STRATEGIES if args.strategy == "all" else (args.strategy,):
        chunks = chunk_map(notes, strategy, args.chunk_chars, args.overlap_chars)
        if any(not fragments for fragments in chunks.values()):
            raise ValueError("Each corpus note must contain an indexable chunk")
        request = make_request(args, chunks, queries, weights, tokenizer)
        name = f"{args.model}-{args.target}-{strategy}"
        output = (llama_inference(request, executable, weights, device, staged, output_dir / f"{name}.log")
                  if qwen else android_inference(device, request, output_dir / f"{name}.log"))
        vectors = output.pop("vectors")
        validate_vectors(request["inputs"], vectors, model.dimensions)
        quality = retrieval(notes, queries, chunks, vectors)
        samples = sorted(output["samplesMs"])
        if len(samples) != len(request["latency"]) * args.rounds:
            raise ValueError("Latency sample count differs from the requested workload")
        output.setdefault("latencyBoundary", "in-process tokenization and inference")
        report = dict(
            output, **metadata(notes, queries),
            model=args.model, dimensions=model.dimensions, target=args.target, strategy=strategy,
            chunkChars=args.chunk_chars, overlapChars=args.overlap_chars, offsetUnit="Unicode code point, end exclusive",
            inputs=len(vectors), inputHash=digest(json.dumps(request["inputs"], ensure_ascii=False, sort_keys=True)),
            latencyInputs=[row["id"] for row in request["latency"]],
            modelSha256=model.weights.sha256, tokenizerSha256=model.tokenizer.sha256 if model.tokenizer else None,
            modelBytes=weights.stat().st_size, tokenizerBytes=tokenizer.stat().st_size if tokenizer else 0,
            workloadInputProfile="repeated 128-token text" if tokenizer else "round-robin query-role texts",
            p50Ms=median(samples), p95Ms=samples[(95 * len(samples) + 99) // 100 - 1],
            qualityByGroup=summary(quality), queryResults=quality, evidenceCoverage=coverage(notes, queries, chunks),
        )
        write(output_dir / f"{name}.json", report)


def count_list(text):
    values = [int(part) for part in text.split(",")] if text else []
    if any(not 1 <= value <= 1000 for value in values):
        raise argparse.ArgumentTypeError("Expected comma-separated chunk counts between 1 and 1000")
    return values


def main():
    parser = argparse.ArgumentParser(description="Measure Looma embedding retrieval and Markdown chunk strategies.")
    parser.add_argument("action", choices=("chunking", "run"))
    parser.add_argument("--corpus", type=Path, default=ROOT / "corpus")
    parser.add_argument("--assets", type=Path, default=ROOT / "build/assets")
    parser.add_argument("--apk", type=Path, default=ROOT / "build/outputs/apk/debug/embedding-benchmark-debug.apk")
    parser.add_argument("--model", choices=(*MODELS, PAIRED_MODEL), default="gemma-cpu")
    parser.add_argument("--target", choices=("android", "host"), default="android")
    parser.add_argument("--serial")
    parser.add_argument("--strategy", choices=(*STRATEGIES, "all"))
    parser.add_argument("--chunk-chars", type=int, default=240)
    parser.add_argument("--overlap-chars", type=int, default=48)
    parser.add_argument("--threads", type=int, choices=range(1, 9), default=2)
    parser.add_argument("--rounds", type=int, choices=range(1, 21))
    parser.add_argument("--workloads", type=count_list, default=[])
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    if args.model == PAIRED_MODEL and (args.strategy or args.workloads):
        parser.error("gemma-pair measures query latency; use gemma-cpu or gemma-gpu for chunk strategies and workloads")
    args.strategy = args.strategy or "sections"
    args.rounds = args.rounds or (1 if args.model == PAIRED_MODEL else 5)
    args.assets = args.assets.resolve()
    output = ROOT / "build/reports"
    output.mkdir(parents=True, exist_ok=True)
    notes, queries = load_corpus(args.corpus)
    if args.action == "chunking":
        results = measure_chunking(notes, queries, args.chunk_chars, args.overlap_chars)
        write(output / "chunking.json", dict(**metadata(notes, queries), chunkChars=args.chunk_chars,
                                            overlapChars=args.overlap_chars, strategies=results))
    else:
        run(args, notes, queries, output)


if __name__ == "__main__":
    main()
