package com.elianfabian.localeeasy

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elianfabian.localeeasy.ui.legacy.SampleActivity
import com.elianfabian.localeeasy.ui.legacy.SampleFragment
import com.elianfabian.localeeasy.ui.legacy.WebViewFragment
import com.elianfabian.localeeasy.ui.screens.ComposeSampleScreen
import com.elianfabian.localeeasy.ui.screens.MainScreen
import com.elianfabian.localeeasy.ui.settings.LocaleSettingsViewModel
import com.elianfabian.localeeasy.ui.theme.LocaleEasyTheme

class MainActivity : AppCompatActivity() {
	private val viewModel: MainViewModel by viewModels()
	private val settingsViewModel: LocaleSettingsViewModel by viewModels()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setTitle(R.string.app_name)
		setContentView(ComposeView(this).apply {
			id = R.id.main_compose_view
			setContent {
				LocaleEasyTheme {
					val destination by viewModel.destination.collectAsStateWithLifecycle()
					BackHandler(enabled = destination != MainDestination.Home) {
						viewModel.navigateTo(MainDestination.Home)
					}
					when (destination) {
						MainDestination.Home -> MainScreen(
							viewModel = settingsViewModel,
							onOpenCompose = { viewModel.navigateTo(MainDestination.Compose) },
							onOpenActivity = { startActivity(Intent(this@MainActivity, SampleActivity::class.java)) },
							onOpenFragment = {
								SampleFragment().show(supportFragmentManager, "sample_fragment")
							},
							onOpenWebView = {
								WebViewFragment().show(supportFragmentManager, "webview_fragment")
							}
						)
						MainDestination.Compose -> ComposeSampleScreen(
							viewModel = settingsViewModel,
							onBack = { viewModel.navigateTo(MainDestination.Home) }
						)
					}
				}
			}
		})
	}
}
