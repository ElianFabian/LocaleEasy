"""Correlate opt-in screenshots with app-layer presence in a Perfetto surface trace.

Requires Pillow and the official perfetto Python package, plus trace_processor_shell.
Dark-image classification is intended for this sample's light-theme captures only.
"""
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image
from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig


def analyze(folder, binary):
    frames = json.loads((folder / "frames.json").read_text())
    state = json.loads((folder / "state.json").read_text())
    trigger = state["trigger_ns"]
    end = max(frame["capture_end_ns"] for frame in frames)
    sampled = []
    for frame in frames:
        file = folder / frame["file"]
        with Image.open(file) as image:
            region = image.convert("RGB").crop((10, 40, 170, 370))
            pixels = list(region.get_flattened_data() if hasattr(region, "get_flattened_data") else region.getdata())
        fraction = sum(max(pixel) < 24 for pixel in pixels) / len(pixels)
        sampled.append({**frame, "dark_fraction": round(fraction, 4), "mostly_black": fraction > .9,
                        "sha256": hashlib.sha256(file.read_bytes()).hexdigest()})
    trace_file = folder / "window.pftrace"
    with TraceProcessor(trace=str(trace_file), config=TraceProcessorConfig(bin_path=str(binary.resolve()))) as tp:
        query = f"""select s.id,s.ts,
          sum(case when l.layer_name like 'com.elianfabian.localeeasy/%SampleActivity#%' then 1 else 0 end) as app_layers,
          group_concat(l.layer_name,' | ') as visible_layers
          from surfaceflinger_layers_snapshot s left join surfaceflinger_layer l
          on l.snapshot_id=s.id and l.is_visible=1
          where s.ts between {trigger-100_000_000} and {end}
          group by s.id order by s.ts"""
        snapshots = [dict(id=row.id, ts=row.ts, offset_ms=round((row.ts-trigger)/1e6, 3),
                          app_layers=row.app_layers, visible_layers=row.visible_layers or "") for row in tp.query(query)]
        events = [dict(ts=row.ts, offset_ms=round((row.ts-trigger)/1e6, 3), dur_ms=round(row.dur/1e6, 3), name=row.name)
                  for row in tp.query(f"""select ts,dur,name from slice where ts between {trigger} and {end}
                  and (name glob 'LocaleEasy.change.*' or name glob 'LocaleEasy.hybrid.*' or name glob 'perform*:*SampleActivity*') order by ts""")]
    if not snapshots:
        raise RuntimeError("No SurfaceFlinger snapshots: do not infer a surface gap from missing trace data")
    gaps = []
    start = None
    for snapshot in snapshots:
        if snapshot["ts"] < trigger:
            continue
        if snapshot["app_layers"] == 0:
            if start is None:
                start = snapshot["ts"]
        elif start is not None:
            gaps.append({"start_ns": start, "end_ns": snapshot["ts"],
                         "start_ms": round((start-trigger)/1e6, 3),
                         "end_ms": round((snapshot["ts"]-trigger)/1e6, 3),
                         "duration_ms": round((snapshot["ts"]-start)/1e6, 3)})
            start = None
    if start is not None:
        raise RuntimeError("The trace did not capture app surface recovery")
    result = {"state": state, "environment": json.loads((folder / "environment.json").read_text()),
              "trace_sha256": hashlib.sha256(trace_file.read_bytes()).hexdigest(),
              "sample_count": len(sampled), "black_sample_count": sum(f["mostly_black"] for f in sampled),
              "frames": sampled, "surface_gaps": gaps, "snapshots": snapshots, "events": events,
              "limits": "Screenshot requests bracket capture; snapshot timestamps are not physical display presentation. Tracing perturbs timing. Light theme only."}
    (folder / "analysis.json").write_text(json.dumps(result, indent=2))
    print(folder.name, "black samples:", result["black_sample_count"], "/", len(sampled), "surface gaps:", gaps)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folders", nargs="+", type=Path)
    parser.add_argument("--trace-processor", required=True, type=Path)
    args = parser.parse_args()
    for folder in args.folders:
        analyze(folder, args.trace_processor)
