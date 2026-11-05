"""Run isolated process-per-trial comparisons and preserve raw artifacts. Standard library only."""
import argparse
import csv
import json
from pathlib import Path
import random
import statistics
import subprocess
import time
from analyze_artifacts import class_diff, method_trace

PACKAGE = "com.elianfabian.recreationlab"
CASES = ["recreate", "rotation", "manual_locale", "framework_locale", "localeeasy", "view_only"]
ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit disposable emulator/device serial")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--apk", type=Path, help="Install/replace only the isolated lab package")
    parser.add_argument("--cases", nargs="+", choices=CASES, default=CASES)
    parser.add_argument("--mode", choices=["timing", "memory", "sampling", "methods", "heap"], default="timing")
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--rows", type=int, default=200)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--perfetto", action="store_true", help="Separate diagnostic run; not an unprofiled timing result")
    parser.add_argument("--out", type=Path, default=ROOT / "build" / "recreation-lab" / time.strftime("%Y%m%d-%H%M%S"))
    args = parser.parse_args()
    if args.iterations < 1 or not 1 <= args.rows <= 2000:
        parser.error("iterations must be positive and rows must be 1..2000")
    args.out.mkdir(parents=True, exist_ok=False)
    adb = [args.adb, "-s", args.serial]

    def call(*parts, check=True, input_bytes=None):
        response = subprocess.run([*adb, *map(str, parts)], input=input_bytes, capture_output=True)
        if check and response.returncode:
            raise RuntimeError(f"ADB command failed: {parts}\n{response.stdout.decode(errors='replace')}\n{response.stderr.decode(errors='replace')}")
        return response

    if args.apk:
        call("install", "-r", args.apk.resolve())
    api = int(call("shell", "getprop", "ro.build.version.sdk").stdout)
    supported = [case for case in args.cases if case != "framework_locale" or api >= 33]
    metadata = {"serial": args.serial, "api": api, "seed": args.seed, "mode": args.mode,
                "rows": args.rows, "iterations": args.iterations, "perfetto": args.perfetto,
                "skipped_cases": [case for case in args.cases if case not in supported],
                "fingerprint": call("shell", "getprop", "ro.build.fingerprint").stdout.decode().strip()}
    apk_paths = call("shell", "pm", "path", PACKAGE).stdout.decode().splitlines()
    base_apk = next(line.removeprefix("package:").strip() for line in apk_paths if line.strip().endswith("base.apk"))
    metadata["installed_apk_sha256"] = call("shell", "sha256sum", base_apk).stdout.decode().split()[0]
    metadata["display_size"] = call("shell", "wm", "size").stdout.decode().strip()
    metadata["display_density"] = call("shell", "wm", "density").stdout.decode().strip()
    (args.out / "environment.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    jobs = [(case, iteration) for iteration in range(args.iterations) for case in supported]
    random.Random(args.seed).shuffle(jobs)
    results = []
    try:
        for case, iteration in jobs:
            run_id = f"{case}-{args.mode}-{iteration}-{time.time_ns()}"
            folder = args.out / run_id
            folder.mkdir()
            print(f"Starting {case} #{iteration + 1} ({args.mode})", flush=True)
            call("shell", "am", "force-stop", PACKAGE)
            trace_path = f"/data/misc/perfetto-traces/{run_id}.pftrace"
            perfetto_pid = None
            if args.perfetto:
                # Feed config through stdin: newer SELinux policies block perfetto from reading /data/local/tmp.
                response = call("shell", "perfetto", "--background", "--txt", "-c", "-", "-o", trace_path,
                                input_bytes=Path(__file__).with_name("perfetto.pbtxt").read_bytes())
                (folder / "perfetto-start.txt").write_bytes(response.stdout + response.stderr)
                pids = [line for line in response.stdout.decode().splitlines() if line.strip().isdigit()]
                if not pids:
                    raise RuntimeError(f"Perfetto did not report a background PID: {response.stdout!r}")
                perfetto_pid = pids[-1].strip()
            launch = call("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.HarnessActivity",
                          "--es", "case", case, "--es", "mode", args.mode, "--es", "run_id", run_id,
                          "--ei", "rows", args.rows)
            (folder / "launch.txt").write_bytes(launch.stdout + launch.stderr)
            deadline = time.monotonic() + 60
            while True:
                response = call("shell", "run-as", PACKAGE, "cat", f"files/runs/{run_id}/result.json", check=False)
                if response.returncode == 0:
                    result = json.loads(response.stdout)
                    break
                if time.monotonic() > deadline:
                    logs = call("logcat", "-d", "-t", "500", check=False)
                    (folder / "failure-logcat.txt").write_bytes(logs.stdout)
                    raise RuntimeError(f"No result from {case}; inspect {folder}")
                time.sleep(0.2)
            result["external_perfetto"] = args.perfetto
            result["timing_is_profiled"] = result["timing_is_profiled"] or args.perfetto
            (folder / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
            (folder / "meminfo.txt").write_bytes(call("shell", "dumpsys", "meminfo", PACKAGE).stdout)
            (folder / "gfxinfo.txt").write_bytes(call("shell", "dumpsys", "gfxinfo", PACKAGE, "framestats").stdout)
            artifacts = ["methods.trace"] if args.mode in {"methods", "sampling"} else ["before.hprof", "after.hprof"] if args.mode == "heap" else []
            for name in artifacts:
                # Binary-safe on Windows too: never redirect a heap/trace through a text shell.
                with (folder / name).open("wb") as output:
                    subprocess.run([*adb, "exec-out", "run-as", PACKAGE, "cat", f"files/runs/{run_id}/{name}"], stdout=output, check=True)
            if args.mode in {"methods", "sampling"}:
                analysis = method_trace(folder / "methods.trace")
                (folder / "method-analysis.json").write_text(json.dumps(analysis, indent=2), encoding="utf-8")
            elif args.mode == "heap":
                analysis = class_diff(folder / "before.hprof", folder / "after.hprof")
                (folder / "class-inventory.json").write_text(json.dumps(analysis, indent=2), encoding="utf-8")
            if args.perfetto:
                # Wait for the writer to exit, not just duration_ms: final flushing can take longer.
                deadline = time.monotonic() + 60
                while True:
                    state = call("shell", "cat", f"/proc/{perfetto_pid}/stat", check=False)
                    # SELinux can deny kill -0 even for shell-owned perfetto; /proc is readable.
                    if state.returncode != 0 or b"(perfetto)" not in state.stdout or b") Z " in state.stdout:
                        break
                    if time.monotonic() > deadline:
                        raise RuntimeError("Perfetto did not finalize within 60 seconds")
                    time.sleep(0.5)
                call("pull", trace_path, folder / "system.pftrace")
                if (folder / "system.pftrace").stat().st_size < 1024:
                    raise RuntimeError("Perfetto produced an empty or unexpectedly small trace")
                result["perfetto_bytes"] = (folder / "system.pftrace").stat().st_size
                (folder / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
            result["artifact_directory"] = run_id
            results.append(result)
            print(f"  {result['status']}: frame commit {result.get('first_frame_commit_ms', 'n/a')} ms", flush=True)
            if result["status"] != "PASS":
                raise RuntimeError(f"Behavior check failed: {result}")
    finally:
        call("shell", "am", "force-stop", PACKAGE, check=False)
        (args.out / "results.json").write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    with (args.out / "summary.csv").open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=["case", "mode", "profiled", "count", "median_commit_ms", "min_commit_ms", "max_commit_ms", "median_pss_delta_kb", "median_loaded_class_delta"])
        writer.writeheader()
        for case in supported:
            group = [r for r in results if r["case"] == case]
            commits = [r["first_frame_commit_ms"] for r in group]
            writer.writerow({"case": case, "mode": args.mode, "profiled": args.mode != "timing" or args.perfetto, "count": len(group), "median_commit_ms": statistics.median(commits),
                "min_commit_ms": min(commits), "max_commit_ms": max(commits),
                "median_pss_delta_kb": statistics.median(r["after"]["pss_kb"] - r["before"]["pss_kb"] for r in group),
                "median_loaded_class_delta": statistics.median(r["after"]["loaded_class_count"] - r["before"]["loaded_class_count"] for r in group)})
    print(f"Artifacts: {args.out.resolve()}", flush=True)


if __name__ == "__main__":
    main()
