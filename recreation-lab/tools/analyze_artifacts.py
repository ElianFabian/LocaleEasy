"""Inspect HPROF class metadata or ART method traces without third-party dependencies."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import struct


def class_inventory(path):
    """Read STRING/LOAD_CLASS records, including Android HPROF 1.0.3. No retained-size claim."""
    strings, class_names = {}, []
    size = Path(path).stat().st_size
    with Path(path).open("rb") as stream:
        header = bytearray()
        while len(header) < 128:
            char = stream.read(1)
            if char == b"\0":
                break
            if not char:
                raise ValueError("Truncated HPROF header")
            header.extend(char)
        if not header.startswith(b"JAVA PROFILE 1.0."):
            raise ValueError("Unsupported HPROF header")
        raw = stream.read(12)
        if len(raw) != 12:
            raise ValueError("Truncated HPROF header fields")
        id_size = struct.unpack_from(">I", raw)[0]
        if id_size not in (4, 8):
            raise ValueError("Unsupported HPROF identifier size")
        while stream.tell() < size:
            raw = stream.read(9)
            if len(raw) != 9:
                raise ValueError("Truncated HPROF record header")
            tag, _, length = struct.unpack(">BII", raw)
            if length > size - stream.tell():
                raise ValueError("Truncated HPROF record body")
            if tag == 1:
                if length < id_size:
                    raise ValueError("Invalid HPROF string")
                identifier = int.from_bytes(stream.read(id_size), "big")
                strings[identifier] = stream.read(length - id_size).decode("utf-8", errors="replace")
            elif tag == 2:
                if length != 8 + 2 * id_size:
                    raise ValueError("Invalid HPROF LOAD_CLASS record")
                raw = stream.read(length)
                class_names.append(int.from_bytes(raw[8 + id_size:8 + 2 * id_size], "big"))
            else:
                stream.seek(length, 1)
    missing = set(class_names) - strings.keys()
    if missing:
        raise ValueError(f"Unresolved HPROF class-name identifiers: {len(missing)}")
    return sorted({strings[identifier].replace("/", ".") for identifier in class_names})


def class_diff(before, after):
    first, last = set(class_inventory(before)), set(class_inventory(after))
    return {"meaning": "Class names in HPROF LOAD_CLASS metadata; not chronological load events or retained sizes",
            "before_count": len(first), "after_count": len(last),
            "added": sorted(last - first), "removed": sorted(first - last),
            "before_classes": sorted(first), "after_classes": sorted(last)}


def method_trace(path):
    raw = Path(path).read_bytes()
    boundary = raw.find(b"*end\n")
    if boundary < 0:
        raise ValueError("Not a supported non-streaming ART trace")
    text = raw[:boundary].decode("utf-8", errors="replace")
    methods, threads, metadata = {}, {}, {}
    section = ""
    for line in text.splitlines():
        if line.startswith("*"):
            section = line
        elif section == "*methods":
            fields = line.split("\t")
            if len(fields) >= 4:
                methods[int(fields[0], 16)] = fields[1].replace("/", ".") + "." + fields[2] + fields[3]
        elif section == "*threads":
            fields = line.split("\t", 1)
            if len(fields) == 2:
                threads[int(fields[0])] = fields[1]
        elif "=" in line:
            key, value = line.split("=", 1)
            metadata[key] = value
    start = boundary + len(b"*end\n")
    if raw[start:start + 4] != b"SLOW":
        raise ValueError("Unsupported ART trace binary magic")
    version, offset = struct.unpack_from("<HH", raw, start + 4)
    if version not in (1, 2, 3):
        raise ValueError(f"Unsupported ART trace version {version}")
    record_size = struct.unpack_from("<H", raw, start + 16)[0] if version == 3 else 9 if version == 1 else 10
    minimum = 9 if version == 1 else 10
    if record_size < minimum:
        raise ValueError("Invalid ART record size")
    position = start + offset
    if (len(raw) - position) % record_size:
        raise ValueError("Truncated ART records")
    stacks, entries, paths = defaultdict(list), Counter(), Counter()
    timing_stacks = defaultdict(list)
    timings = defaultdict(Counter)
    discarded_frames = unmatched_exits = 0
    clock = metadata.get("clock", "unknown")
    if clock == "dual" and record_size < (13 if version == 1 else 14):
        raise ValueError("Dual-clock trace record is too small")
    framework_paths = {}
    needles = ["ActivityThread.handleRelaunch", "ActivityThread.performLaunchActivity", "Activity.recreate",
               "ActivityThread.handleConfigurationChanged", "ActivityRelaunchItem", "LocaleManager.setApplicationLocales"]
    record_count = 0
    while position < len(raw):
        thread = raw[position] if version == 1 else struct.unpack_from("<H", raw, position)[0]
        value = struct.unpack_from("<I", raw, position + (1 if version == 1 else 2))[0]
        action, method = value & 3, value & ~3
        clock_position = position + (5 if version == 1 else 6)
        first_clock = struct.unpack_from("<I", raw, clock_position)[0]
        cpu = first_clock if clock in {"dual", "thread-cpu"} else None
        wall = struct.unpack_from("<I", raw, clock_position + 4)[0] if clock == "dual" else first_clock if clock == "wall" else None
        name = methods.get(method, f"unknown:{method:x}")
        stack = stacks[thread]
        timing_stack = timing_stacks[thread]
        if action == 0:
            stack.append(name)
            timing_stack.append({"name": name, "cpu": cpu, "wall": wall, "child_cpu": 0, "child_wall": 0, "incomplete": False})
            entries[name] += 1
            if any(key in name for key in needles):
                framework_paths.setdefault((thread, name), list(stack))
            if threads.get(thread) == "main":
                paths[tuple(stack[-12:])] += 1
        elif action in (1, 2):
            # A capture can begin/end inside a call; preserve only matching observed frames.
            if name in stack:
                index = len(stack) - 1 - stack[::-1].index(name)
                frame = timing_stack[index]
                if index != len(stack) - 1:
                    discarded_frames += len(stack) - index - 1
                    frame["incomplete"] = True
                if clock != "unknown":
                    row = timings[(thread, name)]
                    row["completed_calls"] += 1
                    # ART v1-v3 clocks are microseconds in uint32 fields; allow wraparound.
                    for label, current in (("cpu", cpu), ("wall", wall)):
                        if current is not None:
                            elapsed = (current - frame[label]) & 0xffffffff
                            row[f"inclusive_{label}_us"] += elapsed
                            if not frame["incomplete"]:
                                row[f"exclusive_{label}_us"] += max(0, elapsed - frame[f"child_{label}"])
                            if index:
                                timing_stack[index - 1][f"child_{label}"] += elapsed
                    row["incomplete_children_calls" if frame["incomplete"] else "complete_children_calls"] += 1
                else:
                    discarded_frames += 1
                    if index:
                        timing_stack[index - 1]["incomplete"] = True
                del stack[index:]
                del timing_stack[index:]
            else:
                unmatched_exits += 1
        else:
            raise ValueError("Unsupported ART method action")
        record_count += 1
        position += record_size
    timing_rows = [{"thread": threads.get(thread, str(thread)), "method": name, **row}
                   for (thread, name), row in timings.items()]
    main_timings = sorted((row for row in timing_rows if row["thread"] == "main"),
                          key=lambda row: row.get("exclusive_cpu_us", row.get("exclusive_wall_us", 0)), reverse=True)
    return {"meaning": "Observed method entry transitions and reconstructed main-thread paths; sampling is not an exact call count",
            "timing_clock": clock, "timings_include_profiler_overhead": True,
            "timing_coverage": {"completed_calls": sum(row["completed_calls"] for row in timing_rows),
                                "incomplete_self_time_calls": sum(row.get("incomplete_children_calls", 0) for row in timing_rows),
                                "open_or_discarded_frames": discarded_frames + sum(map(len, timing_stacks.values())),
                                "unmatched_exit_events": unmatched_exits},
            "main_thread_timings": main_timings,
            "buffer_overflow": metadata.get("data-file-overflow") == "true",
            "version": version, "metadata": metadata, "record_count": record_count, "threads": threads,
            "framework_evidence": {name: count for name, count in entries.items() if any(key in name for key in needles)},
            "framework_paths": [{"thread": threads.get(thread, str(thread)), "method": method, "path": path}
                                for (thread, method), path in framework_paths.items()],
            "classes_with_observed_entries": sorted({name.split("(", 1)[0].rsplit(".", 1)[0] for name in entries}),
            "top_method_entries": entries.most_common(100),
            "main_thread_paths": [{"path": list(path), "entries": count} for path, count in paths.most_common(100)],
            "recorded_methods": sorted(methods.values())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path)
    parser.add_argument("--after", type=Path)
    parser.add_argument("--trace", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.trace:
        result = method_trace(args.trace)
    elif args.before and args.after:
        result = class_diff(args.before, args.after)
    else:
        parser.error("Provide --trace or both --before and --after")
    args.out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(args.out)


if __name__ == "__main__":
    main()
