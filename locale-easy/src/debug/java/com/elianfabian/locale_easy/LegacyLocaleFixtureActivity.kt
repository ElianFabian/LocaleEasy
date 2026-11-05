package com.elianfabian.locale_easy

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.TextView

/** Minimal consumer fixture for the library's API-21 floor, independent of the sample. */
public class LegacyLocaleFixtureActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleEasy.getInstance().localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            id = android.R.id.text1
            setText(R.string.locale_easy_fixture_label)
        })
    }
}
