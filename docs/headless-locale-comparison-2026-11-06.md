# Headless locale comparison — 2026-11-06

**Result:** all 40 unprofiled timing trials and 10 separate memory trials passed. In this isolated emulator workload, manual locale-context recreation reached frame commit sooner than framework locale recreation. This does not reproduce the reported case of manual recreation feeling slower, and it does not determine whether either path displayed a blank frame.

No physical device was connected. Testing used one temporary, headless API-36 emulator (Medium_Phone_API_36.0) with read-only/no-snapshot AVD state, the same debug APK, a 200-row hierarchy, English → French, and randomized seed 42. No screenshots, pixel inspection, or screen reading were used. The temporary session's low-storage reserve was lowered to permit APK installation; saved AVD state was not overwritten.

The [machine-readable comparison](../recreation-lab/results/locale-comparison-2026-11-06.json) contains APK hash, fingerprint, display configuration, settings, raw-result hashes, distributions, phase summaries, memory observations, and selected method evidence. The recorder and comparison commands are documented in the [lab README](../recreation-lab/README.md).

## Timing: 20 trials per path

These are trigger-to-frame-commit callback times. Frame commit indicates submission to the swap chain, not physical presentation or smoothness.

| Metric | Manual context + recreate | Framework locale change |
| --- | ---: | ---: |
| Trials passed | 20 / 20 | 20 / 20 |
| Median | 134.5 ms | 216.4 ms |
| P95, linearly interpolated | 228.1 ms | 502.7 ms |
| Minimum | 72.8 ms | 105.3 ms |
| Maximum | 334.7 ms | 503.8 ms |
| Decor identity preserved | 20 / 20 | 0 / 20 |
| Activity and content root replaced | 20 / 20 | 20 / 20 |

The difference between observed medians was **81.9 ms** in favor of the manual path. This is a descriptive result for this APK/device/workload, not a confidence interval, a physical-device guarantee, or evidence that one integration is universally preferable. The framework operation also updates app-wide configuration and persistent app-language preferences; the manual case only changes the activity context.

The first two randomized jobs were framework trials, both around 503 ms. All trials were retained; no warm-up exclusion was applied. The tail is therefore particularly sensitive to initial device/host warm-up and the small sample count. Each trial used a fresh app process, while device caches and optimization state could persist between trials.

## Where the wall time appeared

Lifecycle events provide a headless decomposition using the replacement activity's identity:

| Median wall-clock interval | Manual | Framework |
| --- | ---: | ---: |
| Request → new `onCreate` entry | 15.6 ms | 52.8 ms |
| `onCreate` entry → `onStart` completion | 38.7 ms | 52.7 ms |
| `onStart` completion → frame-commit callback | 76.0 ms | 98.0 ms |

The extra time was not confined to content construction. It appeared before the new activity's `onCreate` as well as after `onStart`. These intervals include scheduling and framework work, so they are not pure CPU-stage measurements. Their medians do not add to the overall median because each statistic summarizes a different distribution.

The decor identity difference is consistent with the earlier source/trace investigation: a local relaunch and a system locale relaunch can coordinate windows differently even while sharing activity construction code. It is a lead for investigating frame presentation, not proof that decor replacement caused the measured latency or that decor preservation prevents flashing.

## Replacement-frame rendering metrics

A follow-up headless analysis used the already saved `gfxinfo ... framestats` dumps, with no additional device runs or screenshots. It selected the first completed workload-window frame whose recorded start fell after the replacement activity's `onCreate` and before its frame-commit callback. Initial harness/workload startup frames were excluded.

The lab event and graphics clocks were aligned only when the dump's millisecond Uptime/Realtime headers agreed, with a 1 ms exclusion margin at each selection boundary. That produced 20 manual and 19 framework frames. One framework trial was excluded because its clock headers differed; no alignment was guessed. The [rendering result](../recreation-lab/results/rendering-comparison-2026-11-06.json) records selected raw rows, source hashes, exclusions, and distributions.

