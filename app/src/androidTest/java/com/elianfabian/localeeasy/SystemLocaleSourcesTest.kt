package com.elianfabian.localeeasy

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.LocaleEasy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class SystemLocaleSourcesTest {
    @Test
    fun compareSystemLocaleSourcesWithAndroidAppOverride() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val manager = app.getSystemService(LocaleManager::class.java)
        val api = LocaleEasy.getInstance()
        val preference = api.preference.value
        val effectiveLocales = api.effectiveLocaleList.value
        val originalOverride = manager.applicationLocales
        val systemLocales = manager.systemLocales
        val overrideLocale = listOf(Locale.FRENCH, Locale.ITALIAN, Locale.JAPANESE, Locale.GERMAN)
            .first { it != systemLocales[0] && it != originalOverride[0] }
        val baselineResources = Resources.getSystem().configuration.locales
        val overrideApplied = CountDownLatch(1)
        val callback = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                if (newConfig.locales[0] == overrideLocale) overrideApplied.countDown()
            }

            @Deprecated("Deprecated in Android")
            override fun onLowMemory() = Unit
        }
        app.registerComponentCallbacks(callback)
        try {
            manager.applicationLocales = LocaleList(overrideLocale)
            assertTrue(
                "The app override did not reach the process configuration",
                overrideApplied.await(10, TimeUnit.SECONDS),
            )
            instrumentation.waitForIdleSync()

            val updatedResources = Resources.getSystem().configuration.locales
            val updatedSystem = manager.systemLocales
            val observedSystem = api.systemLocaleList.value
            instrumentation.sendStatus(0, Bundle().apply {
                putString("androidApi", Build.VERSION.SDK_INT.toString())
                putString("appOverride", manager.applicationLocales.toLanguageTags())
                putString("resourcesBefore", baselineResources.toLanguageTags())
                putString("resourcesAfter", updatedResources.toLanguageTags())
                putString("managerBefore", systemLocales.toLanguageTags())
                putString("managerAfter", updatedSystem.toLanguageTags())
                putString("localeEasy", observedSystem.joinToString(",") { it.toLanguageTag() })
            })
            assertEquals(LocaleList(overrideLocale), manager.applicationLocales)
            assertEquals(systemLocales, updatedSystem)
            assertEquals(List(updatedSystem.size()) { updatedSystem[it] }, observedSystem)
            runBlocking {
                withTimeout(10_000) { api.selectedAndroidAppLocale.first { it == overrideLocale } }
                manager.applicationLocales = LocaleList.getEmptyLocaleList()
                withTimeout(10_000) { api.selectedAndroidAppLocale.first { it == null } }
            }
            assertEquals(preference, api.preference.value)
            assertEquals(effectiveLocales, api.effectiveLocaleList.value)
        } finally {
            app.unregisterComponentCallbacks(callback)
            manager.applicationLocales = originalOverride
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun supportedLocaleListReflectsCurrentAndroidConfig() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val manager = app.getSystemService(LocaleManager::class.java)
        val api = LocaleEasy.getInstance()
        val originalConfig = manager.overrideLocaleConfig
        val originalSelection = manager.applicationLocales
        val originalSupported = api.androidSupportedLocaleList
        val selected = List(originalSelection.size()) { originalSelection[it] }
        try {
            val first = (listOf(Locale.FRENCH, Locale.ENGLISH) + selected).distinct()
            manager.overrideLocaleConfig = LocaleConfig(LocaleList(*first.toTypedArray()))
            assertEquals(first, api.androidSupportedLocaleList)

            val second = (listOf(Locale.ITALIAN) + selected).distinct()
            manager.overrideLocaleConfig = LocaleConfig(LocaleList(*second.toTypedArray()))
            assertEquals(second, api.androidSupportedLocaleList)
        } finally {
            manager.overrideLocaleConfig = originalConfig
            if (manager.applicationLocales != originalSelection) {
                manager.applicationLocales = originalSelection
            }
        }
        assertEquals(originalSupported, api.androidSupportedLocaleList)
    }
}
