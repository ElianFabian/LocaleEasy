package com.elianfabian.localeeasy

import android.content.res.Configuration
import android.os.Bundle
import androidx.core.os.ConfigurationCompat
import com.elianfabian.localeeasy.ui.legacy.SampleActivity

/** Investigation fixture: framework preference delivery followed by a local window-preserving relaunch. */
class LocalRelaunchSampleActivity : SampleActivity() {
    private var appliedLocales = ""
    private var appliedScreenLayout = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedLocales = ConfigurationCompat.getLocales(resources.configuration).toLanguageTags()
        appliedScreenLayout = resources.configuration.screenLayout
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        android.os.Trace.beginSection("LocaleEasy.hybrid.configuration")
        try {
            super.onConfigurationChanged(newConfig)
            val next = ConfigurationCompat.getLocales(newConfig).toLanguageTags()
            if (next != appliedLocales || newConfig.screenLayout != appliedScreenLayout) {
                appliedLocales = next
                appliedScreenLayout = newConfig.screenLayout
                recreate()
            }
        } finally { android.os.Trace.endSection() }
    }
}
