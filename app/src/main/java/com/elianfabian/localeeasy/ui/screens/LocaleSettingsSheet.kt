package com.elianfabian.localeeasy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elianfabian.localeeasy.MainViewModel
import com.elianfabian.localeeasy.R
import com.elianfabian.localeeasy.UiAction
import com.elianfabian.localeeasy.ui.theme.TranslatedGreen
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocaleSettingsSheet(
	viewModel: MainViewModel,
	sheetState: SheetState,
	onDismiss: () -> Unit
) {
	val isFollowingSystem by viewModel.isFollowingSystemLocale.collectAsStateWithLifecycle()
	val appLocales by viewModel.appLocaleList.collectAsStateWithLifecycle()
	val systemLocales by viewModel.systemLocaleList.collectAsStateWithLifecycle()

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = sheetState
	) {
		Column(
			modifier = Modifier
				.padding(16.dp)
				.fillMaxWidth(),
			verticalArrangement = Arrangement.spacedBy(16.dp)
		) {
			Text(
				text = stringResource(R.string.settings_title),
				style = MaterialTheme.typography.titleLarge,
				color = TranslatedGreen
			)

			Text(
				text = stringResource(R.string.state_following_system, isFollowingSystem),
				color = TranslatedGreen
			)
			Text(
				text = stringResource(R.string.state_app_locales, appLocales.joinToString { it.toLanguageTag() }),
				color = TranslatedGreen
			)
			Text(
				text = stringResource(R.string.state_system_locales, systemLocales.joinToString { it.toLanguageTag() }),
				color = TranslatedGreen
			)

			HorizontalDivider()

			Row(verticalAlignment = Alignment.CenterVertically) {
				Checkbox(
					checked = isFollowingSystem,
					onCheckedChange = { viewModel.sendAction(UiAction.SetFollowSystemLocale(it)) }
				)
				Text(text = stringResource(R.string.follow_system_locale), color = TranslatedGreen)
			}

			LanguageButtons(onLanguageSelected = { viewModel.sendAction(UiAction.SetLocale(it)) })
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageButtons(onLanguageSelected: (Locale) -> Unit) {
	val languages = listOf(
		"English" to Locale.ENGLISH,
		"Spanish" to Locale.forLanguageTag("es"),
		"French" to Locale.FRENCH,
		"Portuguese" to Locale.forLanguageTag("pt"),
		"Italian" to Locale.ITALIAN,
	)

	FlowRow(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		languages.forEach { (name, locale) ->
			Button(onClick = { onLanguageSelected(locale) }) {
				Text(text = name)
			}
		}
	}
}
