package com.elianfabian.localeeasy.ui.screens

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elianfabian.localeeasy.MainViewModel
import com.elianfabian.localeeasy.R
import com.elianfabian.localeeasy.ui.theme.EnglishOnlyColor
import com.elianfabian.localeeasy.ui.theme.TranslatedTextColor
import com.elianfabian.localeeasy.ui.theme.localizableButtonColors
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeSampleScreen(
	viewModel: MainViewModel,
	onBack: () -> Unit,
) {
	var showSettings by rememberSaveable { mutableStateOf(false) }
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

	Scaffold(
		modifier = Modifier.fillMaxSize(),
		topBar = {
			TopAppBar(title = { Text(stringResource(R.string.compose_sample_btn), color = TranslatedTextColor) })
		}
	) { innerPadding ->
		Column(
			modifier = Modifier
				.padding(innerPadding)
				.padding(16.dp)
				.fillMaxWidth(),
			verticalArrangement = Arrangement.spacedBy(16.dp)
		) {
			TextOriginLegend()
			Text(
				text = "Hardcoded Marker (Compose)",
				color = EnglishOnlyColor,
				style = MaterialTheme.typography.labelSmall
			)

			Text(
				text = stringResource(R.string.string_resource_example) + " - ${Locale.getDefault()} + ${androidx.core.os.ConfigurationCompat.getLocales(resources().configuration)}",
				color = TranslatedTextColor,
				style = MaterialTheme.typography.bodyLarge
			)

			val date = remember { Date() }
			val dateFormat = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG)
			Text(
				text = stringResource(R.string.date_format_example, dateFormat.format(date)),
				color = TranslatedTextColor
			)

			Button(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth(), colors = localizableButtonColors()) {
				Text(stringResource(R.string.open_settings))
			}

			TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
				Text("Back", color = EnglishOnlyColor)
			}
		}

		if (showSettings) {
			LocaleSettingsSheet(
				viewModel = viewModel,
				sheetState = sheetState,
				onDismiss = { showSettings = false }
			)
		}
	}
}

@Composable
@ReadOnlyComposable
internal fun resources(): Resources {
	LocalConfiguration.current
	println("$$$ localcontext: ${LocalContext.current}")
	return LocalContext.current.resources
}
