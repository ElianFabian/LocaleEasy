package com.elianfabian.localeeasy.ui.legacy

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebViewClient
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.localeeasy.MainViewModel
import com.elianfabian.localeeasy.databinding.FragmentWebviewBinding
import com.elianfabian.localeeasy.ui.screens.LocaleSettingsSheet
import com.elianfabian.localeeasy.ui.theme.LocaleEasyTheme

class WebViewFragment : DialogFragment() {
	private val viewModel: MainViewModel by activityViewModels()
	private var _binding: FragmentWebviewBinding? = null
	private val binding get() = _binding!!
	
	private var showSettings by mutableStateOf(false)

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		_binding = FragmentWebviewBinding.inflate(inflater, container, false)
		return binding.root
	}

	@OptIn(ExperimentalMaterial3Api::class)
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		binding.webView.webViewClient = WebViewClient()
		binding.webView.loadUrl("https://www.google.com")

		binding.btnFix.setOnClickListener {
			LocaleEasy.fixWebViewLocale()
			binding.webView.reload()
		}

		binding.btnSettings.setOnClickListener {
			showSettings = true
		}

		binding.settingsComposeView.setContent {
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

	override fun onStart() {
		super.onStart()
		dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
	}

	override fun onDestroyView() {
		super.onDestroyView()
		_binding = null
	}
}
