package com.elianfabian.locale_easy

import android.app.Application
import android.content.Context
import android.view.View
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

// Based on. https://github.com/YarikSOffice/lingver/blob/master/library/src/main/java/com/yariksoffice/lingver/Lingver.kt
public interface LocaleEasy {

	/**
	 * Temporary comparison flag. Defaults to true on API 33+, false on older Android.
	 * False uses localized activity contexts and Activity.recreate(); true delegates to
	 * LocaleManager (requires API 33+). Persisted across process restarts.
	 * Changing backend can itself recreate activities; compare subsequent language changes.
	 * Manual mode does not synchronize with Android's per-app language settings.
	 */
	@set:MainThread
	public var useFrameworkLocaleRecreation: Boolean

	/** The user's selection. This is not a signal that activity recreation has completed. */
	public val preference: StateFlow<LocalePreference>

	/**
	 * Locales resolved from [preference] and system languages, in preference order.
	 * Resource fallback may select a different supported language. Existing views may
	 * still be awaiting refresh when this value changes.
	 */
	public val effectiveLocales: StateFlow<List<Locale>>

	@MainThread
	public fun setPreference(preference: LocalePreference)

	@MainThread
	public fun followSystem() { setPreference(LocalePreference.FollowSystem) }

	@MainThread
	public fun setLocale(locale: Locale) { setLocales(listOf(locale)) }

	@MainThread
	public fun setLocales(locales: List<Locale>) { setPreference(LocalePreference.AppLocales(locales)) }

	/** Compatibility view of whether [preference] is [LocalePreference.FollowSystem]. */
	public val isFollowingSystemLocale: StateFlow<Boolean>

	public val systemLocaleList: StateFlow<List<Locale>>

	/** Compatibility alias for [effectiveLocales]. */
	public val appLocaleList: StateFlow<List<Locale>> get() = effectiveLocales

	/** Setting false freezes the current effective locales as a custom preference. */
	@set:MainThread
	public var followSystemLocale: Boolean

	@MainThread
	public fun setAppLocale(locale: Locale) { setLocale(locale) }

	@MainThread
	public fun setAppLocaleList(localeList: List<Locale>) { setLocales(localeList) }

	public fun localizedContext(base: Context): Context

	/**
	 * Legacy interface. LocaleEasy uses activity recreation and does not call this method.
		 * Use localizedContext() from attachBaseContext(); no view factory is required.
	 */
	@Deprecated("LocaleEasy recreates activities; a view factory is not required")
	public interface ViewCreatorComponent {
		public fun createContentView(): View
	}

	public companion object {
		@Volatile private var _instance: LocaleEasy? = null

		public fun getInstance(): LocaleEasy {
			return _instance ?: error("Attempt to access LocaleEasy instance before being initialized")
		}

		/** Automatically installed by AndroidX Startup. For manual initialization, call from
		 * Application.onCreate() on the main thread. Repeated calls are safe. */
		@MainThread
		public fun install(context: Context) {
			checkMainThread()
			if (_instance == null) _instance = AndroidLocaleEasy(context.applicationContext as Application)
		}

		/**
		 * Reapply native resources in manual mode after a WebView provider changes configuration.
		 * Does not translate websites, reload existing WebViews, or rebuild native views.
		 * This is a no-op while framework locale recreation is enabled.
		 */
		@MainThread
		public fun fixWebViewLocale() {
			checkMainThread()
			(getInstance() as? AndroidLocaleEasy)?.reapplyResources()
		}
	}
}
