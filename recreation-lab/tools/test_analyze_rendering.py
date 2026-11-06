import unittest
from analyze_rendering import frame_rows, replacement_frame


def dump(window="WorkloadActivity", start=20_000_000, realtime=100):
    return f"""Uptime: 100 Realtime: {realtime}
Window: example/{window}
---PROFILEDATA---
Flags,IntendedVsync,FrameStartTime,PerformTraversalsStart,DrawStart,SyncQueued,FrameCompleted,FrameDeadline,DisplayPresentTime,
0,15000000,{start},22000000,30000000,34000000,40000000,31000000,0,
---PROFILEDATA---
"""


class RenderingTests(unittest.TestCase):
    def record(self):
        return {"final_view": {"activity_id": 2}, "events": [
            {"name": "onCreate", "instance": 2, "elapsed_ns": 10_000_000},
            {"name": "frame_commit", "instance": 2, "elapsed_ns": 45_000_000},
        ]}

    def test_frame_metrics_use_replacement_interval(self):
        result = replacement_frame(self.record(), dump())
        self.assertEqual(20, result["frame_work_ms"])
        self.assertEqual(5, result["intended_vsync_to_start_ms"])
        self.assertEqual(8, result["traversals_to_draw_ms"])
        self.assertEqual(9, result["deadline_overrun_ms"])
        self.assertFalse(result["display_present_time_available"])

    def test_harness_frames_are_ignored(self):
        self.assertEqual([], frame_rows(dump(window="HarnessActivity")))

    def test_uncalibrated_clock_is_rejected(self):
        with self.assertRaises(ValueError):
            replacement_frame(self.record(), dump(realtime=101))

    def test_boundary_ambiguity_is_rejected(self):
        with self.assertRaises(ValueError):
            replacement_frame(self.record(), dump(start=10_500_000))


if __name__ == "__main__":
    unittest.main()
