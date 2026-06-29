package com.elianfabian.localeeasy.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elianfabian.localeeasy.MainViewModel
import com.elianfabian.localeeasy.R
import com.elianfabian.localeeasy.ui.theme.TranslatedGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
	viewModel: MainViewModel,
	onOpenCompose: () -> Unit,
	onOpenActivity: () -> Unit,
	onOpenFragment: () -> Unit,
	onOpenWebView: () -> Unit
) {
	var showSettings by remember { mutableStateOf(false) }
	val sheetState = rememberModalBottomSheetState()

	Scaffold(
		modifier = Modifier.fillMaxSize(),
		topBar = {
			TopAppBar(title = { Text(stringResource(R.string.main_title), color = TranslatedGreen) })
		}
	) { innerPadding ->
		Column(
			modifier = Modifier
				.padding(innerPadding)
				.padding(16.dp)
				.fillMaxWidth(),
			verticalArrangement = Arrangement.spacedBy(16.dp)
		) {
			Button(onClick = onOpenCompose, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.compose_sample_btn))
			}
			Button(onClick = onOpenActivity, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.activity_sample_btn))
			}
			Button(onClick = onOpenFragment, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.fragment_sample_btn))
			}
			Button(onClick = onOpenWebView, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.webview_sample_btn))
			}

			Button(
				onClick = { showSettings = true },
				modifier = Modifier.fillMaxWidth()
			) {
				Text(stringResource(R.string.open_settings))
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
