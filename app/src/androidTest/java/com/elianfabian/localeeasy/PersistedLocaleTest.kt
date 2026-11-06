package com.elianfabian.localeeasy

import android.util.Log
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.LocalePreference
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Run seed and verify in separate instrumentation processes, with force-stop in between. */
@RunWith(AndroidJUnit4::class)
class PersistedLocaleTest {
    @Test fun preferenceSurvivesProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("localePhase")
        assumeTrue(phase == "seed" || phase == "verify")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val api = LocaleEasy.getInstance()
        val backend = InstrumentationRegistry.getArguments().getString("localeBackend")
        val framework = when (backend) { "manual" -> false; "framework", "framework_only", "framework_stale" -> true; else -> null }
        if (phase == "seed") instrumentation.runOnMainSync {
            if (framework != null) api.useFrameworkLocaleRecreation = framework
            api.setLocale(Locale.FRENCH)
            if (framework == true) {
                // Reproduce the obsolete persisted switch written by older experiments.
                instrumentation.targetContext.getSharedPreferences("locale_easy_prefs", 0).edit()
                    .putBoolean("KEY_FRAMEWORK_RECREATION", true).commit()
            }
        }
        instrumentation.waitForIdleSync()
        // A framework seed represents an older experiment/install. The next process
        // must keep its saved language but return to the normal local-recreation path.
        val expectedFramework = phase == "seed" && framework == true
        assertEquals(expectedFramework, api.useFrameworkLocaleRecreation)
        if (phase == "verify") org.junit.Assert.assertFalse(
            instrumentation.targetContext.getSharedPreferences("locale_easy_prefs", 0).contains("KEY_FRAMEWORK_RECREATION"))
        if (android.os.Build.VERSION.SDK_INT >= 33 && !expectedFramework) {
            org.junit.Assert.assertTrue(instrumentation.targetContext.getSystemService(android.app.LocaleManager::class.java).applicationLocales.isEmpty)
        }
        assertEquals(LocalePreference.AppLocales(listOf(Locale.FRENCH)), api.preference.value)
        assertEquals(listOf(Locale.FRENCH), api.effectiveLocales.value)
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertEquals("Chaîne Localisée: Vert", it.findViewById<TextView>(R.id.locale_fixture_label).text.toString())
            }
        }
        if (phase == "seed" && backend == "framework_only") {
            // Simulate an existing OS selection with no LocaleEasy storage yet.
            instrumentation.waitForIdleSync()
            instrumentation.targetContext.getSharedPreferences("locale_easy_prefs", 0).edit().clear().commit()
        }
        if (phase == "seed" && backend == "framework_stale") {
            // Older library storage can lag a framework selection made outside the process.
            // Keep French in LocaleManager, but leave Spanish in the old library snapshot.
            instrumentation.targetContext.getSharedPreferences("locale_easy_prefs", 0).edit()
                .putBoolean("KEY_FOLLOW_SYSTEM_LOCALE", false).putString("KEY_LANGUAGES", "es").commit()
        }
        Log.i("LocaleEasyFixture", "persistence phase=$phase process=${android.os.Process.myPid()}")
    }
}
