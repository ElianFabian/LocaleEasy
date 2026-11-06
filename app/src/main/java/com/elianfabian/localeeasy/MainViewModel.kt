package com.elianfabian.localeeasy

import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.LocalePreference
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed interface UiAction {
	data class SetLocale(val locale: Locale) : UiAction
	data class SetFollowSystemLocale(val follow: Boolean) : UiAction
	data object FixWebViewLocale : UiAction
}

class MainViewModel : ViewModel() {
	private val localeEasy = LocaleEasy.getInstance()

	init {
		viewModelScope.launch {
			localeEasy.effectiveLocales.collect {
				println("$$$ viewModel.appLocalesList: $it | ${LocaleListCompat.getDefault()}")
			}
		}
		viewModelScope.launch {
			localeEasy.systemLocaleList.collect {
				println("$$$ viewModel.systemLocaleList: $it")
			}
		}
	}

	val preference = localeEasy.preference
	val systemLocaleList = localeEasy.systemLocaleList
	val effectiveLocales = localeEasy.effectiveLocales
	private val _frameworkRecreation = MutableStateFlow(localeEasy.useFrameworkLocaleRecreation)
	val frameworkRecreation = _frameworkRecreation.asStateFlow()

	fun setFrameworkRecreation(enabled: Boolean) {
		localeEasy.useFrameworkLocaleRecreation = enabled
		_frameworkRecreation.value = enabled
	}

	fun sendAction(action: UiAction) {
		when (action) {
			is UiAction.SetLocale -> localeEasy.setLocale(action.locale)
			is UiAction.SetFollowSystemLocale -> if (action.follow) {
				localeEasy.followSystem()
			} else {
				localeEasy.setPreference(LocalePreference.AppLocales(effectiveLocales.value))
			}
			UiAction.FixWebViewLocale -> LocaleEasy.fixWebViewLocale()
		}
	}
}
