package com.elianfabian.locale_easy

import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LegacyLocaleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val api get() = LocaleEasy.getInstance()
    @Before fun reset() { instrumentation.runOnMainSync { api.followSystem() } }
    @After fun cleanup() { instrumentation.runOnMainSync { api.followSystem() } }

    @Test fun startupIsIdempotentAndContextResourcesAreLocalized() {
        val before = api
        instrumentation.runOnMainSync {
            LocaleEasy.install(instrumentation.targetContext)
            assertSame(before, api)
            api.setLocale(Locale.FRENCH)
            assertEquals("French fixture", api.localizedContext(instrumentation.targetContext).getString(R.string.locale_easy_fixture_label))
        }
    }

    @Test fun plainActivityRecreationAppliesFrench() {
        ActivityScenario.launch(LegacyLocaleFixtureActivity::class.java).use { scenario ->
            var original: LegacyLocaleFixtureActivity? = null
            scenario.onActivity { original = it; api.setLocale(Locale.FRENCH) }
            val deadline = System.nanoTime() + 15_000_000_000L
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { ready = it !== original && it.findViewById<TextView>(android.R.id.text1).text == "French fixture" }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Replacement activity must have French resources", ready)
        }
    }
}
