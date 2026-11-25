package com.elianfabian.localeeasy.ui.legacy

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elianfabian.localeeasy.ui.settings.LocaleSettingsViewModel
import com.elianfabian.localeeasy.R
import com.elianfabian.localeeasy.databinding.FragmentSampleBinding
import com.elianfabian.localeeasy.ui.settings.LocaleSettingsSheet
import com.elianfabian.localeeasy.ui.theme.LocaleEasyTheme
import java.text.DateFormat
import java.util.Date

class SampleFragment : DialogFragment() {
	private val viewModel: LocaleSettingsViewModel by activityViewModels()
	private var _binding: FragmentSampleBinding? = null
	private val binding get() = _binding!!
	
	private var showSettings by mutableStateOf(false)

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		_binding = FragmentSampleBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		showSettings = savedInstanceState?.getBoolean("settings_open") ?: showSettings

		val date = Date()
		val dateFormat = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG)
		binding.dateText.text = getString(R.string.date_format_example, dateFormat.format(date))

		binding.btnSettings.setOnClickListener {
			showSettings = true
		}

		binding.settingsComposeView.setContent {
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

	override fun onSaveInstanceState(outState: Bundle) {
		outState.putBoolean("settings_open", showSettings)
		super.onSaveInstanceState(outState)
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
