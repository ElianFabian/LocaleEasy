package com.elianfabian.localeeasy

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.DebugWebViewSubclass
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.setAppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToInt

class WebViewContentViewTest {
    @Test
    @SdkSuppress(minSdkVersion = 24)
    fun optedInActivityRepairsBeforeTheSiblingConstructor() = verifyContentView(merge = false)

    @Test
    @SdkSuppress(minSdkVersion = 24)
    fun mergeLayoutStillAttachesToTheActivityContent() = verifyContentView(merge = true)

    @Test
    @SdkSuppress(minSdkVersion = 24)
    fun fragmentLayoutIdRepairsBeforeTheSiblingConstructor() = verifyContentView(merge = false, fragment = true)

    private fun verifyContentView(merge: Boolean, fragment: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val api = LocaleEasy.getInstance()
        val previous = api.preference.value
        WebViewConstructorLabel.firstText.set(null)
        instrumentation.runOnMainSync { api.setAppLocale(Locale.FRENCH) }
        try {
            val intent = Intent(instrumentation.targetContext, WebViewContentFixtureActivity::class.java)
                .putExtra("merge", merge)
                .putExtra("fragment", fragment)
            ActivityScenario.launch<WebViewContentFixtureActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(!fragment, activity.usedResourceOverload)
                    assertTrue(activity.findViewById<View>(R.id.webview_content_fixture_view) is DebugWebViewSubclass)
                    val label = activity.findViewById<TextView>(R.id.webview_constructor_label)
                    assertEquals("Chaîne Localisée: Vert", label.text.toString())
                    assertEquals("Chaîne Localisée: Vert", WebViewConstructorLabel.firstText.get())
                    if (merge) {
                        val child = activity.findViewById<View>(R.id.webview_merge_child)
                        assertTrue(child is AppCompatTextView)
                        assertEquals(android.R.id.content, (child.parent as View).id)
                        assertEquals(android.R.id.content, (label.parent as View).id)
                    } else {
                        val root = activity.findViewById<View>(R.id.webview_content_fixture_root)
                        assertEquals((320 * root.resources.displayMetrics.density).roundToInt(), root.layoutParams.height)
                        if (fragment) assertEquals(android.R.id.custom, (root.parent as View).id)
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { api.setPreference(previous) }
        }
    }
}
