package com.elianfabian.localeeasy

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.elianfabian.locale_easy.LocaleEasy
import java.util.concurrent.atomic.AtomicInteger

/** Plain-Activity integration and recreation trace, available only in debug builds. */
class LocaleFixtureActivity : Activity() {
    val instanceId = nextInstance.incrementAndGet()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleEasy.getInstance().localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_locale_fixture)
        Log.i("LocaleEasyFixture", "create instance=$instanceId root=${System.identityHashCode(findViewById<android.view.View>(R.id.locale_fixture_label))} restored=${savedInstanceState != null} text=${getString(R.string.string_resource_example)}")
    }

    override fun onDestroy() {
        Log.i("LocaleEasyFixture", "destroy instance=$instanceId changing=$isChangingConfigurations")
        super.onDestroy()
    }

    companion object {
        val nextInstance = AtomicInteger()
    }
}
