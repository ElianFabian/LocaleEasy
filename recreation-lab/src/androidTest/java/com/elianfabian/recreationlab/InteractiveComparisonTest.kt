package com.elianfabian.recreationlab

import android.app.LocaleManager
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InteractiveComparisonTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = "com.elianfabian.recreationlab"

    @Before fun openControls() {
        app.startActivity(Intent(app, ComparisonActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res(pkg, "ui_manual")), 10_000))
    }

    private fun scrollTo(id: String) {
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().resourceId("$pkg:id/$id"))
    }

    private fun choose(id: String, value: String) {
        scrollTo(id)
        device.findObject(By.res(pkg, id)).click()
        device.wait(Until.findObject(By.text(value)), 3_000).click()
    }

    private fun systemLanguage(): String = if (Build.VERSION.SDK_INT >= 33) app.getSystemService(LocaleManager::class.java).systemLocales[0].language
        else android.content.res.Resources.getSystem().configuration.locales[0].language

    private fun trial(button: String): JSONObject {
        scrollTo(button)
        device.findObject(By.res(pkg, button)).click()
        val apply = device.wait(Until.findObject(By.res(pkg, "ui_apply").enabled(true)), 10_000)
        assertNotNull("Interactive workload should wait for the user's Apply action", apply)
        device.findObject(By.res(pkg, "lab_input")).text = "keep my edited input"
        apply.click()
        val completed = device.wait(Until.findObject(By.res(pkg, "ui_trial_result").textStartsWith("PASS")), 15_000)
        assertNotNull("Interactive result should be visible after recreation", completed)
        assertEquals("keep my edited input", device.findObject(By.res(pkg, "lab_input")).text)
        val run = app.getSharedPreferences("lab_ui", 0).getString("pending", null)
        assertNotNull(run)
        val result = JSONObject(File(app.filesDir, "runs/$run/result.json").readText())
        assertTrue(result.getBoolean("interactive"))
        assertEquals("PASS", result.getString("status"))
        device.findObject(By.res(pkg, "ui_back")).click()
        assertTrue(device.wait(Until.hasObject(By.res(pkg, "ui_manual")), 5_000))
        return result
    }

    @Test fun bothLocalePathsCanBeRepeatedWithFreshTrialProcesses() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        choose("ui_language", if (systemLanguage() == "fr") "English" else "French")
        choose("ui_rows", "50 text rows")
        val first = trial("ui_manual")
        val second = trial("ui_framework")
        val third = trial("ui_manual")
        assertNotEquals(first.getInt("pid"), second.getInt("pid"))
        assertNotEquals(second.getInt("pid"), third.getInt("pid"))
        assertEquals("manual_locale", first.getString("case"))
        assertEquals("framework_locale", second.getString("case"))
        assertTrue(app.getSharedPreferences("lab_ui", 0).contains("last_manual_locale"))
        assertTrue(app.getSharedPreferences("lab_ui", 0).contains("last_framework_locale"))
    }

    @Test fun legacyUiDisablesFrameworkAndOffersNaturalRotation() {
        assumeTrue(Build.VERSION.SDK_INT < 33)
        choose("ui_language", if (systemLanguage() == "fr") "English" else "French")
        choose("ui_rows", "50 text rows")
        scrollTo("ui_framework")
        assertFalse(device.findObject(By.res(pkg, "ui_framework")).isEnabled)
        assertEquals("manual_locale", trial("ui_manual").getString("case"))
        val rotated = trial("ui_rotation")
        assertEquals(2, rotated.getJSONObject("final_view").getInt("orientation"))
    }

    @Test fun sameSystemLanguageCannotStartANoOpLocaleTrial() {
        val system = systemLanguage()
        assumeTrue(system == "en" || system == "fr")
        choose("ui_language", if (system == "fr") "French" else "English")
        scrollTo("ui_manual")
        assertFalse(device.findObject(By.res(pkg, "ui_manual")).isEnabled)
        scrollTo("ui_framework")
        assertFalse(device.findObject(By.res(pkg, "ui_framework")).isEnabled)
        scrollTo("ui_rotation")
        assertTrue(device.findObject(By.res(pkg, "ui_rotation")).isEnabled)
    }
}
