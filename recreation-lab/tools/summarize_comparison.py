"""Summarize separate timing, memory, and method passes without mixing their timings."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics

CASES = ("manual_locale", "framework_locale")


def distribution(values):
    values = sorted(values)
    if not values:
        return None
    # Linear interpolation at (n - 1) * .95; explicitly not a confidence interval.
    index = (len(values) - 1) * .95
    lower = int(index)
    upper = min(lower + 1, len(values) - 1)
    return {"n": len(values), "min": values[0], "median": statistics.median(values),
            "p95": values[lower] + (values[upper] - values[lower]) * (index - lower), "max": values[-1]}


def lifecycle_phases(record):
    instance = record["final_view"]["activity_id"]
    def stamp(name, identity=None):
        return next(event["elapsed_ns"] for event in record["events"]
                    if event["name"] == name and (identity is None or event["instance"] == identity))
    trigger, create, start, commit = stamp("trigger"), stamp("onCreate", instance), stamp("onStart", instance), stamp("frame_commit", instance)
    if not trigger <= create <= start <= commit:
        raise ValueError("Lifecycle timestamps are out of order")
    return {"request_to_onCreate_ms": (create - trigger) / 1e6,
            "onCreate_to_onStart_ms": (start - create) / 1e6,
            "onStart_to_frame_commit_ms": (commit - start) / 1e6}


def gc_delta(record):
    key = "art.gc.gc-count"
    before, after = record["before"]["art"].get(key), record["after"]["art"].get(key)
    return int(after) - int(before) if before is not None and after is not None else None


def summarize(timing_dir, memory_dir, methods_dir=None):
    timing_dir, memory_dir = Path(timing_dir), Path(memory_dir)
    timing = json.loads((timing_dir / "results.json").read_text(encoding="utf-8"))
    memory = json.loads((memory_dir / "results.json").read_text(encoding="utf-8"))
    environment = json.loads((timing_dir / "environment.json").read_text())
    memory_environment = json.loads((memory_dir / "environment.json").read_text())
    for key in ("installed_apk_sha256", "fingerprint", "display_size", "display_density", "rows"):
        if environment[key] != memory_environment[key]:
            raise ValueError(f"Timing/memory environment mismatch: {key}")
    if not timing or not memory:
        raise ValueError("Both passes need successful trials")
    for mode, records in (("timing", timing), ("memory", memory)):
        if any(record["status"] != "PASS" or record["mode"] != mode or record.get("external_perfetto") for record in records):
            raise ValueError(f"Invalid or mixed {mode} pass")
        if any(record["timing_is_profiled"] != (mode != "timing") for record in records):
            raise ValueError("Unexpected profiling flag")
    initial_languages = {record["initial_view"]["view_locales"] for record in timing + memory}
    target_languages = {record["target_locale"] for record in timing + memory}
    if len(initial_languages) != 1 or len(target_languages) != 1:
        raise ValueError("Locale stimulus differs between trials/passes")
    result = {"schema_version": 1, "environment": environment, "cases": {},
              "initial_view_locales": next(iter(initial_languages)), "target_locale": next(iter(target_languages)),
              "source_results_sha256": {"timing": hashlib.sha256((timing_dir / "results.json").read_bytes()).hexdigest(),
                                        "memory": hashlib.sha256((memory_dir / "results.json").read_bytes()).hexdigest()},
              "limits": ["Emulator debug-build observations; not a physical-device performance guarantee",
                         "Frame commit is not display presentation or a blank-frame detector",
                         "Memory deltas/observed sample maxima are not retained sizes or leak evidence",
                         "Phase medians do not sum to the overall median"]}
    for case in CASES:
        group = [record for record in timing if record["case"] == case]
        mem = [record for record in memory if record["case"] == case]
        if not group or not mem:
            raise ValueError(f"Missing case: {case}")
        phases = [lifecycle_phases(record) for record in group]
        observed = []
        for record in mem:
            trigger = next(event["elapsed_ns"] for event in record["events"] if event["name"] == "trigger")
            commit = next(event["elapsed_ns"] for event in record["events"] if event["name"] == "frame_commit" and event["elapsed_ns"] > trigger)
            samples = [sample for sample in record["memory_samples"] if trigger <= sample["elapsed_ns"] <= commit]
            observed.append({"samples_during_transition": len(samples),
                             "java_peak_delta_bytes": max([record["before"]["java_used_bytes"]] + [sample["java_used_bytes"] for sample in samples]) - record["before"]["java_used_bytes"],
                             "native_peak_delta_bytes": max([record["before"]["native_allocated_bytes"]] + [sample["native_allocated_bytes"] for sample in samples]) - record["before"]["native_allocated_bytes"]})
        deltas = [gc_delta(record) for record in group + mem]
        result["cases"][case] = {
            "frame_commit_ms": distribution([record["first_frame_commit_ms"] for record in group]),
            "phases_ms": {key: distribution([phase[key] for phase in phases]) for key in phases[0]},
            "timing_pss_delta_kb": distribution([record["after"]["pss_kb"] - record["before"]["pss_kb"] for record in group]),
            "timing_loaded_class_delta": distribution([record["after"]["loaded_class_count"] - record["before"]["loaded_class_count"] for record in group]),
            "decor_preserved_trials": sum(record["initial_view"]["decor_id"] == record["final_view"]["decor_id"] for record in group),
            "gc_counter_increments": sum(delta for delta in deltas if delta is not None),
            "gc_counter_trials_available": sum(delta is not None for delta in deltas),
            "memory_profile": {"n": len(mem),
                               "pss_delta_kb": distribution([record["after"]["pss_kb"] - record["before"]["pss_kb"] for record in mem]),
                               "sampled_java_peak_delta_bytes": distribution([sample["java_peak_delta_bytes"] for sample in observed]),
                               "sampled_native_peak_delta_bytes": distribution([sample["native_peak_delta_bytes"] for sample in observed]),
                               "samples_during_transition": [sample["samples_during_transition"] for sample in observed]},
        }
    result["observed_median_difference_ms"] = (result["cases"]["framework_locale"]["frame_commit_ms"]["median"] -
                                                result["cases"]["manual_locale"]["frame_commit_ms"]["median"])
    if methods_dir:
        root = Path(methods_dir)
        methods_environment = json.loads((root / "environment.json").read_text())
        for key in ("installed_apk_sha256", "fingerprint", "display_size", "display_density"):
            if methods_environment[key] != environment[key]:
                raise ValueError(f"Method environment mismatch: {key}")
        if methods_environment["mode"] not in {"methods", "sampling"}:
            raise ValueError("Not a method capture pass")
        result["method_pass"] = {"mode": methods_environment["mode"], "rows": methods_environment["rows"], "runs": []}
        for path in sorted(root.glob("*/method-analysis.json")):
            analysis = json.loads(path.read_text())
            record = json.loads(path.with_name("result.json").read_text())
            if record["status"] != "PASS" or record["mode"] != methods_environment["mode"]:
                raise ValueError("Invalid method capture")
            selected = [row for row in analysis["main_thread_timings"]
                        if any(key in row["method"] for key in ("WorkloadActivity.onCreate(", "WorkloadActivity.installWorkload(", "ActivityThread.performLaunchActivity(", "ActivityThread.handleRelaunchActivityInner("))]
            result["method_pass"]["runs"].append({"case": record["case"], "buffer_overflow": analysis["buffer_overflow"],
                                                   "coverage": analysis["timing_coverage"], "selected_timings_us": selected,
                                                   "framework_paths": [item for item in analysis["framework_paths"]
                                                                       if any(key in item["method"] for key in ("ActivityThread.performLaunchActivity(", "ActivityThread.handleRelaunchActivityLocally("))],
                                                   "framework_evidence": analysis["framework_evidence"],
                                                   "classes_with_observed_entries": len(analysis["classes_with_observed_entries"])})
        if not result["method_pass"]["runs"]:
            raise ValueError("No analyzed method captures found")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timing-dir", type=Path, required=True)
    parser.add_argument("--memory-dir", type=Path, required=True)
    parser.add_argument("--methods-dir", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.write_text(json.dumps(summarize(args.timing_dir, args.memory_dir, args.methods_dir), indent=2), encoding="utf-8")
    print(args.out)


if __name__ == "__main__":
    main()
