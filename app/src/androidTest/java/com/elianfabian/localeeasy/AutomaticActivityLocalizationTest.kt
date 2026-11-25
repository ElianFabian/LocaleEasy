package com.elianfabian.localeeasy

import android.app.Activity
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.setAppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AutomaticActivityLocalizationTest {
    @Test
    fun instrumentsPlainActivity() = verifyActivity(AutomaticPlainLocaleActivity::class.java)

    @Test
    fun preservesInheritedFinalAttachBaseContext() =
        verifyActivity(AutomaticAppCompatLocaleActivity::class.java)

    private fun <T : Activity> verifyActivity(activityClass: Class<T>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val api = LocaleEasy.getInstance()
        val originalPreference = api.preference.value
        try {
            instrumentation.runOnMainSync { api.setAppLocale(Locale.FRENCH) }
            ActivityScenario.launch(activityClass).use { scenario ->
                lateinit var firstActivity: T
                lateinit var firstView: TextView
                scenario.onActivity { activity ->
                    firstActivity = activity
                    firstView = activity.findViewById(R.id.locale_fixture_label)
                    assertEquals("Chaîne Localisée: Vert", fixtureLabel(activity))
                    if (activity is AutomaticAppCompatLocaleActivity) assertEquals(1, activity.attachCalls)
                    api.setAppLocale(Locale.forLanguageTag("es"))
                }
                val deadline = System.nanoTime() + 10_000_000_000L
                do {
                    instrumentation.waitForIdleSync()
                    var recreated = false
                    scenario.onActivity { activity ->
                        if (activity !== firstActivity) {
                            assertNotSame(firstView, activity.findViewById<TextView>(R.id.locale_fixture_label))
                            assertEquals("Cadena Localizada: Verde", fixtureLabel(activity))
                            if (activity is AutomaticAppCompatLocaleActivity) assertEquals(1, activity.attachCalls)
                            recreated = true
                        }
                    }
                    if (recreated) return
                    Thread.sleep(50)
                } while (System.nanoTime() < deadline)
                throw AssertionError("LocaleEasy did not recreate ${activityClass.simpleName}")
            }
        } finally {
            instrumentation.runOnMainSync { api.setPreference(originalPreference) }
        }
    }

    private fun fixtureLabel(activity: Activity): String =
        activity.findViewById<TextView>(R.id.locale_fixture_label).text.toString()
}
