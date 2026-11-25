package com.elianfabian.localeeasy

import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Build
import android.widget.EditText
import android.widget.TextView
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.followSystem
import com.elianfabian.locale_easy.LocalePreference
import com.elianfabian.localeeasy.ui.legacy.SampleFragment
import com.elianfabian.localeeasy.ui.legacy.SampleActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocaleLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext
    private val api get() = LocaleEasy.getInstance()

    @Before fun reset() { main { api.followSystem() }; instrumentation.waitForIdleSync() }
    @After fun cleanup() { main { api.followSystem() }; instrumentation.waitForIdleSync() }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Locale/lifecycle condition did not become true within 15 seconds")
    }

    private fun translated(language: String): String {
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return app.createConfigurationContext(config).getString(R.string.string_resource_example)
    }

    @Test fun installingTwiceRetainsTheInstance() {
        val before = api
        main { LocaleEasy.install(app); LocaleEasy.install(app) }
        assertSame(before, api)
    }

    @Test fun runtimeCatalogMatchesTheGeneratedBuildConfig() {
        val catalog = api.localeCatalog
        assertEquals(BuildConfig.DEFAULT_LANGUAGE, catalog.defaultLocale.toLanguageTag())
        assertEquals(BuildConfig.SUPPORTED_LANGUAGES.toList(), catalog.supportedLocales.map { it.toLanguageTag() })
        assertEquals(LocalePreference.FollowSystem, api.preference.value)
        main { api.setAppLocale(Locale.GERMAN) }
        assertEquals(listOf(Locale.GERMAN), api.effectiveLocales.value)
        assertEquals(Locale.ENGLISH, catalog.defaultLocale)
    }

    @Test fun followSystemResolvesAnEmulatorLanguageThroughTheCatalog() {
        val expectedSystem = InstrumentationRegistry.getArguments().getString("catalogSystemLanguage")
        assumeTrue(expectedSystem == "de" || expectedSystem == "fr")
        val expected = if (expectedSystem == "fr") Locale.FRENCH else Locale.ENGLISH
        val snapshot = api.state.value
        assertEquals(expectedSystem, snapshot.systemLocales.single().language)
        assertEquals(LocalePreference.FollowSystem, snapshot.preference)
        assertEquals(expected.language, snapshot.effectiveLocales.single().language)
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertEquals(expected.language, it.resources.configuration.locales[0].language)
                assertEquals(translated(expected.language), it.findViewById<TextView>(R.id.locale_fixture_label).text)
            }
        }
    }

    @Test fun backgroundMutationFailsWithoutChangingSelection() {
        val before = api.preference.value
        assertTrue(runCatching { api.setAppLocale(Locale.FRENCH) }.exceptionOrNull() is IllegalStateException)
        assertEquals(before, api.preference.value)
    }

    @Test fun invalidInputDoesNotChangePreferenceOrStorage() {
        val prefs = app.getSharedPreferences("locale_easy_prefs", 0)
        val before = prefs.all
        main {
            assertTrue(runCatching { api.setAppLocales(emptyList()) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { api.setAppLocale(Locale("fr-CA")) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { api.setLanguageTag("fr_CA") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(LocalePreference.FollowSystem, api.preference.value)
        }
        assertEquals(before, prefs.all)
    }

    @Test fun duplicateLocalesAreNormalizedBeforeUpdatingResources() {
        main {
            api.setAppLocales(listOf(Locale.FRENCH, Locale.FRENCH, Locale.CANADA_FRENCH))
            assertEquals(listOf(Locale.FRENCH, Locale.CANADA_FRENCH), api.state.value.effectiveLocales)
            assertEquals("fr,fr-CA", app.getSharedPreferences("locale_easy_prefs", 0).getString("KEY_LANGUAGES", null))
            assertEquals(translated("fr"), api.localizedContext(app).getString(R.string.string_resource_example))
        }
    }

    @Test fun selectionFromImmediateCollectorAppliesAfterCurrentChange() {
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            val italian = LocalePreference.AppLocales(listOf(Locale.ITALIAN))
            var observedFrench = false
            main {
                val scope = CoroutineScope(Dispatchers.Main.immediate)
                try {
                    scope.launch {
                        api.preference.collect { preference ->
                            if (preference == LocalePreference.AppLocales(listOf(Locale.FRENCH))) {
                                assertEquals(listOf(Locale.FRENCH), api.effectiveLocales.value)
                                assertEquals(translated("fr"), app.getString(R.string.string_resource_example))
                                observedFrench = true
                                api.setLanguageTag("it")
                            }
                        }
                    }
                    api.setAppLocale(Locale.FRENCH)
                    assertTrue(observedFrench)
                    assertEquals(italian, api.state.value.preference)
                    assertEquals(italian.locales, api.state.value.effectiveLocales)
                    assertEquals(italian.locales, api.effectiveLocales.value)
                    assertEquals("it", app.getSharedPreferences("locale_easy_prefs", 0).getString("KEY_LANGUAGES", null))
                    assertEquals(translated("it"), app.getString(R.string.string_resource_example))
                    assertEquals(translated("it"), api.localizedContext(app).getString(R.string.string_resource_example))
                } finally {
                    scope.cancel()
                }
            }
            await {
                var ready = false
                scenario.onActivity { ready = it.findViewById<TextView>(R.id.locale_fixture_label).text == translated("it") }
                ready
            }
        }
    }

    @Test fun plainActivityRecreatesItsXmlViewAndRestoresUserInput() {
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            var initialId = 0
            var initialView: TextView? = null
            scenario.onActivity {
                initialId = it.instanceId
                initialView = it.findViewById(R.id.locale_fixture_label)
                it.findViewById<EditText>(R.id.locale_fixture_input).setText("keep this input")
                api.setAppLocale(Locale.FRENCH)
            }
            await {
                var ready = false
                scenario.onActivity {
                    ready = it.instanceId != initialId && it.findViewById<TextView>(R.id.locale_fixture_label).text == translated("fr")
                }
                ready
            }
            scenario.onActivity {
                assertNotSame(initialView, it.findViewById(R.id.locale_fixture_label))
                assertEquals("keep this input", it.findViewById<EditText>(R.id.locale_fixture_input).text.toString())
            }
            // Natural recreation must retain the selected language too.
            scenario.recreate()
            scenario.onActivity { assertEquals(translated("fr"), it.findViewById<TextView>(R.id.locale_fixture_label).text) }
        }
    }

    @Test fun repeatedSelectionDoesNotRecreateAgainAndRapidUpdatesSettleOnLatest() {
        main { api.setAppLocale(Locale.FRENCH) }
        instrumentation.waitForIdleSync()
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            var initialId = 0
            scenario.onActivity { initialId = it.instanceId; api.setAppLocale(Locale.FRENCH) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(initialId, it.instanceId) }
            scenario.onActivity { api.setAppLocale(Locale.GERMAN); api.setAppLocale(Locale.ITALIAN) }
            await {
                var ready = false
                scenario.onActivity { ready = it.findViewById<TextView>(R.id.locale_fixture_label).text == translated("it") }
                ready
            }
            assertEquals(LocalePreference.AppLocales(listOf(Locale.ITALIAN)), api.preference.value)
        }
    }

    @Test fun stoppedManualActivityRefreshesWhenStartedAgain() {
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            var initialId = 0
            scenario.onActivity { initialId = it.instanceId }
            scenario.moveToState(Lifecycle.State.CREATED)
            main { api.setAppLocale(Locale.FRENCH) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(initialId, it.instanceId) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            await {
                var ready = false
                scenario.onActivity { ready = it.instanceId != initialId && it.findViewById<TextView>(R.id.locale_fixture_label).text == translated("fr") }
                ready
            }
        }
    }

    @Test fun retainedLocalizedContextFollowsNightModeChanges() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val manager = app.getSystemService(UiModeManager::class.java)
        fun nightMode(context: android.content.Context) =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val original = nightMode(app)
        val target = if (original == Configuration.UI_MODE_NIGHT_YES) Configuration.UI_MODE_NIGHT_NO
            else Configuration.UI_MODE_NIGHT_YES
        main { api.setAppLocale(Locale.FRENCH) }
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            val wrapped = api.localizedContext(app)
            assertEquals(original, nightMode(wrapped))
            try {
                scenario.onActivity {
                    manager.setApplicationNightMode(if (target == Configuration.UI_MODE_NIGHT_YES)
                        UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
                }
                await {
                    var ready = false
                    scenario.onActivity {
                        ready = nightMode(it) == target && nightMode(app) == target && nightMode(wrapped) == target
                    }
                    ready
                }
                assertEquals(translated("fr"), wrapped.getString(R.string.string_resource_example))
            } finally {
                main { manager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO) }
            }
        }
    }

    @Test fun manualModeSwitchWithIdenticalLanguagesStillRebuildsTheContext() {
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            var initialId = 0
            scenario.onActivity { initialId = it.instanceId; api.followSystemLocale = false }
            await {
                var replaced = false
                scenario.onActivity { replaced = it.instanceId != initialId }
                replaced
            }
            assertTrue(api.preference.value is LocalePreference.AppLocales)
        }
    }

    @Test fun appCompatRuntimeTitlesAndStoppedBackStackKeepTheirLocale() {
        ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.startActivity(android.content.Intent(it, SampleActivity::class.java).putExtra("level", 2))
            }
            fun topActivity(): SampleActivity? {
                return androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<SampleActivity>().firstOrNull()
            }
            await {
                var ready = false
                main { ready = topActivity()?.intent?.getIntExtra("level", 0) == 2 }
                ready
            }
            main { api.setAppLocale(Locale.FRENCH) }
            await {
                var ready = false
                main { ready = topActivity()?.title?.toString() == "Activité 2" }
                ready
            }
            main { topActivity()?.finish() }
            await {
                var ready = false
                scenario.onActivity { ready = it.title.toString() == "Activité 1" && it.getString(R.string.string_resource_example) == translated("fr") }
                ready
            }
        }
    }

    @Test fun xmlToolbarKeepsSystemBarInsetsAcrossLocaleRecreation() {
        ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
            var original: SampleActivity? = null
            var beforeTop = 0
            var target = Locale.FRENCH
            fun toolbarTop(activity: SampleActivity): Int {
                val toolbar = activity.findViewById<View>(R.id.toolbar)
                val location = IntArray(2)
                toolbar.getLocationInWindow(location)
                val bars = requireNotNull(ViewCompat.getRootWindowInsets(toolbar))
                    .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                assertTrue("Toolbar overlaps system bars y=${location[1]} inset=${bars.top}", location[1] >= bars.top)
                return location[1]
            }
            await {
                var laidOut = false
                scenario.onActivity { laidOut = it.findViewById<View>(R.id.toolbar).isLaidOut }
                laidOut
            }
            scenario.onActivity {
                original = it
                beforeTop = toolbarTop(it)
                target = if (api.effectiveLocales.value.first().language == "fr") Locale.ITALIAN else Locale.FRENCH
                api.setAppLocale(target)
            }
            await {
                var ready = false
                scenario.onActivity { ready = it !== original && it.findViewById<View>(R.id.toolbar).isLaidOut }
                ready
            }
            scenario.onActivity {
                assertEquals("Toolbar moved after recreation", beforeTop, toolbarTop(it))
                assertEquals("XML content used a stale locale", translated(target.language),
                    it.findViewById<TextView>(R.id.textViewLocalized).text.toString())
                val expectedTitle = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(target) })
                    .getString(R.string.activity_title_1)
                assertEquals("Activity title used a stale locale", expectedTitle, it.title.toString())
                assertEquals("Toolbar title used a stale locale", expectedTitle,
                    it.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).title.toString())
            }
        }
    }

    @Test fun fragmentDialogSurvivesActivityLocaleRecreationExactlyOnce() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                SampleFragment().showNow(it.supportFragmentManager, "locale-test-dialog")
                api.setAppLocale(Locale.FRENCH)
            }
            await {
                var ready = false
                scenario.onActivity {
                    val fragment = it.supportFragmentManager.findFragmentByTag("locale-test-dialog")
					ready = fragment?.getString(R.string.string_resource_example) == translated("fr") && fragment?.view != null
                }
                ready
            }
            scenario.onActivity {
                assertEquals(1, it.supportFragmentManager.fragments.count { fragment -> fragment.tag == "locale-test-dialog" })
            }
        }
    }

    @Test fun webViewInitializationAndNativeRepairPreservePreference() {
        main { api.setAppLocale(Locale.FRENCH) }
        instrumentation.waitForIdleSync()
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            scenario.onActivity {
                val webView = android.webkit.WebView(it)
                webView.loadData("<html lang='fr'><body>Locale fixture</body></html>", "text/html", "UTF-8")
                LocaleEasy.fixWebViewLocale()
                assertEquals(LocalePreference.AppLocales(listOf(Locale.FRENCH)), api.preference.value)
                assertEquals(translated("fr"), it.getString(R.string.string_resource_example))
                webView.destroy()
            }
        }
    }

    @Test fun webViewRepairLeavesOutgoingActivityResourcesScopedAcrossSwitches() {
        main { api.setAppLocale(Locale.FRENCH) }
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            val french = "Chaîne Localisée: Vert"
            val spanish = "Cadena Localizada: Verde"
            var current = french
            repeat(3) {
                for ((locale, expected) in listOf(Locale.forLanguageTag("es") to spanish, Locale.FRENCH to french)) {
                    var outgoingId = 0
                    scenario.onActivity {
                        outgoingId = it.instanceId
                        api.setAppLocale(locale)
                        LocaleEasy.fixWebViewLocale()
                        assertEquals(current, it.getString(R.string.string_resource_example))
                    }
                    await {
                        var ready = false
                        scenario.onActivity {
                            ready = it.instanceId != outgoingId &&
                                it.findViewById<TextView>(R.id.locale_fixture_label).text == expected
                        }
                        ready
                    }
                    current = expected
                }
            }
        }
    }
}
