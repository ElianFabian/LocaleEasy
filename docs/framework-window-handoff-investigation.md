# Framework locale window handoff investigation

2026-11-06. **The black flash has a demonstrated surface gap on API 36. A debug-only hybrid avoids that gap in three captured runs while retaining real framework app locales.** Its lifecycle/state behavior also passes on API 33. This report records the earlier comparison. The subsequent [current policy](recreation-comparison.md) makes local recreation the default on all APIs and removes the framework selector; the hybrid remains a debug-only prototype.

## What causes the captured black output

SurfaceFlinger snapshots and timestamped screenshots now cover the same locale change. In the default framework case:

1. The old `SampleActivity` buffer layer is visible.
2. That layer disappears before the replacement activity supplies a visible layer. Only the system-bar layers remain visible.
3. The new activity layer becomes visible, with the translated content.

Black screenshots occur inside the interval with no visible app layer. This establishes an app-surface handoff gap, rather than a failure to construct or translate the new view hierarchy. An explicit light window background cannot cover this interval because its owning app window is absent. Disabling all three animation scales did not remove the gap either; the previous scales were restored afterward.

The framework source explains why the paths differ: [ActivityRecord.ensureActivityConfiguration](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/wm/ActivityRecord.java#8608) preserves the window for certain resize-only changes; locale changes fall outside that condition. [ActivityThread's local relaunch](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/app/ActivityThread.java#6791) supports preserving an existing foreground window. This describes the examined Android implementation, not an API guarantee for every device.

## Captured evidence

| API-36 case | Black screenshots / sampled screenshots | Snapshot interval without an app layer | Decor |
| --- | --- | --- | --- |
| Default framework, first run | 2 / 12 | 174.086 ms | Replaced |
| Default framework, repeat | 2 / 12 | 219.244 ms | Replaced |
| Default framework, animation scales off | 2 / 16 | 133.154 ms | Replaced |
| Existing manual path, control | 0 / 13 | None observed | Preserved |
| Correctly configured hybrid, first run | 0 / 13 | None observed | Preserved |
| Correctly configured hybrid, repeat | 0 / 14 | None observed | Preserved |
| Correctly configured hybrid, final run | 0 / 15 | None observed | Preserved |

These are diagnostic snapshot intervals, **not precise physical blackout durations or performance comparisons**. Tracing, screenshot sampling, host load, and scheduling perturb the operation. The successful cases are supported by continuous visible app-layer snapshots and decor identity checks as well as screenshot sampling; absence of a black screenshot alone would be weaker evidence.

The API-33 hybrid screenshot check had 0 black images out of 15, a preserved decor, a replaced activity/root, and French text. No SurfaceFlinger trace was collected on API 33. Compact evidence, trace SHA-256 values, settings restoration, state metadata, and selected surface/lifecycle events are in [the investigation results](results/framework-window-handoff-2026-11-06.json). Full local artifacts remain in ignored `build/qa/flash-trace-*/` directories.

## The supported-API hybrid

The debug [LocalRelaunchSampleActivity](../app/src/debug/java/com/elianfabian/localeeasy/LocalRelaunchSampleActivity.kt) uses the normal sample XML and ordinary activity creation:

- `LocaleManager` still stores and applies the app-language preference. The framework backend remains enabled and `applicationLocales` contains `fr` in the successful capture.
- The activity declares `locale|layoutDirection|screenLayout` as handled configuration categories.
- It receives `onConfigurationChanged()`, calls `super`, and requests `recreate()` when its applied locale list or screen-layout bits change.
- The local relaunch creates a new activity and XML root using the framework's updated resources, while preserving the decor and visible app surface.

This avoids requiring a view-factory interface, an overlay, or hidden APIs. It changes who requests activity recreation: framework preference delivery is retained, but the activity owns the subsequent relaunch. It is not a claim that the stock automatic framework relaunch has been repaired.

The `screenLayout` declaration matters on the tested configuration. Android's event log recorded change mask `0x2104`: locale (`0x4`), screen layout (`0x100`), and layout direction (`0x2000`). Declaring only locale/layout direction still allowed automatic window replacement and a black flash. Adding grammatical gender did not handle the missing screen-layout bit. Those unsuccessful controls are retained in the evidence file. The final fixture handles both locale-list and screen-layout changes rather than swallowing screen-layout callbacks.

Tests change French → externally selected Italian → Spanish → follow-system, checking activity/root replacement, decor preservation, saved-state registry restoration, framework preference ownership, flow reconciliation, and toolbar clearance below the system bars. Full instrumentation suites passed on API 33 and 36: 21 discovered, 15 passed, 6 intentional skips each. The strengthened external-selection/reset/toolbar check subsequently passed on both APIs. Final sample lint: 0 errors, 20 warnings.

## Scope before library integration

The prototype is registered only in the debug manifest and is exercised by tests; it does not add another selector or change the normal sample's framework behavior. Promoting it to LocaleEasy would require an explicit activity integration contract and manifest declarations. The library cannot silently make all consumer activities handle configuration changes.

Further acceptance checks include hybrid Compose navigation, fragments/dialogs, background/stopped activities, rapid changes, multi-window and resize-only events, RTL, theme changes, and physical/OEM devices. The successful foreground XML experiments demonstrate a viable workaround for the observed flash, not universal flash-free behavior.

## Reproduce

Build and install both debug APKs. On an explicitly selected disposable API-35+ device exposing `android.surfaceflinger.layers`:

```powershell
python app/tools/capture_window_transition.py --serial DEVICE --out build/qa/my-framework-capture
python app/tools/capture_window_transition.py --serial DEVICE --local-relaunch --out build/qa/my-hybrid-capture
python app/tools/capture_window_transition.py --serial DEVICE --animations off --out build/qa/my-animation-control
```

Each output directory must be new. Use `--adb PATH` if needed. The scripts modify only the sample's locale preference; the optional animation control temporarily changes device-wide scales and restores their original values. Use a disposable installation/device. The tracing session is limited to 18 seconds, and its generated remote file is removed after successful retrieval. Available data sources, device/build metadata, APK hash, raw trace, screenshots, and logs are preserved locally.

Analysis requires Pillow, the official `perfetto` Python package, and a compatible `trace_processor_shell` binary:

```powershell
python app/tools/analyze_window_trace.py --trace-processor PATH_TO_TRACE_PROCESSOR build/qa/my-framework-capture build/qa/my-hybrid-capture
```

The analyzer is intended for this sample's light-theme captures. It rejects missing surface snapshots rather than treating missing data as continuity. Capture times are bracketed by screenshot request start/end timestamps on the elapsed-realtime clock; surface snapshots are not physical presentation timestamps. Test teardown is excluded from the analyzed interval.

On API 33/34, use the screenshot diagnostic without the integrated surface-tracing runner:

```powershell
adb -s DEVICE shell am instrument -w -e class com.elianfabian.localeeasy.RecreationWindowCaptureTest -e captureWindows true -e capturePath framework -e localRelaunch true com.elianfabian.localeeasy.test/androidx.test.runner.AndroidJUnitRunner
```

The tracing configuration follows the [official Winscope/Perfetto capture documentation](https://source.android.com/docs/core/graphics/winscope/capture/adb). No undocumented system API is used by the prototype activity.
