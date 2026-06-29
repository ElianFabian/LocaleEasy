package com.elianfabian.localeeasy.ui.screens

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elianfabian.localeeasy.MainViewModel
import com.elianfabian.localeeasy.R
import com.elianfabian.localeeasy.ui.theme.HardcodedRed
import com.elianfabian.localeeasy.ui.theme.TranslatedGreen
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeSampleScreen(
	viewModel: MainViewModel,
	onBack: () -> Unit,
) {
	var showSettings by remember() { mutableStateOf(false) }
	val sheetState = rememberModalBottomSheetState()

	Scaffold(
		modifier = Modifier.fillMaxSize(),
		topBar = {
			TopAppBar(title = { Text(stringResource(R.string.compose_sample_btn), color = TranslatedGreen) })
		}
	) { innerPadding ->
		Column(
			modifier = Modifier
				.padding(innerPadding)
				.padding(16.dp)
				.fillMaxWidth(),
			verticalArrangement = Arrangement.spacedBy(16.dp)
		) {
			Text(
				text = "Hardcoded Marker (Compose)",
				color = HardcodedRed,
				style = MaterialTheme.typography.labelSmall
			)

			Text(
				text = stringResource(R.string.string_resource_example) + " - ${Locale.getDefault()} + ${resources().configuration.locales}",
				color = TranslatedGreen,
				style = MaterialTheme.typography.bodyLarge
			)

			val date = remember { Date() }
			val dateFormat = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG)
			Text(
				text = stringResource(R.string.date_format_example, dateFormat.format(date)),
				color = TranslatedGreen
			)

			Button(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.open_settings))
			}

			Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
				Text("Back")
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
	println("$$$ localcontext: ${LocalContext.current} | ${LocalContext.current.resources.configuration.locales}")
	return LocalContext.current.resources
}
