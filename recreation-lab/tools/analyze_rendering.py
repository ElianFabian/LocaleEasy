"""Analyze replacement-frame timing in saved gfxinfo dumps; no device or screen access."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re
from summarize_comparison import CASES, distribution


def frame_rows(text):
    """Read per-window CSV sections, excluding harness/other-window statistics."""
    markers = list(re.finditer(r"---PROFILEDATA---", text))
    if len(markers) % 2:
        raise ValueError("Unclosed gfxinfo profile section")
    frames = []
    for opening, closing in zip(markers[::2], markers[1::2]):
        windows = re.findall(r"^Window:\s*(.+)$", text[:opening.start()], re.MULTILINE)
        if not windows or "WorkloadActivity" not in windows[-1]:
            continue
        lines = [line.strip().rstrip(",") for line in text[opening.end():closing.start()].splitlines() if line.strip()]
        for values in csv.DictReader(lines):
            if None in values or any(value is None for value in values.values()):
                raise ValueError("Malformed gfxinfo CSV row")
            frames.append({name: int(value) for name, value in values.items()})
    return frames


def replacement_frame(record, text):
    clock = re.search(r"Uptime:\s*(\d+)\s+Realtime:\s*(\d+)", text)
    if not clock or clock[1] != clock[2]:
        raise ValueError("Uptime/Realtime headers differ or are absent; clock alignment unavailable")
    # Lab events use elapsed realtime; gfxinfo uses monotonic timestamps. Equal
    # millisecond headers bound the offset; exclude the 1ms boundary band.
    identity = record["final_view"]["activity_id"]
    start = next(event["elapsed_ns"] for event in record["events"] if event["name"] == "onCreate" and event["instance"] == identity)
    end = next(event["elapsed_ns"] for event in record["events"] if event["name"] == "frame_commit" and event["instance"] == identity)
    eligible = [row for row in frame_rows(text)
                if start + 1_000_000 <= row["FrameStartTime"] <= end - 1_000_000
                and row["FrameCompleted"] >= row["FrameStartTime"] > 0
                and row["IntendedVsync"] > 0 and row["FrameDeadline"] > 0]
    if not eligible:
        raise ValueError("No completed replacement-window frame safely inside the lifecycle interval")
    row = min(eligible, key=lambda value: value["FrameStartTime"])
    ordered = [row[name] for name in ("FrameStartTime", "PerformTraversalsStart", "DrawStart", "SyncQueued", "FrameCompleted")]
    if ordered != sorted(ordered) or row["FrameStartTime"] < row["IntendedVsync"]:
        raise ValueError("Frame stage timestamps are out of order")
    return {"frame_work_ms": (row["FrameCompleted"] - row["FrameStartTime"]) / 1e6,
            "intended_vsync_to_start_ms": (row["FrameStartTime"] - row["IntendedVsync"]) / 1e6,
            "traversals_to_draw_ms": (row["DrawStart"] - row["PerformTraversalsStart"]) / 1e6,
            "draw_to_sync_queue_ms": (row["SyncQueued"] - row["DrawStart"]) / 1e6,
            "sync_queue_to_complete_ms": (row["FrameCompleted"] - row["SyncQueued"]) / 1e6,
            "deadline_overrun_ms": max(0, (row["FrameCompleted"] - row["FrameDeadline"]) / 1e6),
            "display_present_time_available": row.get("DisplayPresentTime", 0) > 0,
            "first_draw_frame": bool(row["Flags"] & 1),
            "frame_flags": row["Flags"], "raw_frame": row}


def analyze(directory):
    root = Path(directory)
    records = json.loads((root / "results.json").read_text(encoding="utf-8"))
    result = {"schema_version": 1, "environment": json.loads((root / "environment.json").read_text()),
              "source_results_sha256": hashlib.sha256((root / "results.json").read_bytes()).hexdigest(),
              "clock_boundary_margin_ns": 1_000_000, "cases": {}, "excluded_trials": [],
              "scope": "First recorded completed workload-window frame starting after replacement onCreate and before frame commit; first-draw frames included",
              "limits": ["Not an Android JankStats rate; window-first-draw flags differ across the two paths",
                         "No actual display-presentation or blank-frame claim",
                         "Render completion timing includes scheduling/queueing; not pure GPU duration"]}
    metrics = ("frame_work_ms", "intended_vsync_to_start_ms", "traversals_to_draw_ms", "draw_to_sync_queue_ms", "sync_queue_to_complete_ms", "deadline_overrun_ms")
    for case in CASES:
        selected = []
        trials = [record for record in records if record["case"] == case]
        if not trials:
            raise ValueError(f"No trials for {case}")
        for record in trials:
            if record["status"] != "PASS" or record["timing_is_profiled"]:
                raise ValueError("Expected successful unprofiled timing trials")
            folder = root / record["artifact_directory"]
            try:
                text = (folder / "gfxinfo.txt").read_text(encoding="utf-8")
                frame = replacement_frame(record, text)
            except (ValueError, KeyError, StopIteration, OSError) as error:
                result["excluded_trials"].append({"case": case, "artifact_directory": folder.name, "reason": str(error)})
                continue
            frame["artifact_directory"] = folder.name
            frame["source_gfxinfo_sha256"] = hashlib.sha256((folder / "gfxinfo.txt").read_bytes()).hexdigest()
            selected.append(frame)
        result["cases"][case] = {"total_trials": len(trials), "matched_frames": len(selected),
                                 "deadline_exceeded_frames": sum(frame["deadline_overrun_ms"] > 0 for frame in selected),
                                 "first_draw_frames": sum(frame["first_draw_frame"] for frame in selected),
                                 "display_present_timestamps": sum(frame["display_present_time_available"] for frame in selected),
                                 "metrics": {key: distribution([frame[key] for frame in selected]) for key in metrics},
                                 "frames": selected}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.write_text(json.dumps(analyze(args.input_dir), indent=2), encoding="utf-8")
    print(args.out)


if __name__ == "__main__":
    main()
