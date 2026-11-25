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

class WebViewContentViewTest {
    @Test
    @SdkSuppress(minSdkVersion = 24)
    fun optedInActivityRepairsBeforeTheSiblingConstructor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val api = LocaleEasy.getInstance()
        val previous = api.preference.value
        WebViewConstructorLabel.firstText.set(null)
        instrumentation.runOnMainSync { api.setAppLocale(Locale.FRENCH) }
        try {
            ActivityScenario.launch(WebViewContentFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<View>(R.id.webview_content_fixture_view) is DebugWebViewSubclass)
                    val label = activity.findViewById<TextView>(R.id.webview_constructor_label)
                    assertEquals("Chaîne Localisée: Vert", label.text.toString())
                    assertEquals("Chaîne Localisée: Vert", WebViewConstructorLabel.firstText.get())
                }
            }
        } finally {
            instrumentation.runOnMainSync { api.setPreference(previous) }
        }
    }

    @Test
    fun mergeLayoutStillAttachesToTheActivityContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, WebViewContentFixtureActivity::class.java).putExtra("merge", true)
        ActivityScenario.launch<WebViewContentFixtureActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val child = activity.findViewById<View>(R.id.webview_merge_child)
                assertTrue(child is AppCompatTextView)
                assertEquals(android.R.id.content, (child.parent as View).id)
            }
        }
    }
}
