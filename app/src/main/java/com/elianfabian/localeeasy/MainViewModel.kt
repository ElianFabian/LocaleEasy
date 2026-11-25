package com.elianfabian.localeeasy

import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.followSystem
import com.elianfabian.locale_easy.setAppLocale
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface UiAction {
	data class SetLocale(val locale: Locale) : UiAction
	data object FollowSystem : UiAction
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

	val localeState = localeEasy.state

	fun sendAction(action: UiAction) {
		when (action) {
			is UiAction.SetLocale -> localeEasy.setAppLocale(action.locale)
			UiAction.FollowSystem -> localeEasy.followSystem()
			UiAction.FixWebViewLocale -> LocaleEasy.fixWebViewLocale()
		}
	}
}
