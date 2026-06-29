package com.elianfabian.locale_easy

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

// Based on. https://github.com/YarikSOffice/lingver/blob/master/library/src/main/java/com/yariksoffice/lingver/Lingver.kt
public interface LocaleEasy {

	public val isFollowingSystemLocale: StateFlow<Boolean>

	public val systemLocaleList: StateFlow<List<Locale>>

	public val appLocaleList: StateFlow<List<Locale>>

	public var followSystemLocale: Boolean

	public fun setAppLocale(locale: Locale)

	public fun setAppLocaleList(localeList: List<Locale>)

	public fun localizedContext(base: Context): Context

	/**
	 * This interface must be implemented by the all activities that want to be updated when the locale changes.
	 *
	 * We need to be able to recreate the activity view to apply the new locale.
	 *
	 * If your activity does not implement this interface, the activity will be recreated calling [Activity.recreate],
	 * which it's a heavier operation than just recreating the view.
	 */
	public interface ViewCreatorComponent {
		public fun createContentView(): View
	}

	public companion object {
		private var _instance: LocaleEasy? = null

		public fun getInstance(): LocaleEasy {
			return _instance ?: error("Attempt to access LocaleEasy instance before being initialized")
		}

		public fun install(context: Context) {
			_instance = AndroidLocaleEasy(context.applicationContext as Application)
		}

		/**
		 * There's a problem starting from Android 7.0 (API 24) and above, where the WebView doesn't respect the app's locale.
		 *
		 * More info: https://stackoverflow.com/a/59623196/18418162
		 *
		 * TODO: Test this method.
		 */
		public fun fixWebViewLocale() {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				val instance = getInstance()
				if (instance is AndroidLocaleEasy) {
					with(instance) {
						WebView(application).destroy()
						updateResources(application, appLocaleList.value)
					}
				}
			}
		}
	}
}
