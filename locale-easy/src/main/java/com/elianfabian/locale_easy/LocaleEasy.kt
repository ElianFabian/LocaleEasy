package com.elianfabian.locale_easy

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

public interface LocaleEasy {
	/** The app's language catalog, read once at startup. Its default is optional. */
	public val localeCatalog: LocaleCatalog get() = LocaleCatalog.Empty

	/** The locale first applied by [setInitialLocale], or null if none was recorded. */
	public val initialAppLocale: Locale?

	/** Languages offered in Android Settings. Refreshed on activity resume and configuration changes. */
	public val androidSupportedLocaleList: StateFlow<AndroidSupportedLocales>

	/** The user's selection. This is not a signal that activity recreation has completed. */
	public val preference: StateFlow<LocalePreference>

	public val systemLocaleList: StateFlow<List<Locale>>

	/**
	 * Locales resolved from [preference] and system languages, in preference order.
	 * Resource fallback may select a different supported language. Existing views may
	 * still be awaiting refresh when this value changes.
	 */
	public val effectiveLocaleList: StateFlow<List<Locale>>

	/** The primary app language saved by Android. Null when unset or before Android 13. */
	public val selectedAndroidAppLocale: StateFlow<Locale?>

	/**
	 * Apply and save [locale] if the app has no saved preference. An explicit [followSystem]
	 * choice also counts as a saved preference. Call from Application.onCreate() to apply the
	 * initial locale before the first activity opens.
	 */
	@MainThread
	public fun setInitialLocale(locale: Locale)

	@MainThread
	public fun setPreference(preference: LocalePreference)

	/**
	 * Replace the languages offered in Android Settings. An empty list offers zero languages;
	 * null removes the override and restores the APK configuration. Android can clear its
	 * saved app language if it is excluded by the new configuration.
	 */
	@MainThread
	@RequiresApi(34)
	public fun setAndroidSupportedLocaleList(locales: List<Locale>?)

	/**
	 * Return a context for the current selection. Wrap an activity's base context in
	 * `attachBaseContext`, or use the result for one-off resource lookups.
	 * Obtain a fresh context after a language change.
	 */
	public fun localizedContext(base: Context): Context

	/** Return a context for a one-off resource lookup in [locale], independent of the saved choice. */
	public fun localizedContext(base: Context, locale: Locale): Context =
		localizedContext(base, listOf(locale))

	/**
	 * Return a context for a one-off resource lookup with [locales] in preference order.
	 * The list must be nonempty and contain valid locales. Repeated locales are ignored
	 * after their first occurrence. On API 21–23, Android resources use the first locale.
	 * The saved preference and observable locale state are unaffected.
	 */
	public fun localizedContext(base: Context, locales: List<Locale>): Context

	public companion object {
		@Volatile
		private var _instance: LocaleEasy? = null

		public fun getInstance(): LocaleEasy {
			return _instance ?: error("Attempt to access LocaleEasy instance before being initialized")
		}

		/**
		 * Automatically installed by AndroidX Startup. For manual initialization, call from
		 * Application.onCreate() on the main thread. Repeated calls are safe.
		 */
		@MainThread
		public fun install(context: Context) {
			checkMainThread()
			if (_instance == null) {
				_instance = AndroidLocaleEasy(context.applicationContext as Application)
			}
		}

		/**
		 * On Android 7.0 and later, repair application resources if WebView changes their locales.
		 * Activity resource scopes and existing WebViews retain their configurations.
		 */
		@MainThread
		public fun fixWebViewLocale() {
			checkMainThread()
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
			(getInstance() as? AndroidLocaleEasy)?.reapplyResources()
		}

		// Called by the Gradle plugin after layout inflation, including layouts without a WebView.
		@PublishedApi
		internal fun repairAfterLayoutInflation() {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Looper.myLooper() == Looper.getMainLooper()) {
				(_instance as? AndroidLocaleEasy)?.reapplyResources()
			}
		}
	}
}

/** Save a single locale for the app. */
@MainThread
public fun LocaleEasy.setAppLocale(locale: Locale) {
	setAppLocaleList(listOf(locale))
}

/** Save the app's locales in preference order. */
@MainThread
public fun LocaleEasy.setAppLocaleList(locales: List<Locale>) {
	setPreference(LocalePreference.AppLocales(locales))
}

/** Save the preference to follow the device's languages. */
@MainThread
public fun LocaleEasy.followSystem() {
	setPreference(LocalePreference.FollowSystem)
}
