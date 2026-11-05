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
    framework_paths = {}
    needles = ["ActivityThread.handleRelaunch", "ActivityThread.performLaunchActivity", "Activity.recreate",
               "ActivityThread.handleConfigurationChanged", "ActivityRelaunchItem", "LocaleManager.setApplicationLocales"]
    record_count = 0
    while position < len(raw):
        thread = raw[position] if version == 1 else struct.unpack_from("<H", raw, position)[0]
        value = struct.unpack_from("<I", raw, position + (1 if version == 1 else 2))[0]
        action, method = value & 3, value & ~3
        name = methods.get(method, f"unknown:{method:x}")
        stack = stacks[thread]
        if action == 0:
            stack.append(name)
            entries[name] += 1
            if any(key in name for key in needles):
                framework_paths.setdefault((thread, name), list(stack))
            if threads.get(thread) == "main":
                paths[tuple(stack[-12:])] += 1
        elif action in (1, 2):
            # A capture can begin/end inside a call; preserve only matching observed frames.
            if name in stack:
                del stack[len(stack) - 1 - stack[::-1].index(name):]
        else:
            raise ValueError("Unsupported ART method action")
        record_count += 1
        position += record_size
    return {"meaning": "Observed method entry transitions and reconstructed main-thread paths; sampling is not an exact call count",
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
