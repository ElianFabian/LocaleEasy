import struct
import tempfile
import unittest
from pathlib import Path
from analyze_artifacts import class_inventory, class_diff, method_trace

TEST_ROOT = Path(__file__).resolve().parents[2] / "build" / "lab-parser-tests"
TEST_ROOT.mkdir(parents=True, exist_ok=True)

def hprof(names):
    data = b"JAVA PROFILE 1.0.3\0" + struct.pack(">IQ", 4, 0)
    for index, name in enumerate(names, 1):
        value = struct.pack(">I", index) + name.encode()
        data += struct.pack(">BII", 1, 0, len(value)) + value
        value = struct.pack(">IIII", index, index + 100, 0, index)
        data += struct.pack(">BII", 2, 0, len(value)) + value
    return data


class ArtifactTests(unittest.TestCase):
    def analyze_events(self, events):
        text = b"*version\n3\nclock=dual\n*threads\n1\tmain\n*methods\n0x4\texample/Activity\touter\t()V\n0x8\texample/Activity\tchild\t()V\n*end\n"
        binary = b"SLOW" + struct.pack("<HHQH", 3, 18, 0, 14)
        for method, cpu, wall in events:
            binary += struct.pack("<HIII", 1, method, cpu, wall)
        with tempfile.TemporaryDirectory(dir=TEST_ROOT) as directory:
            path = Path(directory) / "timing.trace"
            path.write_bytes(text + binary)
            return method_trace(path)

    def test_nested_call_self_time_excludes_child_time(self):
        result = self.analyze_events([(4, 0, 0), (8, 20, 40), (9, 50, 100), (5, 100, 200)])
        rows = {row["method"]: row for row in result["main_thread_timings"]}
        outer = rows["example.Activity.outer()V"]
        self.assertEqual(100, outer["inclusive_cpu_us"])
        self.assertEqual(70, outer["exclusive_cpu_us"])
        self.assertEqual(200, outer["inclusive_wall_us"])
        self.assertEqual(140, outer["exclusive_wall_us"])
        self.assertEqual(2, result["timing_coverage"]["completed_calls"])

    def test_open_child_does_not_inflate_parent_self_time(self):
        result = self.analyze_events([(4, 0, 0), (8, 20, 40), (5, 100, 200)])
        row = result["main_thread_timings"][0]
        self.assertEqual(100, row["inclusive_cpu_us"])
        self.assertNotIn("exclusive_cpu_us", row)
        self.assertEqual(1, row["incomplete_children_calls"])
        self.assertEqual(1, result["timing_coverage"]["open_or_discarded_frames"])

    def test_clock_wraparound(self):
        result = self.analyze_events([(4, 0xfffffff0, 0xfffffff0), (5, 16, 32)])
        self.assertEqual(32, result["main_thread_timings"][0]["inclusive_cpu_us"])
        self.assertEqual(48, result["main_thread_timings"][0]["inclusive_wall_us"])

    def test_class_names_and_added_removed_sets(self):
        with tempfile.TemporaryDirectory(dir=TEST_ROOT) as directory:
            before, after = Path(directory) / "before", Path(directory) / "after"
            before.write_bytes(hprof(["android/app/Activity", "example/Old"]))
            after.write_bytes(hprof(["android/app/Activity", "example/New"]))
            result = class_diff(before, after)
            self.assertEqual(["example.New"], result["added"])
            self.assertEqual(["example.Old"], result["removed"])

    def test_truncated_heap_is_rejected(self):
        with tempfile.TemporaryDirectory(dir=TEST_ROOT) as directory:
            path = Path(directory) / "invalid"
            path.write_bytes(hprof(["example/Thing"])[:-1])
            with self.assertRaises(ValueError):
                class_inventory(path)

    def test_method_entries_and_main_thread_call_path(self):
        text = b"*version\n3\nclock=dual\ndata-file-overflow=false\n*threads\n1\tmain\n*methods\n0x4\tandroid/app/Activity\trecreate\t()V\n0x8\texample/Activity\tonCreate\t()V\n*end\n"
        binary = b"SLOW" + struct.pack("<HHQH", 3, 18, 0, 14)
        for method in [4, 8, 9, 5]:
            binary += struct.pack("<HIII", 1, method, 1, 1)
        with tempfile.TemporaryDirectory(dir=TEST_ROOT) as directory:
            path = Path(directory) / "methods.trace"
            path.write_bytes(text + binary)
            result = method_trace(path)
            self.assertEqual(4, result["record_count"])
            self.assertEqual(1, result["framework_evidence"]["android.app.Activity.recreate()V"])
            self.assertTrue(any(len(item["path"]) == 2 for item in result["main_thread_paths"]))


if __name__ == "__main__":
    unittest.main()
