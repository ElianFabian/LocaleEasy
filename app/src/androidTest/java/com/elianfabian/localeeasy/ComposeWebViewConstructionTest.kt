package com.elianfabian.localeeasy

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.setAppLocale
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ComposeWebViewConstructionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun readsResourcesBetweenConstructionAndLoad() = verifyConstruction(repairEarly = false)

    @Test fun immediateRepairProtectsEarlyApplicationContextReads() = verifyConstruction(repairEarly = true)

    private fun verifyConstruction(repairEarly: Boolean) {
        // Run each case in a fresh process. Provider versions differ in whether they reset resources.
        val reset = InstrumentationRegistry.getArguments().getString("webViewResetsApplicationLocale")
        assumeTrue(reset == "true" || reset == "false")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val api = LocaleEasy.getInstance()
        val previous = api.preference.value
        val creationInstrumented =
            InstrumentationRegistry.getArguments().getString("webViewCreationInstrumented") == "true"
        val afterConstruction = if (reset == "true" && !creationInstrumented) ENGLISH else FRENCH
        val displayedApplicationText = if (repairEarly) FRENCH else afterConstruction
        instrumentation.runOnMainSync { api.setAppLocale(Locale.FRENCH) }
        val intent = Intent(instrumentation.targetContext, ComposeWebViewFixtureActivity::class.java)
            .putExtra(ComposeWebViewFixtureActivity.EXTRA_CONSTRUCTION_PROBE, true)
            .putExtra(ComposeWebViewFixtureActivity.EXTRA_REPAIR_EARLY, repairEarly)
        try {
            ActivityScenario.launch<ComposeWebViewFixtureActivity>(intent).use { scenario ->
                compose.onNodeWithText("Before construction: $FRENCH").assertIsDisplayed()
                compose.onNodeWithText("After construction: $afterConstruction").assertIsDisplayed()
                compose.onNodeWithText("Displayed application text: $displayedApplicationText").assertIsDisplayed()
                compose.onNodeWithText("Factory context text: $FRENCH").assertIsDisplayed()
                compose.onNodeWithText("Compose resource: $FRENCH").assertIsDisplayed()
                compose.onNodeWithText("After load:", substring = true).assertDoesNotExist()

                scenario.onActivity { it.loadConstructionProbe() }

                compose.onNodeWithText("After load: $FRENCH").assertIsDisplayed()
                compose.onNodeWithText("Displayed application text: $displayedApplicationText").assertIsDisplayed()
            }
        } finally {
            instrumentation.runOnMainSync { api.setPreference(previous) }
        }
    }

    private companion object {
        const val ENGLISH = "Localized String: Green"
        const val FRENCH = "Chaîne Localisée: Vert"
    }
}
