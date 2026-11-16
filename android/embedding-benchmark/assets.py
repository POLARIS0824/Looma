from dataclasses import dataclass
import hashlib
from pathlib import Path
import shutil
import tarfile
import tempfile
from urllib.request import urlopen
import zipfile


@dataclass(frozen=True)
class Asset:
    path: str
    url: str
    sha256: str

    @classmethod
    def huggingface(cls, path, repo, revision, filename, sha256):
        return cls(path, f"https://huggingface.co/{repo}/resolve/{revision}/{filename}", sha256)

    def find(self, directory, offline):
        target = directory / self.path
        if target.is_file() and hash_file(target) == self.sha256:
            return target
        if offline:
            raise FileNotFoundError(f"Missing or invalid cached asset {target}")
        target.parent.mkdir(parents=True, exist_ok=True)
        print(f"Downloading {self.path}", flush=True)
        handle = tempfile.NamedTemporaryFile(dir=target.parent, suffix=".partial", delete=False)
        temp = Path(handle.name)
        try:
            with handle as out, urlopen(self.url, timeout=60) as response:
                shutil.copyfileobj(response, out, length=2**20)
            if hash_file(temp) != self.sha256:
                raise ValueError(f"Checksum mismatch for {self.path}")
            temp.replace(target)
        finally:
            temp.unlink(missing_ok=True)
        return target


def hash_file(path):
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


@dataclass(frozen=True)
class Model:
    dimensions: int
    weights: Asset
    tokenizer: Asset | None = None


hf = Asset.huggingface
gemma = Model(768, hf(
    "gemma/model.litertlm", "litert-community/embeddinggemma-2-text-270m-litert-lm",
    "9be6e8b90982095dc05c2bd162e4b954ee4dbac7", "embeddinggemma-2-text-270m.litertlm",
    "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb",
))
MODELS = {
    "bge": Model(512, hf(
        "bge/model.onnx", "Xenova/bge-small-zh-v1.5", "75c43b069aac4d136ba6bc1122f995fedcfd2781",
        "onnx/model_int8.onnx", "b9837c19ce154ff0726d398ee77abbc03a7faf0476c6f93016c84e531be7ebb5",
    ), hf(
        "bge/vocab.txt", "BAAI/bge-small-zh-v1.5", "7999e1d3359715c523056ef9478215996d62a620", "vocab.txt",
        "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c",
    )),
    "bekko": Model(128, hf(
        "bekko/model.onnx", "hotchpotch/bekko-static-embedding-v1", "c18167b8f244c1bc6de21ce9b710bac8a29c4615",
        "onnx/model_int8_dim128.onnx", "b848886b593c3b00e99815482ac1e963f93f194ed087390c2ee769d14206e1ab",
    ), hf(
        "bekko/tokenizer.json", "hotchpotch/bekko-static-embedding-v1", "c18167b8f244c1bc6de21ce9b710bac8a29c4615",
        "tokenizer.json", "308823060352e35bf9fff6b42ef343f31cc68fa800e284568cc44e9cc0f70a73",
    )),
    "gemma-cpu": gemma,
    "gemma-gpu": gemma,
    "qwen06": Model(1024, hf(
        "qwen06/model.gguf", "Qwen/Qwen3-Embedding-0.6B-GGUF", "370f27d7550e0def9b39c1f16d3fbaa13aa67728",
        "Qwen3-Embedding-0.6B-Q8_0.gguf", "06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439",
    )),
    "qwen4": Model(2560, hf(
        "qwen4/model.gguf", "Qwen/Qwen3-Embedding-4B-GGUF", "f4602530db1d980e16da9d7d3a70294cf5c190be",
        "Qwen3-Embedding-4B-Q4_K_M.gguf", "2b0cf8f17b4c723c27303015383c27ec4bf2d8314bb677d05e920dd70bb0f16b",
    )),
}

LLAMA_RELEASE = "b12430"
LLAMA_ARCHIVES = {
    "android": ("bin-android-arm64.tar.gz", "021f66ee04276a843c6592b947824c019f1ada217168c87b8502899167776a70"),
    "win": ("bin-win-cpu-x64.zip", "663f2b08d8710db3f7ac8b8d5055cccbf3e0cbcb60d33d9d5d427387935f85b7"),
}


def llama_binary(assets, platform, offline):
    suffix, sha = LLAMA_ARCHIVES[platform]
    filename = f"llama-{LLAMA_RELEASE}-{suffix}"
    asset = Asset(f"runtime/{filename}",
                  f"https://github.com/ggml-org/llama.cpp/releases/download/{LLAMA_RELEASE}/{filename}", sha)
    archive = asset.find(assets, offline)
    destination = archive.parent / f"{platform}-{LLAMA_RELEASE}"
    executable = "llama-server.exe" if platform == "win" else "llama-server"
    if not (destination / ".complete").is_file():
        with tempfile.TemporaryDirectory(dir=archive.parent) as temp:
            unpacked = Path(temp) / "unpacked"
            unpacked.mkdir()
            if platform == "win":
                with zipfile.ZipFile(archive) as files:
                    files.extractall(unpacked)
            else:
                with tarfile.open(archive) as files:
                    files.extractall(unpacked, filter="data")
            (unpacked / ".complete").touch()
            try:
                unpacked.rename(destination)
            except OSError:
                if not (destination / ".complete").is_file():
                    raise
    return next(destination.rglob(executable))
