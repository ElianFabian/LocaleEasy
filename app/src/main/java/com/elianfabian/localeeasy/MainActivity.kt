package com.elianfabian.localeeasy

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.PersistableBundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.os.LocaleListCompat
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.localeeasy.ui.legacy.SampleActivity
import com.elianfabian.localeeasy.ui.legacy.SampleFragment
import com.elianfabian.localeeasy.ui.legacy.WebViewFragment
import com.elianfabian.localeeasy.ui.screens.ComposeSampleScreen
import com.elianfabian.localeeasy.ui.screens.MainScreen
import com.elianfabian.localeeasy.ui.theme.LocaleEasyTheme

class MainActivity : AppCompatActivity(), LocaleEasy.ViewCreatorComponent {
	private val viewModel: MainViewModel by viewModels()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContentView(createContentView())
		setTitle(R.string.app_name)

		onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {

			}
		})

		println("$$$$ string: ${getString(R.string.main_title)} | ${LocaleListCompat.getDefault()}")
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)

		println("$$$ MainActivity onConfigurationChanged: ${LocaleListCompat.getDefault()} | ${newConfig.locales}")
	}

	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleEasy.getInstance().localizedContext(newBase))

		println("$$$ MainActivity attachBaseContext($newBase): ${LocaleListCompat.getDefault()} | ${newBase?.resources?.configuration?.locales}")
	}

	override fun createContentView(): View {
		return ComposeView(this).apply {
			setContent {
				LocaleEasyTheme {
					var currentScreen by rememberSaveable { mutableStateOf("main") }

					println("$$$ currentScreen: $currentScreen")
					
					when (currentScreen) {
						"main" -> MainScreen(
							viewModel = viewModel,
							onOpenCompose = { currentScreen = "compose" },
							onOpenActivity = { startActivity(Intent(this@MainActivity, SampleActivity::class.java)) },
							onOpenFragment = {
								SampleFragment().show(supportFragmentManager, "sample_fragment")
							},
							onOpenWebView = {
								WebViewFragment().show(supportFragmentManager, "webview_fragment")
							}
						)
						"compose" -> ComposeSampleScreen(
							viewModel = viewModel,
							onBack = { currentScreen = "main" }
						)
					}
				}
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()

		println("$$$ onDestroy.isChangingConfigurations: $isChangingConfigurations")
	}
}
