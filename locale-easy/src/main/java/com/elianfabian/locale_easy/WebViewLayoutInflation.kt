@file:JvmName("WebViewLayoutInflation")

package com.elianfabian.locale_easy

import android.content.Context
import android.os.Build
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import org.xmlpull.v1.XmlPullParser

@PublishedApi
internal fun repairAfterWebViewCreation() {
	LocaleEasy.repairAfterLayoutInflation()
}

@PublishedApi
internal fun inflate(inflater: LayoutInflater, resource: Int, root: ViewGroup?): View =
	withWebViewRepair(inflater).inflate(resource, root)

@PublishedApi
internal fun inflate(inflater: LayoutInflater, resource: Int, root: ViewGroup?, attach: Boolean): View =
	withWebViewRepair(inflater).inflate(resource, root, attach)

@PublishedApi
internal fun inflate(inflater: LayoutInflater, parser: XmlPullParser, root: ViewGroup?): View =
	withWebViewRepair(inflater).inflate(parser, root)

@PublishedApi
internal fun inflate(inflater: LayoutInflater, parser: XmlPullParser, root: ViewGroup?, attach: Boolean): View =
	withWebViewRepair(inflater).inflate(parser, root, attach)

private fun withWebViewRepair(inflater: LayoutInflater): LayoutInflater {
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || Looper.myLooper() != Looper.getMainLooper()) {
		return inflater
	}
	val clone = inflater.cloneInContext(inflater.context)
	clone.factory2 = WebViewRepairFactory(clone.factory, clone.factory2)
	return clone
}

private class WebViewRepairFactory(
	private val existingFactory: LayoutInflater.Factory?,
	private val existingFactory2: LayoutInflater.Factory2?,
) : LayoutInflater.Factory2 {
	override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
		if (name != "WebView" && name != "android.webkit.WebView") return null

		val view = when (val factory = existingFactory2) {
			null -> existingFactory?.onCreateView(name, context, attrs)
			else -> factory.onCreateView(parent, name, context, attrs)
		} ?: WebView(context, attrs)
		if (view is WebView) repairAfterWebViewCreation()
		return view
	}

	override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
		onCreateView(null, name, context, attrs)
}