| Median recorded frame interval | Manual, n=20 | Framework, n=19 |
| --- | ---: | ---: |
| Actual frame start → frame complete | 71.2 ms | 89.6 ms |
| Intended vsync → actual frame start | 54.2 ms | 97.1 ms |
| Traversals start → draw start | 34.0 ms | 53.6 ms |
| Draw start → render sync queued | 16.3 ms | 15.5 ms |
| Render sync queued → frame complete | 17.5 ms | 19.2 ms |
| Completion beyond recorded frame deadline | 109.2 ms | 152.5 ms |

The larger differences appeared in start lateness and the traversal interval, while draw-command recording was similar. The traversal interval includes framework measure/layout work; the sync-to-completion interval includes scheduling, queueing, and rendering. These are timestamp intervals, not pure CPU or GPU utilization measurements. Their separate medians should not be added.

Every matched replacement frame exceeded its recorded deadline. This is **not** an Android JankStats rate or a comparable steady-animation jank percentage: manual frames had flags 0, while framework frames had flags 1 for their new window's first draw. The whole-process headline `Janky frames` statistics also mix harness/startup and replacement work and were deliberately not used for this comparison.

This flag difference is a concrete lead for the perceived-smoothness problem. API-36 `FrameMetrics` maps flag bit 1 to `FIRST_DRAW_FRAME`; Android documents these frames as expected to be slow, generally exempt from animation-jank calculations, and often masked by window animations. Window/surface presentation could therefore differ even when manual recreation has lower measured latency. That is an inference to test, not proof of the original flash. See [FrameMetrics.FIRST_DRAW_FRAME](https://developer.android.com/reference/android/view/FrameMetrics#FIRST_DRAW_FRAME).

`DisplayPresentTime` was zero for every selected row, so these dumps do not establish actual presentation time, blank-frame count, or pixel content. That is the remaining distinction between objective headless rendering metrics and proof of a visible flash. The measured results do establish that both paths contain slow replacement frames in this emulator workload; they do not reproduce the manual path being slower.

The rendering parser has tests for timestamp calculations, excluding harness data, rejecting unaligned clocks, and rejecting boundary-ambiguous frames. The complete Python suite now has 13 passing tests. The analysis follows Android's [rendering instrumentation guidance](https://developer.android.com/topic/performance/rendering/inspect-gpu-rendering), while retaining the narrower raw-interval definitions above rather than treating every number as a GPU measurement.

## Memory: five separate profiled trials per path

The memory sampler runs on a background thread. It changes runtime behavior, so its timings are excluded from the primary timing comparison. Snapshot PSS was collected before the request and roughly 200 ms after commit; Java/native maxima below are only the maxima of observed samples during the transition.

| Median memory observation | Manual | Framework |
| --- | ---: | ---: |
| Before/after PSS delta | +4,498 KB | +4,453 KB |
| Observed Java-used peak delta | +961,952 bytes | +1,161,232 bytes |
| Observed native-allocated peak delta | +792,736 bytes | +1,954,784 bytes |
| Samples during transition, per trial | 3 | 4–5 |

These are not true peak PSS, retained sizes, or leak evidence. The sampler has overhead and can miss short peaks. The longer framework interval also gives it more sampling opportunities. No forced GC was requested; old views and other garbage may remain allocated at the final snapshot.

Across all 50 timing/memory trials, the available ART Java GC counters showed **no increment**. Java GC therefore does not appear to explain the captured timing difference. This says nothing about GC behavior in a larger real application.

The runtime-reported loaded-class counter had zero net change in every timing trial. That does not establish that no class loading/unloading occurred between snapshots. For class-name membership, the earlier separate heap inventories remain available; exact load chronology requires additional runtime instrumentation.

## Method evidence: two fresh traces per path

Four instrumented captures passed with no buffer overflow, using the same APK and a smaller 20-row workload to bound profiler overhead. Their timings are diagnostic and are not merged with the 200-row latency distributions.

Both manual captures entered:

```text
Activity.recreate
  → ActivityThread.handleRelaunchActivityLocally
  → transaction execution
  → ActivityRelaunchItem.execute
  → ActivityThread.handleRelaunchActivity
  → ActivityThread.handleRelaunchActivityInner
  → ActivityThread.handleLaunchActivity
  → ActivityThread.performLaunchActivity
```

Both framework captures showed:

```text
LocaleManager.setApplicationLocales
  → framework configuration / incoming relaunch transaction
  → ActivityRelaunchItem.execute
  → ActivityThread.handleRelaunchActivity
  → ActivityThread.handleRelaunchActivityInner
  → ActivityThread.handleLaunchActivity
  → ActivityThread.performLaunchActivity
```

The arrow between the LocaleManager request and incoming transaction spans asynchronous work; it is not a single continuous Java stack or a full system-server call graph.

The analyzer now extracts inclusive CPU/wall and self timings for matching method entry/exit records, reports coverage, and excludes self timings with incomplete child intervals. Tests cover nested calls, partial stacks, clock wraparound, percentile calculation, and event ordering; with the rendering parser, the suite has 13 passing tests.

The manual captures contained balanced `onCreate`/content-construction records; under instrumentation, content construction accounted for most of their measured `onCreate` interval. The framework captures did not provide matched records for these stages: one had no recorded `onCreate` entry, and the other had an entry without its exit, despite successful lifecycle/view checks. Their durations are intentionally absent. An overflow-free file does not imply exhaustive method coverage or balanced pairs. This prevents a reliable method-time comparison of those stages; the unprofiled lifecycle intervals above remain the stronger cross-case evidence. Missing durations are not treated as zero work, and the cause of the recorder gap is unconfirmed.

## What this establishes

- The two approaches can be exercised and behavior-checked without looking at the screen.
- Both recreated the activity/root and preserved the fixture's input and target locale in every timing trial.
- The manual path was faster to frame commit in this experiment; the originally reported ordering was not reproduced.
- Java GC and a net increase in the class counter were not observed in the captured intervals.
- The paths enter the framework differently, then share the main relaunch/activity-creation routines. Window identity behavior differs.

## What remains

The next discriminating test is the same headless run on the affected physical device, followed by presentation/window tracing if its timing pattern differs. To establish a visible flash, separate frame-content or human visual evidence is still necessary. A frame-commit timestamp alone cannot establish the absence of blank displayed frames.

No change to the production recreation strategy is justified solely by this run. It provides no evidence that manually changing private configuration flags would reduce the user's visible disruption, and it does not rule out an optional lightweight UI refresh API.

## Reproduce

From the repository root, with an installed debug lab APK and an explicit disposable device serial:

```powershell
python recreation-lab/tools/run_lab.py --serial DEVICE --cases manual_locale framework_locale --iterations 20 --seed 42 --out build/recreation-lab/comparison-timing
python recreation-lab/tools/run_lab.py --serial DEVICE --cases manual_locale framework_locale --mode memory --iterations 5 --seed 42 --out build/recreation-lab/comparison-memory
python recreation-lab/tools/run_lab.py --serial DEVICE --cases manual_locale framework_locale --mode methods --rows 20 --iterations 2 --seed 42 --out build/recreation-lab/comparison-methods
python recreation-lab/tools/summarize_comparison.py --timing-dir build/recreation-lab/comparison-timing --memory-dir build/recreation-lab/comparison-memory --methods-dir build/recreation-lab/comparison-methods --out build/recreation-lab/comparison.json
```

Use `--adb PATH` if adb is not on PATH. Raw files for this run remain under `build/recreation-lab/2026-11-06-api36-locale-{timing,memory,methods}` and are not committed. The compact comparison is committed for review. No raw file was discarded as an outlier.
