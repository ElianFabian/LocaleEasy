package com.elianfabian.localeeasy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elianfabian.locale_easy.LocaleEasy
import kotlinx.coroutines.launch
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
			localeEasy.appLocaleList.collect {
				println("$$$ viewModel.appLocalesList: $it | ${LocaleListCompat.getDefault()}")
			}
		}
		viewModelScope.launch {
			localeEasy.systemLocaleList.collect {
				println("$$$ viewModel.systemLocaleList: $it")
			}
		}
	}

	val isFollowingSystemLocale = localeEasy.isFollowingSystemLocale
	val systemLocaleList = localeEasy.systemLocaleList
	val appLocaleList = localeEasy.appLocaleList

	fun sendAction(action: UiAction) {
		when (action) {
			is UiAction.SetLocale -> localeEasy.setAppLocale(action.locale)
			is UiAction.SetFollowSystemLocale -> localeEasy.followSystemLocale = action.follow
			UiAction.FixWebViewLocale -> LocaleEasy.fixWebViewLocale()
		}
	}
}
