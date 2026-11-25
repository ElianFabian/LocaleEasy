package com.elianfabian.localeeasy.ui.legacy

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.elianfabian.localeeasy.ui.settings.LocaleSettingsSheet
import com.elianfabian.localeeasy.ui.settings.LocaleSettingsViewModel
import com.elianfabian.localeeasy.ui.theme.LocaleEasyTheme

abstract class BaseSampleActivity : AppCompatActivity() {
	private val viewModel: LocaleSettingsViewModel by viewModels()
	private var showSettings by mutableStateOf(false)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		showSettings = savedInstanceState?.getBoolean("settings_open") ?: false
	}

	override fun onSaveInstanceState(outState: Bundle) {
		outState.putBoolean("settings_open", showSettings)
		super.onSaveInstanceState(outState)
	}

	protected fun setupGlobalSettingsFab(composeView: ComposeView) {
		composeView.setContent {
			LocaleEasyTheme {
				if (showSettings) {
					LocaleSettingsSheet(
						viewModel = viewModel,
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
