import http.client
import json
import os
from pathlib import Path
import random
import re
import shlex
import socket
from statistics import median
import subprocess
import tempfile
from time import perf_counter, sleep

from assets import LLAMA_RELEASE, hash_file


PACKAGE = "com.example.looma.embeddingbenchmark"
REMOTE = "/data/local/tmp/looma-embedding-benchmark"
INSTRUCTION = "Given a search query about a Markdown note vault, retrieve relevant passages that answer the query."


class Device:
    def __init__(self, serial):
        if not serial:
            raise ValueError("Android runs require --serial (Gradle -PembeddingSerial=...)")
        self.serial = serial
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        self.adb = str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")) if sdk else "adb"
        state = self.run("get-state", check=False, capture_output=True, text=True)
        if state.returncode or state.stdout.strip() != "device":
            raise RuntimeError(f"ADB device {serial} is unavailable: {state.stderr.strip()}")

    def run(self, *command, **options):
        options.setdefault("check", True)
        return subprocess.run([self.adb, "-s", self.serial, *map(str, command)], **options)

    def shell_text(self, *command):
        return self.run("shell", *command, capture_output=True, encoding="utf-8", errors="replace").stdout.strip()

    def properties(self):
        return dict(device=self.shell_text("getprop", "ro.product.model"),
                    abi=self.shell_text("getprop", "ro.product.cpu.abi"),
                    api=int(self.shell_text("getprop", "ro.build.version.sdk")))

    def push(self, source, target):
        expected = hash_file(source)
        probe = self.run("shell", f"if [ -f {shlex.quote(target)} ]; then sha256sum {shlex.quote(target)}; fi",
                         capture_output=True, encoding="utf-8")
        if not probe.stdout.startswith(expected):
            self.run("push", source, target)
            actual = self.shell_text("sha256sum", target).split()
            if not actual or actual[0] != expected:
                raise ValueError(f"Device checksum mismatch for {target}")


def stage_app(device, apk, files):
    packages = device.run("shell", "pm", "path", PACKAGE, check=False, capture_output=True, text=True)
    installed = packages.stdout.strip().removeprefix("package:") if packages.returncode == 0 else ""
    installed_hash = device.run("shell", "sha256sum", installed, check=False, capture_output=True, text=True) if installed else None
    if installed_hash is None or installed_hash.returncode or not installed_hash.stdout.startswith(hash_file(apk)):
        device.run("install", "-r", apk)
    device.run("shell", "mkdir", "-p", REMOTE)
    device.run("shell", "run-as", PACKAGE, "mkdir", "-p", "files/weights")
    for source in files:
        destination = f"files/weights/{source.name}"
        expected = hash_file(source)
        probe = device.shell_text(f"run-as {PACKAGE} sh -c 'if [ -f {destination} ]; then sha256sum {destination}; fi'")
        if probe.startswith(expected):
            continue
        staged = f"{REMOTE}/{expected[:16]}-{source.name}"
        try:
            device.push(source, staged)
            device.run("shell", f"cat {staged} | run-as {PACKAGE} sh -c 'cat > {destination}'")
            actual = device.shell_text("run-as", PACKAGE, "sha256sum", destination).split()
            if not actual or actual[0] != expected:
                raise ValueError(f"App sandbox checksum mismatch for {destination}")
        finally:
            device.run("shell", "rm", "-f", staged)


def android_inference(device, request, log_path):
    descriptor, filename = tempfile.mkstemp(prefix="embedding-request-", suffix=".json")
    local = Path(filename)
    name = f"{REMOTE}/{local.name}"
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as file:
            json.dump(request, file, ensure_ascii=False)
        device.run("push", local, name)
        device.run("shell", f"cat {name} | run-as {PACKAGE} sh -c 'cat > files/request.json'")
        device.run("shell", "run-as", PACKAGE, "rm", "-f", "files/result.json")
        device.run("shell", "am", "force-stop", PACKAGE)
        output = device.shell_text("am", "instrument", "-w", f"{PACKAGE}/.EmbeddingInstrumentation")
        log_path.write_text(output + "\n", encoding="utf-8")
        print(output, flush=True)
        if "Wrote " not in output:
            raise RuntimeError(f"Android instrumentation produced no benchmark report; see {log_path}")
        report = device.run("exec-out", "run-as", PACKAGE, "cat", "files/result.json", capture_output=True)
        result = json.loads(report.stdout)
        if result.get("model") != request["model"]:
            raise ValueError("Android instrumentation returned a different model than requested")
        return result
    finally:
        local.unlink(missing_ok=True)
        device.run("shell", "rm", "-f", name)


def stage_llama(device, executable, weights, model):
    if device.properties()["abi"] != "arm64-v8a":
        raise ValueError("This pinned Android llama.cpp runtime requires arm64-v8a")
    runtime = f"{REMOTE}/runtime-{LLAMA_RELEASE}"
    model_path = f"{REMOTE}/{model}.gguf"
    device.run("shell", "mkdir", "-p", runtime)
    for item in sorted(executable.parent.iterdir()):
        if item == executable or item.suffix == ".so":
            device.push(item, f"{runtime}/{item.name}")
    device.run("shell", "chmod", "700", f"{runtime}/llama-server")
    device.push(weights, model_path)
    return runtime, model_path


