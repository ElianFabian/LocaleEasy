@file:JvmName("AppCompatWebViewContent")

package com.elianfabian.locale_easy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

private class AppCompatContentRequest(val activity: Activity, val resource: Int)

private val pendingAppCompatContent = ThreadLocal<AppCompatContentRequest>()

// The Gradle plugin redirects opted-in AppCompatActivity.setContentView(Int) calls here.
@PublishedApi
internal fun setAppCompatContentView(activity: Activity, resource: Int) {
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || Looper.myLooper() != Looper.getMainLooper()) {
		activity.setContentView(resource)
		return
	}
	val previous = pendingAppCompatContent.get()
	pendingAppCompatContent.set(AppCompatContentRequest(activity, resource))
	try {
		activity.setContentView(resource)
	} finally {
		if (previous == null) pendingAppCompatContent.remove() else pendingAppCompatContent.set(previous)
	}
}

// The Gradle plugin redirects the inflate call inside AppCompatDelegateImpl to this method.
@PublishedApi
internal fun inflateAppCompatContent(inflater: LayoutInflater, resource: Int, parent: ViewGroup): View {
	val request = pendingAppCompatContent.get()
	if (request != null && request.resource == resource && inflater.context.belongsTo(request.activity)) {
		// Consume the request before a nested inflation can see it.
		pendingAppCompatContent.remove()
		return inflate(inflater, resource, parent)
	}
	return inflater.inflate(resource, parent)
}

private fun Context.belongsTo(activity: Activity): Boolean {
	var context = this
	while (context is ContextWrapper && context !== activity) {
		context = context.baseContext
	}
	return context === activity
}
