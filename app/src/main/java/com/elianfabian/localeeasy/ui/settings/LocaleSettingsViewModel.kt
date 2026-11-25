package com.elianfabian.localeeasy.ui.settings

import androidx.lifecycle.ViewModel
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.LocalePreference
import com.elianfabian.locale_easy.followSystem
import com.elianfabian.locale_easy.setAppLocaleList
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

	fun toggleLocale(locale: Locale) {
		val selected = (preference.value as? LocalePreference.AppLocales)?.locales.orEmpty()
		val updated = if (locale in selected) selected - locale else selected + locale
		if (updated.isEmpty()) {
			followSystem()
		} else {
			localeEasy.setAppLocaleList(updated)
		}
	}
}
