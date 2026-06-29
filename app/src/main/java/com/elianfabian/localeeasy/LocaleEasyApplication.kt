package com.elianfabian.localeeasy

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import com.elianfabian.locale_easy.LocaleEasy
import java.util.Locale

class LocaleEasyApplication : Application() {

	override fun onCreate() {
		super.onCreate()
		val systemConfiguration = Resources.getSystem().configuration
		val localeListCompat = ConfigurationCompat.getLocales(systemConfiguration)
		val localeManager = getSystemService(LOCALE_SERVICE) as LocaleManager
		println("$$$ LocaleEasyApplication: $localeListCompat | ${localeManager.systemLocales} | ${AppCompatDelegate.getApplicationLocales()} | ${Locale.getDefault()} | ${LocaleListCompat.getDefault()}")
		LocaleEasy.install(this)

	}

	override fun attachBaseContext(base: Context) {
		super.attachBaseContext(base)
		println("$$$ LocaleEasyApplication attachBaseContext: $base")
		//LocaleEasy.install(base)
	}
}
