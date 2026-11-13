#!/usr/bin/env python3
"""Drive Looma on a disposable, read-only Android emulator. Standard library only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ANDROID = Path(__file__).resolve().parents[4]
APP = "com.example.looma"
COMPONENT = APP + "/.MainActivity"
MARKER = "/data/local/tmp/looma-verify-owner"

def sdk_path():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        return Path(sdk)
    for line in (ANDROID / "local.properties").read_text().splitlines():
        if line.startswith("sdk.dir="):
            return Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    raise RuntimeError("Set ANDROID_HOME to the installed Android SDK")

class Control:
    def __init__(self, run):
        self.root = (ANDROID / run).resolve()
        base = (ANDROID / "app/build/verify").resolve()
        if not self.root.is_relative_to(base) or self.root == base:
            raise RuntimeError("--run must be a child of android/app/build/verify")
        self.root.mkdir(parents=True, exist_ok=True)
        self.state_path = self.root / "session.json"
        self.state = json.loads(self.state_path.read_text()) if self.state_path.exists() else {}
        self.sdk = sdk_path()
        self.adb = str(self.sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))

    def save(self):
        self.state_path.write_text(json.dumps(self.state, indent=2), encoding="utf-8")

    def log(self, **event):
        with (self.root / "actions.jsonl").open("a", encoding="utf-8") as out:
            out.write(json.dumps({"time": time.time(), **event}, ensure_ascii=False) + "\n")

    def command(self, args, timeout=30, check=True, binary=False):
        result = subprocess.run(args, capture_output=True, timeout=timeout)
        stdout = result.stdout.decode("utf-8", "replace") if not binary else "[binary artifact]"
        stderr = result.stderr.decode("utf-8", "replace")
        self.log(command=args, exit=result.returncode, stdout=stdout, stderr=stderr)
        if check and result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}): {args}\n{stdout}\n{stderr}")
        return result.stdout if binary else stdout.strip()

    def device(self, *args, **kwargs):
        return self.command([self.adb, "-s", self.state["serial"], *args], **kwargs)

    def owned(self):
        if self.state.get("status") != "running":
            raise RuntimeError("No running session; launch a fresh --run first")
        if self.device("shell", "cat", MARKER) != self.state["owner"]:
            raise RuntimeError("Device ownership marker changed; refuse to drive or stop it")

    def launch(self, avd, port):
        if self.state:
            raise RuntimeError("Run directory already has a session; use a new run name")
        if port < 5554 or port > 5682 or port % 2:
            raise RuntimeError("Use an even emulator console port in 5554..5682")
        for candidate in (port, port + 1):
            with socket.socket() as probe:
                if probe.connect_ex(("127.0.0.1", candidate)) == 0:
                    raise RuntimeError(f"Port {candidate} is occupied; choose another port")
        apk = ANDROID / "app/build/outputs/apk/debug/app-debug.apk"
        if not apk.is_file():
            raise RuntimeError("Build first: ./gradlew.bat assembleDebug --console=plain")
        emulator = self.sdk / "emulator" / ("emulator.exe" if os.name == "nt" else "emulator")
        self.state = {"serial": f"emulator-{port}", "avd": avd, "owner": self.root.name,
                      "vault": "/sdcard/Documents/" + self.root.name,
                      "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                      "status": "starting", "port": port}
        if not re.fullmatch(r"[A-Za-z0-9_-]+", self.root.name):
            raise RuntimeError("Run name must contain only ASCII letters, digits, _ or -")
        self.save()
        try:
            with (self.root / "emulator.log").open("wb") as log:
                proc = subprocess.Popen(
                    [str(emulator), "-avd", avd, "-port", str(port), "-read-only",
                     "-no-snapshot", "-no-window", "-no-audio", "-no-boot-anim"],
                    stdout=log, stderr=subprocess.STDOUT,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            self.state["pid"] = proc.pid
            self.save()
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                if proc.poll() is not None:
                    raise RuntimeError("Emulator exited; read emulator.log")
                try:
                    if self.device("shell", "getprop", "sys.boot_completed", timeout=5) == "1":
                        break
                except (RuntimeError, subprocess.TimeoutExpired):
                    pass
                time.sleep(2)
            else:
                raise RuntimeError("Emulator did not finish booting within 180 seconds")
            self.device("shell", "sh", "-c", shlex.quote(f"echo {self.state['owner']} > {MARKER}"))
            self.state["status"] = "running"
            self.save()

            self.device("uninstall", APP, check=False)
            self.device("install", str(apk), timeout=120)

            exists = self.command([self.adb, "-s", self.state["serial"], "shell", "ls",
                                   "-d", self.state["vault"]], check=False)
            if exists == self.state["vault"]:
                raise RuntimeError("Vault path already exists; choose a new run name")
            self.device("shell", "mkdir", "-p", self.state["vault"])
            self.device("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            self.device("shell", "wm", "dismiss-keyguard", check=False)
            self.device("shell", "am", "start", "-W", "-n", COMPONENT)
            self.wait({"text": "Choose a Looma vault"}, timeout=45)
            self.doctor()
            print(json.dumps(self.state, indent=2))
        except BaseException:
            self.cleanup()
            raise

    def dump(self):
        self.owned()
        self.device("shell", "uiautomator", "dump", "/data/local/tmp/looma-verify-ui.xml", timeout=25)
        xml = self.device("shell", "cat", "/data/local/tmp/looma-verify-ui.xml")
        tree = ET.fromstring(xml)
        self.log(ui=xml)
        return tree, xml

    @staticmethod
    def matches(tree, selectors):
        keys = {"text": "text", "desc": "content-desc", "cls": "class", "id": "resource-id"}
        return [node for node in tree.iter("node")
                if all(node.get(keys[k]) == v for k, v in selectors.items() if v is not None)]

    def wait(self, selectors, timeout=20, absent=False):
        deadline = time.monotonic() + timeout
        while True:
            tree, _ = self.dump()
            nodes = self.matches(tree, selectors)
            if bool(nodes) != absent:
                return nodes
            if time.monotonic() >= deadline:
                raise RuntimeError(f"UI assertion timed out: {selectors}, absent={absent}")
            time.sleep(0.5)

    def doctor(self, system=False):
        self.owned()
        if self.device("shell", "getprop", "sys.boot_completed") != "1":
            raise RuntimeError("Device is not booted")
        pid = self.device("shell", "pidof", APP)
        installed = self.device("shell", "pm", "path", APP).splitlines()[0].removeprefix("package:")
        if not re.fullmatch(r"/data/app/[A-Za-z0-9_=/+.~-]+/base.apk", installed):
            raise RuntimeError("Unexpected installed APK path")
        digest = self.device("shell", "sha256sum", installed).split()[0]
        if digest != self.state["apk_sha256"]:
            raise RuntimeError("Installed APK differs from the build launched by this run")
        focus = self.device("shell", "dumpsys", "activity", "activities")
        resumed = "\n".join(line for line in focus.splitlines()
                            if "mResumedActivity" in line or "topResumedActivity" in line)
        allowed = (APP,) if not system else (APP, "com.android.settings", "com.android.documentsui",
                                                           "com.google.android.documentsui", "com.android.permissioncontroller",
                                                           "com.android.providers.media.module",
                                                           "com.google.android.providers.media.module")
        if not any(package + "/" in resumed for package in allowed):
            raise RuntimeError("Unexpected foreground; inspect/reset before driving: " + resumed)
        tree, xml = self.dump()
        if not list(tree.iter("node")):
            raise RuntimeError("No usable UI nodes")
        report = {"healthy": True, "serial": self.state["serial"], "pid": pid,
                  "apk_sha256": digest, "foreground": resumed, "system_surface": system}
        (self.root / "doctor.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        (self.root / "doctor.xml").write_text(xml, encoding="utf-8")
        print(json.dumps(report, indent=2))

    def system_bars(self):
        dump = self.device("shell", "dumpsys", "window")
        frames = set()
        for match in re.finditer(r"type=(navigationBars|ime) frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\] visible=true", dump):
            frames.add((match.group(1), *map(int, match.groups()[1:])))
        return frames

    def tap(self, selectors, index, long_press, at=None):
        nodes = self.wait(selectors)
        if index is None and len(nodes) != 1:
            raise RuntimeError(f"Ambiguous selector ({len(nodes)} matches); inspect ui and pass --index")
        if index is not None and not 0 <= index < len(nodes):
            raise RuntimeError(f"--index {index} is out of range: {len(nodes)} node(s) match")
        node = nodes[index or 0]
        bounds = [int(n) for n in re.findall(r"\d+", node.attrib["bounds"])]
        fx, fy = (0.5, 0.5) if at is None else at
        x = bounds[0] + int((bounds[2] - bounds[0]) * fx)
        y = bounds[1] + int((bounds[3] - bounds[1]) * fy)
        for name, left, top, right, bottom in self.system_bars():
            if left <= x < right and top <= y < bottom:
                raise RuntimeError(f"Tap point {x},{y} is under the {name} window; scroll the node into view first")
        self.log(action="long-press" if long_press else "tap", selectors=selectors, index=index,
                 at=[fx, fy], point=[x, y], resolved_node=node.attrib)
        if long_press:
            self.device("shell", "input", "swipe", str(x), str(y), str(x), str(y), "800")
        else:
            self.device("shell", "input", "tap", str(x), str(y))

    def capture(self, name):
        if not re.fullmatch(r"[A-Za-z0-9_-]+", name):
            raise RuntimeError("Artifact name must use letters, digits, _ or -")
        if any((self.root / f"{name}{suffix}").exists() for suffix in (".png", ".xml", "-logcat.txt")):
            raise RuntimeError("Artifact already exists; use a new name to preserve earlier evidence")
        self.owned()
        _, xml = self.dump()
        (self.root / f"{name}.xml").write_text(xml, encoding="utf-8")
        png = self.device("exec-out", "screencap", "-p", binary=True)
        if not png.startswith(b"\x89PNG\r\n\x1a\n"):
            raise RuntimeError("Invalid screenshot")
        (self.root / f"{name}.png").write_bytes(png)
        (self.root / f"{name}-logcat.txt").write_text(
            self.device("logcat", "-d", "-t", "400"), encoding="utf-8")
        print(self.root / f"{name}.png")

    def files(self, name):
        if not re.fullmatch(r"[A-Za-z0-9_-]+", name):
            raise RuntimeError("Invalid artifact name")
        self.owned()
        target = self.root / f"{name}-vault"
        if target.exists():
            raise RuntimeError("Artifact already exists; use another name")
        self.device("pull", self.state["vault"], str(target))
        print(target)

    def fixture(self, source, destination, replace=False):
        self.owned()
        path = Path(source).resolve()
        if not path.is_file():
            raise RuntimeError("Fixture must be an existing host file")
        if not re.fullmatch(r"[A-Za-z0-9 _.()-]+", path.name) or path.name.startswith("."):
            raise RuntimeError("Fixture file name must use letters, digits, spaces, (, ), _, - or .")
        if replace and destination != "vault":
            raise RuntimeError("--replace only applies to --to vault")
        folder = (self.state["vault"] if destination == "vault"
                  else f"/sdcard/Pictures/{self.state['owner']}")
        target = f"{folder}/{path.name}"
        exists = self.command([self.adb, "-s", self.state["serial"], "shell", "ls", shlex.quote(target)],
                              check=False) == target
        if exists and not replace:
            raise RuntimeError("Fixture target already exists; use another file name or --replace")
        if replace and not exists:
            raise RuntimeError("--replace needs an existing vault file")
        self.device("shell", "mkdir", "-p", folder)
        self.device("push", str(path), target, timeout=60)
        if destination == "pictures":
            self.device("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                        "-d", "file://" + target)
        self.log(fixture=str(path), target=target, replaced=exists,
                 sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        print(target)

    def toggle(self, label):
        tree, _ = self.dump()
        labels = self.matches(tree, {"text": label})
        if len(labels) != 1:
            raise RuntimeError(f"Need exactly one '{label}' text node, found {len(labels)}")
        lb = [int(n) for n in re.findall(r"\d+", labels[0].attrib["bounds"])]
        cy = (lb[1] + lb[3]) // 2
        rows = []
        for node in tree.iter("node"):
            if node.get("checkable") != "true":
                continue
            b = [int(n) for n in re.findall(r"\d+", node.attrib["bounds"])]
            if b[1] <= cy <= b[3]:
                rows.append((node, b))
        if len(rows) != 1:
            raise RuntimeError(f"Need exactly one checkable node in the row of '{label}', found {len(rows)}")
        node, b = rows[0]
        before = node.get("checked")
        x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
        for name, left, top, right, bottom in self.system_bars():
            if left <= x < right and top <= y < bottom:
                raise RuntimeError(f"'{label}' switch is under the {name} window; scroll it into view first")
        self.log(action="toggle", label=label, resolved_node=node.attrib)
        self.device("shell", "input", "tap", str(x), str(y))
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            time.sleep(0.5)
            tree, _ = self.dump()
            for candidate in tree.iter("node"):
                if candidate.get("checkable") == "true" and candidate.get("bounds") == node.get("bounds"):
                    if candidate.get("checked") != before:
                        print(f"{label}: checked={candidate.get('checked')}")
                        return
        raise RuntimeError(f"'{label}' did not change from checked={before}")

    def port_open(self):
        with socket.socket() as probe:
            return probe.connect_ex(("127.0.0.1", self.state["port"])) == 0

    def cleanup(self):
        if self.state.get("status") == "cleaned" or not self.state.get("pid"):
            return
        if self.state.get("status") == "running" and not self.port_open():
            self.log(cleanup_note="emulator console port already closed; instance exited before cleanup")
            self.state["status"] = "cleaned"
            self.state["exited_early"] = True
            self.save()
            print(f"{self.state['serial']} had already exited; evidence retained: {self.root}")
            return
        if self.state.get("status") == "running":
            try:
                self.owned()
                self.device("emu", "kill", timeout=15)
            except (RuntimeError, subprocess.TimeoutExpired) as error:

                self.log(cleanup_error=str(error))
                raise RuntimeError("Cleanup needs inspection; ownership/ADB failed. See session.json and emulator.log") from error
        else:

            if os.name == "nt":
                self.command(["taskkill", "/PID", str(self.state["pid"]), "/T", "/F"], check=False)
            else:
                try:
                    os.kill(self.state["pid"], 15)
                except ProcessLookupError:
                    pass
        for _ in range(30):
            if not self.port_open():
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("Emulator port still open after cleanup")
        self.state["status"] = "cleaned"
        self.save()
        print(f"Cleaned {self.state['serial']}; evidence retained: {self.root}")

def fraction_pair(raw):
    try:
        fx, fy = (float(part) for part in raw.split(","))
    except ValueError:
        raise argparse.ArgumentTypeError("use two fractions such as 0.2,0.75")
    if not (0 <= fx <= 1 and 0 <= fy <= 1):
        raise argparse.ArgumentTypeError("fractions must be between 0 and 1")
    return fx, fy

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True, help="Child of app/build/verify, relative to android/")
    sub = parser.add_subparsers(dest="action", required=True)
    launch = sub.add_parser("launch")
    launch.add_argument("--avd", required=True)
    launch.add_argument("--port", type=int, default=5580)
    doctor = sub.add_parser("doctor")
    doctor.add_argument("--system", action="store_true")
    for action in ("tap", "assert"):
        p = sub.add_parser(action)
        for selector in ("text", "desc", "class", "id"):
            p.add_argument("--" + selector, dest="cls" if selector == "class" else selector)
        if action == "tap":
            p.add_argument("--index", type=int)
            p.add_argument("--long", action="store_true", dest="long_press")
            p.add_argument("--at", type=fraction_pair,
                           help="X,Y fractions inside the node's bounds, e.g. 0.2,0.75 (default 0.5,0.5)")
        else:
            p.add_argument("--absent", action="store_true")
    sub.add_parser("ui")
    sub.add_parser("select-all")
    sub.add_parser("cleanup")
    sub.add_parser("relaunch")
    text = sub.add_parser("text")
    text.add_argument("value")
    key = sub.add_parser("key")
    key.add_argument("codes", nargs="+")
    swipe = sub.add_parser("swipe")
    swipe.add_argument("direction", choices=["up", "down", "left", "right"])
    swipe.add_argument("--desc", help="left/right: swipe along the row of the node with this description")
    for action in ("capture", "files"):
        sub.add_parser(action).add_argument("name")
    sub.add_parser("toggle", help="Tap the switch or checkbox in the row of an exact text label").add_argument("label")
    fixture = sub.add_parser("fixture", help="Push a disposable host file as a boundary fixture")
    fixture.add_argument("source")
    fixture.add_argument("--to", choices=["pictures", "vault"], required=True, dest="destination")
    fixture.add_argument("--replace", action="store_true",
                         help="Overwrite an existing vault file to simulate another editor")
    args = parser.parse_args()
    c = Control(args.run)
    if args.action == "launch":
        c.launch(args.avd, args.port)
    elif args.action == "cleanup":
        c.cleanup()
    else:
        c.owned()
        if args.action == "doctor":
            c.doctor(args.system)
        elif args.action in ("tap", "assert"):
            selectors = {k: getattr(args, k) for k in ("text", "desc", "cls", "id")
                         if getattr(args, k) is not None}
            if not selectors:
                raise RuntimeError("Specify at least one selector")
            if args.action == "tap":
                c.tap(selectors, args.index, args.long_press, args.at)
            else:
                c.wait(selectors, absent=args.absent)
                print("Assertion passed")
        elif args.action == "ui":
            tree, _ = c.dump()
            fields = ("text", "content-desc", "resource-id", "class", "bounds", "checked", "clickable", "enabled")
            print(json.dumps([{k: n.get(k) for k in fields} for n in tree.iter("node") if n.get("text")
                              or n.get("content-desc") or n.get("clickable") == "true"
                              or n.get("checkable") == "true" or n.get("class") == "android.widget.EditText"],
                             indent=2, ensure_ascii=False))
        elif args.action == "text":
            if not args.value.isascii() or "\n" in args.value or "%" in args.value:
                raise RuntimeError("ADB text supports single-line ASCII without %; use key ENTER for line breaks")
            c.device("shell", "input", "text", shlex.quote(args.value.replace(" ", "%s")))
        elif args.action == "select-all":
            c.device("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
        elif args.action == "key":
            if not all(re.fullmatch(r"KEYCODE_[A-Z0-9_]+", code) for code in args.codes):
                raise RuntimeError("Use Android KEYCODE_ names")
            c.device("shell", "input", "keyevent", *args.codes)
        elif args.action == "swipe":
            size = c.device("shell", "wm", "size")
            width, height = map(int, re.findall(r"(\d+)x(\d+)", size)[-1])
            if args.direction in ("left", "right"):
                if not args.desc:
                    raise RuntimeError("Horizontal swipes need --desc of a node in the row to scroll")
                node = c.wait({"desc": args.desc})[0]
                bounds = [int(n) for n in re.findall(r"\d+", node.attrib["bounds"])]
                y = (bounds[1] + bounds[3]) // 2
                start, end = (0.85, 0.15) if args.direction == "left" else (0.15, 0.85)
                c.device("shell", "input", "swipe", str(int(width * start)), str(y),
                         str(int(width * end)), str(y), "450")
                return
            start, end = (0.8, 0.3) if args.direction == "up" else (0.3, 0.8)
            c.device("shell", "input", "swipe", str(width // 2), str(int(height * start)),
                     str(width // 2), str(int(height * end)), "450")
        elif args.action == "relaunch":
            c.device("shell", "am", "force-stop", APP)
            c.device("shell", "am", "start", "-W", "-n", COMPONENT)
            c.doctor()
        elif args.action == "fixture":
            c.fixture(args.source, args.destination, args.replace)
        elif args.action == "toggle":
            c.toggle(args.label)
        else:
            getattr(c, args.action)(args.name)

if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.TimeoutExpired, OSError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
