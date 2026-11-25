package com.elianfabian.localeeasy

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.Fragment
import java.util.concurrent.atomic.AtomicReference

class WebViewContentFixtureActivity : AppCompatActivity() {
    var usedResourceOverload = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("fragment", false)) {
            setContentView(FrameLayout(this).apply { id = android.R.id.custom })
            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction()
                    .replace(android.R.id.custom, WebViewContentFixtureFragment())
                    .commitNow()
            }
            return
        }
        val layout = if (intent.getBooleanExtra("merge", false)) {
            R.layout.webview_content_merge_fixture
        } else {
            R.layout.webview_content_fixture
        }
        setContentView(layout)
    }

    override fun setContentView(layoutResID: Int) {
        usedResourceOverload = true
        super.setContentView(layoutResID)
    }
}

class WebViewContentFixtureFragment : Fragment(R.layout.webview_content_fixture)

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
