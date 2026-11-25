package com.elianfabian.localeeasy

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import java.util.concurrent.atomic.AtomicReference

class WebViewContentFixtureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = if (intent.getBooleanExtra("merge", false)) {
            R.layout.webview_content_merge_fixture
        } else {
            R.layout.webview_content_fixture
        }
        setContentView(layout)
    }
}

class WebViewConstructorLabel(context: Context, attrs: AttributeSet) : AppCompatTextView(context, attrs) {
    init {
        val localizedText = context.applicationContext.getString(R.string.string_resource_example)
        firstText.compareAndSet(null, localizedText)
        text = localizedText
    }

    companion object {
        val firstText = AtomicReference<String?>()
    }
}
