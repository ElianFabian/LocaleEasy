package com.elianfabian.localeeasy.ui.legacy

import android.content.Intent
import android.os.Bundle
import com.elianfabian.localeeasy.R
import com.elianfabian.localeeasy.databinding.ActivitySampleBinding
import java.text.DateFormat
import java.util.Date

class SampleActivity : BaseSampleActivity() {
	private lateinit var binding: ActivitySampleBinding

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = ActivitySampleBinding.inflate(layoutInflater)
		setContentView(binding.root)

		setSupportActionBar(binding.toolbar)
		
		val level = intent.getIntExtra("level", 1)
		setTitle(if (level == 1) R.string.activity_title_1 else R.string.activity_title_2)

		val date = Date()
		val dateFormat = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG)
		binding.textViewDate.text = getString(R.string.date_format_example, dateFormat.format(date))

		binding.btnNext.setOnClickListener {
			val intent = Intent(this, SampleActivity::class.java).apply {
				putExtra("level", level + 1)
			}
			startActivity(intent)
		}

		binding.btnSettings.setOnClickListener {
			openSettings()
		}

		setupGlobalSettingsFab(binding.settingsComposeView)
	}
}
