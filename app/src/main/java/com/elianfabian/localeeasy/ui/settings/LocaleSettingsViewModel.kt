package com.elianfabian.localeeasy.ui.settings

import androidx.lifecycle.ViewModel
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.followSystem
import com.elianfabian.locale_easy.setAppLocale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class LocaleSettingsViewModel : ViewModel() {
	private val localeEasy = LocaleEasy.getInstance()

	val localeCatalog = localeEasy.localeCatalog
	val preference = localeEasy.preference
	val effectiveLocaleList = localeEasy.effectiveLocaleList
	val systemLocaleList = localeEasy.systemLocaleList
	val selectedAndroidAppLocale = localeEasy.selectedAndroidAppLocale

	private val androidLanguages = MutableStateFlow(localeEasy.androidSupportedLocaleList)
	val androidSupportedLocaleList = androidLanguages.asStateFlow()

	fun followSystem() = localeEasy.followSystem()

	fun selectLocale(locale: Locale) = localeEasy.setAppLocale(locale)

	fun refreshAndroidSupportedLocales() {
		androidLanguages.value = localeEasy.androidSupportedLocaleList
	}
}
