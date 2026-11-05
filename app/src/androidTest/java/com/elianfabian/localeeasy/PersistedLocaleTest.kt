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
        if (phase == "seed") instrumentation.runOnMainSync { api.setLocale(Locale.FRENCH) }
        instrumentation.waitForIdleSync()
        assertEquals(LocalePreference.AppLocales(listOf(Locale.FRENCH)), api.preference.value)
        assertEquals(listOf(Locale.FRENCH), api.effectiveLocales.value)
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertEquals("Chaîne Localisée: Vert", it.findViewById<TextView>(R.id.locale_fixture_label).text.toString())
            }
        }
        Log.i("LocaleEasyFixture", "persistence phase=$phase process=${android.os.Process.myPid()}")
    }
}
