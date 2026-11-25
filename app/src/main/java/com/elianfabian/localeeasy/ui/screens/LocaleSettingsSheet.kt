package com.elianfabian.localeeasy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elianfabian.locale_easy.LocalePreference
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.localeeasy.MainViewModel
import com.elianfabian.localeeasy.R
import com.elianfabian.localeeasy.UiAction
import com.elianfabian.localeeasy.ui.theme.TranslatedTextColor
import com.elianfabian.localeeasy.ui.theme.EnglishOnlyColor
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocaleSettingsSheet(
	viewModel: MainViewModel,
	sheetState: SheetState,
	onDismiss: () -> Unit
) {
	val preference by viewModel.preference.collectAsStateWithLifecycle()
	val appLocales by viewModel.effectiveLocaleList.collectAsStateWithLifecycle()
	val systemLocales by viewModel.systemLocaleList.collectAsStateWithLifecycle()
	val isFollowingSystem = preference == LocalePreference.FollowSystem

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = sheetState
	) {
		Column(
			modifier = Modifier
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 24.dp, vertical = 16.dp)
				.fillMaxWidth(),
			verticalArrangement = Arrangement.spacedBy(16.dp)
		) {
			Text(
				text = stringResource(R.string.settings_title),
				style = MaterialTheme.typography.titleLarge,
				color = TranslatedTextColor
			)
			TextOriginLegend()

			LanguageButtons(
				selectedLocale = if (isFollowingSystem) null else appLocales.firstOrNull(),
				onFollowSystemSelected = { viewModel.sendAction(UiAction.FollowSystem) },
				onLanguageSelected = { viewModel.sendAction(UiAction.SetLocale(it)) },
			)
			HorizontalDivider()

			Text(
				text = stringResource(R.string.state_following_system, isFollowingSystem),
				color = TranslatedTextColor
			)
			Text(
				text = stringResource(R.string.state_app_locales, appLocales.joinToString { it.toLanguageTag() }),
				color = TranslatedTextColor
			)
			Text(
				text = stringResource(R.string.state_system_locales, systemLocales.joinToString { it.toLanguageTag() }),
				color = TranslatedTextColor
			)

		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageButtons(
	selectedLocale: Locale?,
	onFollowSystemSelected: () -> Unit,
	onLanguageSelected: (Locale) -> Unit,
) {
	val languages = LocaleEasy.getInstance().localeCatalog.supportedLocales
	val selectedLanguage = selectedLocale?.let { selected ->
		languages.firstOrNull { it == selected } ?: Locale.forLanguageTag(selected.language)
	}

	FlowRow(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		FilterChip(
			selected = selectedLocale == null,
			onClick = onFollowSystemSelected,
			label = { Text(text = stringResource(R.string.follow_system_locale), color = TranslatedTextColor) },
		)
		languages.forEach { locale ->
			FilterChip(
				selected = selectedLanguage == locale,
				onClick = { onLanguageSelected(locale) },
				label = { Text(text = locale.getDisplayName(Locale.ENGLISH), color = EnglishOnlyColor) },
			)
		}
	}
}
