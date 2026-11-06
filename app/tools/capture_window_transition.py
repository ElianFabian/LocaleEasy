"""Capture one opt-in sample transition on an explicitly selected disposable device."""
import argparse
import json
from pathlib import Path
import subprocess
import time

PACKAGE = "com.elianfabian.localeeasy"
SCALES = ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--path", choices=("manual", "framework"), default="framework")
    parser.add_argument("--animations", choices=("unchanged", "off"), default="unchanged")
    parser.add_argument("--local-relaunch", action="store_true", help="Use the debug configuration-callback fixture")
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    if args.local_relaunch and args.path != "framework":
        parser.error("--local-relaunch compares framework preferences and requires --path framework")
    args.out.mkdir(parents=True, exist_ok=False)
    adb = [args.adb, "-s", args.serial]

    def call(*parts, input_bytes=None, check=True):
        result = subprocess.run([*adb, *map(str, parts)], input=input_bytes, capture_output=True)
        if check and result.returncode:
            raise RuntimeError(result.stderr.decode(errors="replace") + result.stdout.decode(errors="replace"))
        return result

    def shell(*parts):
        return call("shell", *parts).stdout.decode().strip()

    original = {key: shell("settings", "get", "global", key) for key in SCALES}
    api = int(shell("getprop", "ro.build.version.sdk"))
    if api < 35:
        raise RuntimeError("Integrated surface tracing requires API 35+; run the instrumentation capture alone on API 33/34")
    sources = shell("perfetto", "--query")
    (args.out / "trace-sources.txt").write_text(sources)
    if "android.surfaceflinger.layers" not in sources:
        raise RuntimeError("This device does not expose the required SurfaceFlinger layer data source")
    metadata = {"serial": args.serial, "path": args.path, "animations": args.animations, "local_relaunch": args.local_relaunch,
                "api": api, "fingerprint": shell("getprop", "ro.build.fingerprint"), "original_scales": original}
    apk = next(line.removeprefix("package:") for line in shell("pm", "path", PACKAGE).splitlines() if line.endswith("base.apk"))
    metadata["installed_apk_sha256"] = shell("sha256sum", apk).split()[0]
    trace_name = f"locale-window-{time.time_ns()}.pftrace"
    remote = f"/data/misc/perfetto-traces/{trace_name}"
    try:
        if args.animations == "off":
            for key in SCALES:
                shell("settings", "put", "global", key, "0")
        metadata["capture_scales"] = {key: shell("settings", "get", "global", key) for key in SCALES}
        source = Path(__file__).with_name("window-trace.pbtxt")
        start = call("shell", "perfetto", "--background", "--txt", "-c", "-", "-o", remote,
                     input_bytes=source.read_bytes()).stdout.decode()
        (args.out / "perfetto-start.txt").write_text(start)
        pids = [line.strip() for line in start.splitlines() if line.strip().isdigit()]
        if not pids:
            raise RuntimeError("Perfetto did not start: " + start)
        metadata["perfetto_pid"] = pids[-1]
        result = call("shell", "am", "instrument", "-w", "-e", "class",
                      f"{PACKAGE}.RecreationWindowCaptureTest", "-e", "captureWindows", "true",
                      "-e", "capturePath", args.path,
                      "-e", "localRelaunch", str(args.local_relaunch).lower(),
                      f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner")
        report = (result.stdout + result.stderr).decode(errors="replace")
        (args.out / "instrumentation.txt").write_text(report)
        if "OK (1 test)" not in report:
            raise RuntimeError("Visual diagnostic did not pass; inspect instrumentation.txt")
        base = "files/window-captures/"
        frames = json.loads(call("exec-out", "run-as", PACKAGE, "cat", base + args.path + ".json").stdout)
        for frame in frames:
            name = frame["file"]
            if Path(name).name != name:
                raise RuntimeError("Invalid capture filename")
            (args.out / name).write_bytes(call("exec-out", "run-as", PACKAGE, "cat", base + name).stdout)
        (args.out / "frames.json").write_text(json.dumps(frames, indent=2))
        state = call("exec-out", "run-as", PACKAGE, "cat", base + args.path + "-state.json").stdout
        (args.out / "state.json").write_bytes(state)
        (args.out / "logcat.txt").write_bytes(call("logcat", "-d", "-t", "10000", "-v", "monotonic").stdout)
        (args.out / "events.txt").write_bytes(call("logcat", "-b", "events", "-d", "-t", "1000", "-v", "monotonic").stdout)
        deadline = time.monotonic() + 30
        while True:
            status = call("shell", "cat", f"/proc/{pids[-1]}/stat", check=False)
            if status.returncode or b"(perfetto)" not in status.stdout or b") Z " in status.stdout:
                break
            if time.monotonic() > deadline:
                raise RuntimeError("Perfetto did not finalize")
            time.sleep(.25)
        call("pull", remote, args.out / "window.pftrace")
        if (args.out / "window.pftrace").stat().st_size < 1024:
            raise RuntimeError("Trace was empty")
        call("shell", "rm", "-f", remote)
        print(f"Captured {len(frames)} screenshots and trace in {args.out.resolve()}")
    finally:
        if args.animations == "off":
            for key, value in original.items():
                if value == "null":
                    shell("settings", "delete", "global", key)
                else:
                    shell("settings", "put", "global", key, value)
        metadata["restored_scales"] = {key: shell("settings", "get", "global", key) for key in SCALES}
        (args.out / "environment.json").write_text(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
