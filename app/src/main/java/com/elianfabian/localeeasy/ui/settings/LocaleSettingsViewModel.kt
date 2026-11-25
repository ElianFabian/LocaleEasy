package com.elianfabian.localeeasy.ui.settings

import androidx.lifecycle.ViewModel
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.followSystem
import com.elianfabian.locale_easy.setAppLocale
import java.util.Locale

class LocaleSettingsViewModel : ViewModel() {
	private val localeEasy = LocaleEasy.getInstance()

	val localeCatalog = localeEasy.localeCatalog
	val preference = localeEasy.preference
	val effectiveLocaleList = localeEasy.effectiveLocaleList
	val systemLocaleList = localeEasy.systemLocaleList
	val selectedAndroidAppLocale = localeEasy.selectedAndroidAppLocale
	val androidSupportedLocaleList = localeEasy.androidSupportedLocaleList

	fun followSystem() = localeEasy.followSystem()

	fun selectLocale(locale: Locale) = localeEasy.setAppLocale(locale)
}