def embed(port, row):
    text = row["text"]
    if row["role"] == "query":
        text = f"Instruct: {INSTRUCTION}\n Query:{text}"
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=120)
    try:
        start = perf_counter()
        conn.request("POST", "/v1/embeddings", body=json.dumps({"input": text, "encoding_format": "float"}),
                     headers={"Content-Type": "application/json"})
        response = conn.getresponse()
        payload = response.read()
        elapsed = (perf_counter() - start) * 1000
        if response.status != 200:
            raise RuntimeError(f"llama.cpp rejected {row['id']}: {payload.decode(errors='replace')}")
        result = json.loads(payload)
        tokens = result["usage"]["prompt_tokens"]
        if tokens <= 0 or len(result["data"]) != 1:
            raise ValueError(f"Expected one nonempty evaluated input for {row['id']}")
        return result["data"][0]["embedding"], elapsed, tokens
    finally:
        conn.close()


def wait_ready(proc, port, log_path):
    deadline = perf_counter() + 120
    while perf_counter() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f"llama.cpp exited {proc.returncode}; see {log_path}")
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=0.5)
        try:
            conn.request("GET", "/health")
            if conn.getresponse().status == 200:
                return
        except (OSError, http.client.HTTPException):
            pass
        finally:
            conn.close()
        sleep(0.1)
    raise TimeoutError(f"llama.cpp did not become healthy; see {log_path}")


def llama_inference(request, executable, weights, device, staged, log_path):
    device_port = random.randrange(20000, 60000)
    if device:
        output = device.run("forward", "tcp:0", f"tcp:{device_port}", capture_output=True, text=True).stdout
        port = int(output.strip())
    else:
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
    args = ["--embedding", "--pooling", "last", "--embd-normalize", "2", "-ngl", "0",
            "-t", str(request["threads"]), "-tb", str(request["threads"]), "-c", "1024",
            "-b", "512", "-ub", "512", "-np", "1", "--host", "127.0.0.1",
            "--port", str(device_port if device else port), "--no-warmup"]
    if device:
        runtime, model = staged
        cmd = shlex.join(["./llama-server", "-m", model, *args])
        command = [device.adb, "-s", device.serial, "shell",
                   f"cd {runtime} && echo $$ && exec env LD_LIBRARY_PATH=. {cmd}"]
    else:
        command = [str(executable), "-m", str(weights), *args]

    proc = None
    try:
        with log_path.open("wb") as log:
            start = perf_counter()
            proc = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            wait_ready(proc, port, log_path)
            load_ms = (perf_counter() - start) * 1000
            latency = request["latency"]
            _, first_ms, _ = embed(port, latency[0])
            for row in latency[1:]:
                embed(port, row)
            rounds = [[embed(port, row) for row in latency] for _ in range(request["repeats"])]
            start = perf_counter()
            vectors = {row["id"]: embed(port, row)[0] for row in request["inputs"]}
            corpus_ms = (perf_counter() - start) * 1000
            workloads = []
            for count in request["workload"]:
                start = perf_counter()
                for index in range(count):
                    embed(port, latency[index % len(latency)])
                workloads.append(dict(chunks=count, timeMs=(perf_counter() - start) * 1000))
            report = dict(backend=f"llama.cpp {LLAMA_RELEASE} CPU", threads=request["threads"],
                          loadMs=load_ms, firstEmbeddingMs=first_ms, corpusMs=corpus_ms,
                          latencyBoundary="HTTP round trip over ADB" if device else "local HTTP round trip",
                          samplesMs=[ms for group in rounds for _, ms, _ in group],
                          roundMedianMs=[median(ms for _, ms, _ in group) for group in rounds],
                          tokenCounts=[tokens for group in rounds for _, _, tokens in group],
                          workloads=workloads, vectors=vectors)
            if device:
                pid = server_pid(log_path)
                detail = device.shell_text("cat", f"/proc/{pid}/status")
                report.update(device.properties())
                hwm = re.search(r"^VmHWM:\s+(\d+)", detail, re.M)
                if hwm:
                    report["peakRssKiB"] = int(hwm[1])
            else:
                report["peakRssKiB"] = win_peak_working_set_kib(proc.pid)
                report["memoryBoundary"] = "Windows process peak working set"
            if re.search(r"truncated = [1-9]", log_path.read_text(encoding="utf-8", errors="replace")):
                raise ValueError(f"llama.cpp truncated an input; see {log_path}")
            return report
    finally:
        if device:
            pid = server_pid(log_path)
            if pid and proc and proc.poll() is None:
                device.run("shell", "kill", "-TERM", pid, check=False)
            device.run("forward", "--remove", f"tcp:{port}", check=False)
        if proc:
            if proc.poll() is None:
                proc.terminate()
            proc.wait(timeout=15)


def server_pid(log_path):
    if log_path.is_file():
        with log_path.open(encoding="utf-8", errors="replace") as log:
            line = log.readline().strip()
            if line.isdecimal():
                return line
    return None


def win_peak_working_set_kib(pid):
    import ctypes
    from ctypes import wintypes

    class MemoryCounters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in (
                "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
                "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage",
                "PagefileUsage", "PeakPagefileUsage",
            )
        ]

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.K32GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(MemoryCounters), wintypes.DWORD]
    process = kernel.OpenProcess(0x1000, False, pid)
    if not process:
        return None
    try:
        counters = MemoryCounters()
        counters.cb = ctypes.sizeof(counters)
        if kernel.K32GetProcessMemoryInfo(process, ctypes.byref(counters), counters.cb):
            return counters.PeakWorkingSetSize // 1024
    finally:
        kernel.CloseHandle(process)
    return None
