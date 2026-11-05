package com.elianfabian.recreationlab

import android.content.res.Configuration
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.view.View
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

internal object LabSession {
    val cases = setOf("recreate", "rotation", "manual_locale", "framework_locale", "localeeasy", "view_only")
    val modes = setOf("timing", "memory", "sampling", "methods", "heap")
    lateinit var case: String
    lateinit var mode: String
    lateinit var directory: File
    var rows = 200
    var targetLocale = Locale.FRENCH
    var overrideLocale = false
    var triggered = false
    @Volatile private var finished = false
    private var tracing = false
    private var triggerNs = 0L
    private var commitNs = 0L
    private var drawNs = 0L
    private var initial = JSONObject()
    private var before = JSONObject()
    private val events = JSONArray()
    private val memorySamples = ConcurrentLinkedQueue<JSONObject>()
    private var memoryThread: HandlerThread? = null
    private var memoryHandler: Handler? = null
    private val handler = Handler(Looper.getMainLooper())
    private var nextInstance = 0
    fun instance(): Int = ++nextInstance

    fun event(name: String, instance: Int = 0, details: String = "") {
        events.put(JSONObject().put("name", name).put("instance", instance)
            .put("elapsed_ns", SystemClock.elapsedRealtimeNanos()).put("thread", Thread.currentThread().name).put("details", details))
    }

    fun describe(activity: WorkloadActivity, root: View): JSONObject = JSONObject()
        .put("activity_id", activity.instanceId).put("root_id", System.identityHashCode(root))
        .put("decor_id", System.identityHashCode(activity.window.decorView))
        .put("orientation", activity.resources.configuration.orientation)
        .put("activity_locales", activity.resources.configuration.locales.toLanguageTags())
        .put("view_locales", root.resources.configuration.locales.toLanguageTags())
        .put("text", root.context.getString(R.string.lab_title))
        .put("input", root.findViewById<EditText>(R.id.lab_input).text.toString())

    @Suppress("DEPRECATION")
    fun memory(): JSONObject {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        return JSONObject().put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
            .put("pss_kb", info.totalPss).put("private_dirty_kb", info.totalPrivateDirty)
            .put("java_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            .put("java_committed_bytes", runtime.totalMemory())
            .put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize())
            .put("loaded_class_count", Debug.getLoadedClassCount())
            .put("art", JSONObject(Debug.getRuntimeStats()))
    }

    fun prepare(activity: WorkloadActivity, root: View) {
        if (triggered || finished) return
        triggered = true
        initial = describe(activity, root)
        if (mode == "heap") Debug.dumpHprofData(File(directory, "before.hprof").absolutePath)
        before = memory()
        if (mode == "memory") {
            memoryThread = HandlerThread("LabMemorySampler").apply { start() }
            memoryHandler = Handler(memoryThread!!.looper)
        }
        if (mode == "sampling") {
            Debug.startMethodTracingSampling(File(directory, "methods.trace").absolutePath, 32 * 1024 * 1024, 1000)
            tracing = true
        } else if (mode == "methods") {
            Debug.startMethodTracing(File(directory, "methods.trace").absolutePath, 32 * 1024 * 1024, 0)
            tracing = true
        }
        Trace.beginAsyncSection("Lab.transition.$case", 1)
        triggerNs = SystemClock.elapsedRealtimeNanos()
        event("trigger", activity.instanceId, case)
        if (mode == "memory") memoryHandler!!.post { sampleMemory() }
        handler.postDelayed({ if (!finished) fail("No replacement frame committed within 30 seconds") }, 30_000)
        activity.performCase()
    }

    private fun sampleMemory() {
        if (finished) return
        val runtime = Runtime.getRuntime()
        memorySamples.add(JSONObject().put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
            .put("java_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            .put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize()))
        memoryHandler?.postDelayed({ sampleMemory() }, 25)
    }

    fun drawn(instance: Int) {
        event("draw_callback", instance)
        if (triggerNs > 0 && drawNs == 0L) drawNs = SystemClock.elapsedRealtimeNanos()
    }

    fun committed(activity: WorkloadActivity, root: View) {
        event("frame_commit", activity.instanceId)
        if (triggerNs == 0L || finished || commitNs != 0L) return
        commitNs = SystemClock.elapsedRealtimeNanos()
        Trace.endAsyncSection("Lab.transition.$case", 1)
        if (tracing) { Debug.stopMethodTracing(); tracing = false }
        // Capture after a fixed settling interval; this is not a retained-heap/leak assertion.
        handler.postDelayed({ complete(activity, root) }, 200)
    }

    private fun complete(activity: WorkloadActivity, root: View) {
        if (finished) return
        finished = true
        stopMemorySampler()
        val after = memory()
        val final = describe(activity, root)
        if (mode == "heap") Debug.dumpHprofData(File(directory, "after.hprof").absolutePath)
        val errors = JSONArray()
        val shouldRecreate = case != "view_only"
        if ((initial.getInt("activity_id") != final.getInt("activity_id")) != shouldRecreate) errors.put("Unexpected activity identity")
        if (initial.getInt("root_id") == final.getInt("root_id")) errors.put("Content root was not replaced")
        if (final.getString("input") != "typed fixture input") errors.put("User input was not preserved")
        if (case == "rotation" && final.getInt("orientation") != Configuration.ORIENTATION_LANDSCAPE) errors.put("Rotation did not reach landscape")
        if (case in setOf("manual_locale", "framework_locale", "localeeasy", "view_only") &&
            !final.getString("view_locales").startsWith(targetLocale.toLanguageTag())) errors.put("Target view locale not applied")
        write(JSONObject().put("status", if (errors.length() == 0) "PASS" else "FAIL").put("errors", errors)
            .put("before", before).put("after", after).put("initial_view", initial).put("final_view", final)
            .put("first_draw_callback_ms", (drawNs - triggerNs) / 1_000_000.0)
            .put("first_frame_commit_ms", (commitNs - triggerNs) / 1_000_000.0))
    }

    fun fail(message: String) {
        finished = true
        stopMemorySampler()
        if (tracing) { Debug.stopMethodTracing(); tracing = false }
        if (triggerNs > 0 && commitNs == 0L) Trace.endAsyncSection("Lab.transition.$case", 1)
        write(JSONObject().put("status", "FAIL").put("error", message))
    }

    private fun write(result: JSONObject) {
        result.put("schema_version", 1).put("case", case).put("mode", mode).put("rows", rows).put("pid", Process.myPid())
            .put("api", Build.VERSION.SDK_INT).put("device", Build.MODEL).put("fingerprint", Build.FINGERPRINT)
            .put("target_locale", targetLocale.toLanguageTag()).put("events", events).put("memory_samples", JSONArray(memorySamples.toList()))
            .put("timing_is_profiled", mode != "timing")
        val pending = File(directory, "result.tmp")
        pending.writeText(result.toString(2))
        check(pending.renameTo(File(directory, "result.json")))
    }

    private fun stopMemorySampler() {
        memoryHandler?.removeCallbacksAndMessages(null)
        memoryThread?.quitSafely()
    }
}

internal inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection("Lab.$name")
    return try { block() } finally { Trace.endSection() }
}
