package com.elianfabian.localeeasy.ui.legacy

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.elianfabian.localeeasy.MainViewModel
import com.elianfabian.localeeasy.ui.screens.LocaleSettingsSheet
import com.elianfabian.localeeasy.ui.theme.LocaleEasyTheme

@OptIn(ExperimentalMaterial3Api::class)
abstract class BaseSampleActivity : AppCompatActivity() {
	protected val viewModel: MainViewModel by viewModels()
	private var showSettings by mutableStateOf(false)

	protected fun setupGlobalSettingsFab(composeView: ComposeView) {
		composeView.setContent {
			val sheetState = rememberModalBottomSheetState()
			LocaleEasyTheme {
				if (showSettings) {
					LocaleSettingsSheet(
						viewModel = viewModel,
						sheetState = sheetState,
						onDismiss = { showSettings = false }
					)
				}
			}
		}
	}

	protected fun openSettings() {
		showSettings = true
	}
}
