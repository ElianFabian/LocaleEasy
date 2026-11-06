import unittest
from summarize_comparison import distribution, lifecycle_phases


class ComparisonTests(unittest.TestCase):
    def test_median_and_linear_interpolated_p95(self):
        result = distribution([30, 0, 20, 10])
        self.assertEqual(15, result["median"])
        self.assertAlmostEqual(28.5, result["p95"])
        self.assertEqual(4, result["n"])
        self.assertIsNone(distribution([]))

    def record(self):
        return {"final_view": {"activity_id": 2}, "events": [
            {"name": "onCreate", "instance": 1, "elapsed_ns": 1},
            {"name": "trigger", "instance": 1, "elapsed_ns": 1_000_000},
            {"name": "onCreate", "instance": 2, "elapsed_ns": 3_000_000},
            {"name": "onStart", "instance": 2, "elapsed_ns": 6_000_000},
            {"name": "frame_commit", "instance": 2, "elapsed_ns": 10_000_000},
        ]}

    def test_phases_use_replacement_activity(self):
        self.assertEqual({"request_to_onCreate_ms": 2, "onCreate_to_onStart_ms": 3,
                          "onStart_to_frame_commit_ms": 4}, lifecycle_phases(self.record()))

    def test_invalid_event_order_is_rejected(self):
        record = self.record()
        record["events"][-1]["elapsed_ns"] = 2_000_000
        with self.assertRaises(ValueError):
            lifecycle_phases(record)


if __name__ == "__main__":
    unittest.main()
