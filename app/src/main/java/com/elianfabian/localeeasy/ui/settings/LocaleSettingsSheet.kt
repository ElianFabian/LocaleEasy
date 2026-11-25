package com.elianfabian.localeeasy.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elianfabian.locale_easy.AndroidSupportedLocales
import com.elianfabian.locale_easy.LocalePreference
import com.elianfabian.localeeasy.R
import com.elianfabian.localeeasy.ui.screens.TextOriginLegend
import com.elianfabian.localeeasy.ui.theme.EnglishOnlyColor
import com.elianfabian.localeeasy.ui.theme.TranslatedTextColor
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocaleSettingsSheet(
	viewModel: LocaleSettingsViewModel,
	onDismiss: () -> Unit
) {
	val preference by viewModel.preference.collectAsStateWithLifecycle()
	val appliedLocales = (preference as? LocalePreference.AppLocales)?.locales.orEmpty()
	var draftLocales by rememberSaveable(
		appliedLocales,
		stateSaver = Saver<List<Locale>, ArrayList<String>>(
			save = { locales -> ArrayList(locales.map(Locale::toLanguageTag)) },
			restore = { tags -> tags.map(Locale::forLanguageTag) },
		),
	) { mutableStateOf(appliedLocales) }
	val hasChanges = draftLocales != appliedLocales
	val effectiveLocales by viewModel.effectiveLocaleList.collectAsStateWithLifecycle()
	val deviceLocales by viewModel.systemLocaleList.collectAsStateWithLifecycle()
	val androidLanguages by viewModel.androidSupportedLocaleList.collectAsStateWithLifecycle()
	val androidSelection by viewModel.selectedAndroidAppLocale.collectAsStateWithLifecycle()
	val catalog = viewModel.localeCatalog
	val context = LocalContext.current

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
				languages = (catalog.supportedLocales + appliedLocales).distinct(),
				selectedLocales = draftLocales,
				onFollowSystemSelected = { draftLocales = emptyList() },
				onLanguageToggled = { locale ->
					draftLocales = if (locale in draftLocales) draftLocales - locale else draftLocales + locale
				},
			)
			OutlinedButton(
				onClick = { viewModel.applyLocales(draftLocales) },
				enabled = hasChanges,
				modifier = Modifier.fillMaxWidth(),
			) {
				Text(
					text = stringResource(R.string.apply_locale_changes),
					color = if (hasChanges) TranslatedTextColor else Color.Unspecified,
				)
			}
			HorizontalDivider()

			LocaleListSummary(R.string.state_app_locales, effectiveLocales)
			LocaleListSummary(R.string.state_system_locales, deviceLocales)
			LocaleListSummary(R.string.state_catalog_locales, catalog.supportedLocales)
			LocaleSummary(
				R.string.state_catalog_default,
				catalog.defaultLocale?.toLanguageTag() ?: stringResource(R.string.locale_not_configured),
			)

			if (androidLanguages != AndroidSupportedLocales.Unsupported) {
				HorizontalDivider()
				Text(
					text = stringResource(R.string.android_languages_title),
					style = MaterialTheme.typography.titleMedium,
					color = TranslatedTextColor,
				)
				LocaleSummary(
					R.string.state_android_locale,
					androidSelection?.toLanguageTag() ?: stringResource(R.string.android_locale_uses_device),
				)
				val available = (androidLanguages as? AndroidSupportedLocales.Configured)?.locales
				LocaleSummary(
					R.string.state_android_supported_locales,
					when {
						available == null -> stringResource(R.string.locale_not_configured)
						available.isEmpty() -> stringResource(R.string.android_no_languages)
						else -> available.sortedBy(Locale::toLanguageTag).joinToString { it.toLanguageTag() }
					},
				)
				Text(
					text = stringResource(R.string.android_languages_description),
					style = MaterialTheme.typography.bodySmall,
					color = TranslatedTextColor,
				)
				if (!available.isNullOrEmpty()) {
					OutlinedButton(
						onClick = { context.openAndroidAppLanguages() },
						modifier = Modifier.fillMaxWidth(),
					) {
						Text(stringResource(R.string.open_android_languages), color = TranslatedTextColor)
					}
				}
			}
		}
	}
}

@Composable
private fun LocaleListSummary(label: Int, locales: List<Locale>) {
	LocaleSummary(label, locales.joinToString { it.toLanguageTag() }.ifEmpty {
		stringResource(R.string.locale_not_configured)
	})
}

@Composable
private fun LocaleSummary(label: Int, value: String) {
	Text(stringResource(label, value), color = TranslatedTextColor)
}

private fun Context.openAndroidAppLanguages() {
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
	val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", packageName, null))
	try {
		startActivity(intent)
	} catch (_: ActivityNotFoundException) {
		Toast.makeText(this, R.string.android_languages_unavailable, Toast.LENGTH_SHORT).show()
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageButtons(
	languages: List<Locale>,
	selectedLocales: List<Locale>,
	onFollowSystemSelected: () -> Unit,
	onLanguageToggled: (Locale) -> Unit,
) {
	FlowRow(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		FilterChip(
			selected = selectedLocales.isEmpty(),
			onClick = onFollowSystemSelected,
			label = { Text(text = stringResource(R.string.follow_system_locale), color = TranslatedTextColor) },
		)
		languages.forEach { locale ->
			FilterChip(
				selected = locale in selectedLocales,
				onClick = { onLanguageToggled(locale) },
				label = { Text(text = locale.getDisplayName(Locale.ENGLISH), color = EnglishOnlyColor) },
			)
		}
	}
}
