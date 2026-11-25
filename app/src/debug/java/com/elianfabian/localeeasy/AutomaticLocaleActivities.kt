package com.elianfabian.localeeasy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class AutomaticPlainLocaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_locale_fixture)
    }
}

abstract class AutomaticLocaleBaseActivity : AppCompatActivity() {
    var attachCalls = 0
        private set

    final override fun attachBaseContext(newBase: Context) {
        attachCalls++
        super.attachBaseContext(ContextWrapper(newBase))
    }
}

class AutomaticAppCompatLocaleActivity : AutomaticLocaleBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_locale_fixture)
    }
}
