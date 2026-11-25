@file:JvmName("WebViewContentView")

package com.elianfabian.locale_easy

import android.app.Activity
import android.os.Build
import android.os.Looper
import org.xmlpull.v1.XmlPullParser

// The Gradle plugin redirects opted-in plain Activity.setContentView(Int) calls here.
@PublishedApi
internal fun setContentView(activity: Activity, resource: Int) {
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || Looper.myLooper() != Looper.getMainLooper()) {
		activity.setContentView(resource)
		return
	}
	val isMerge = activity.resources.getLayout(resource).use { parser ->
		var event = parser.eventType
		while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
			event = parser.next()
		}
		parser.name == "merge"
	}
	// A merge layout needs the window's real container and immediate attachment.
	if (isMerge) {
		activity.setContentView(resource)
	} else {
		activity.setContentView(inflate(activity.layoutInflater, resource, null, false))
	}
}
