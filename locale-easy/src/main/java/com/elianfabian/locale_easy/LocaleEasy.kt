package com.elianfabian.locale_easy

import android.app.Application
import android.content.Context
import android.view.View
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

// Based on. https://github.com/YarikSOffice/lingver/blob/master/library/src/main/java/com/yariksoffice/lingver/Lingver.kt
public interface LocaleEasy {

	/** The app's language catalog, read once at startup. Its default is optional. */
	public val localeCatalog: LocaleCatalog get() = LocaleCatalog.Empty

	/** The applied language selection and its resolution, available in one read. */
	public val state: StateFlow<LocaleState>

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
	public fun setAppLocale(locale: Locale) { setAppLocales(listOf(locale)) }

	/** Set the app's locales in preference order. */
	@MainThread
	public fun setAppLocales(locales: List<Locale>) { setPreference(LocalePreference.AppLocales(locales)) }

	/**
	 * Apply and save [locale] if the app has no saved preference. An explicit [followSystem]
	 * choice also counts as a saved preference. Call from Application.onCreate() to apply the
	 * initial locale before the first activity opens.
	 */
	@MainThread
	public fun setInitialLocale(locale: Locale)

	/** Select a language using a well-formed BCP 47 tag such as `fr-CA`. */
	@MainThread
	public fun setLanguageTag(tag: String) {
		setAppLocale(requireNotNull(parseLocaleTag(tag)) { "Expected a BCP 47 tag with a language" })
	}

	@Deprecated("Use setAppLocale", ReplaceWith("setAppLocale(locale)"))
	@MainThread
	public fun setLocale(locale: Locale) { setAppLocale(locale) }

	@Deprecated("Use setAppLocales", ReplaceWith("setAppLocales(locales)"))
	@MainThread
	public fun setLocales(locales: List<Locale>) { setAppLocales(locales) }

	/** Compatibility view of whether [preference] is [LocalePreference.FollowSystem]. */
	public val isFollowingSystemLocale: StateFlow<Boolean>

	public val systemLocaleList: StateFlow<List<Locale>>

	/** Compatibility alias for [effectiveLocales]. */
	public val appLocaleList: StateFlow<List<Locale>> get() = effectiveLocales

	/** Setting false freezes the current effective locales as a custom preference. */
	@set:MainThread
	public var followSystemLocale: Boolean

	@Deprecated("Use setAppLocales", ReplaceWith("setAppLocales(localeList)"))
	@MainThread
	public fun setAppLocaleList(localeList: List<Locale>) { setAppLocales(localeList) }

	/** Return a context for the current selection. Obtain a new one after a language change. */
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
		 * Repair application resources in local mode if a WebView provider changes their locales.
		 * Activity resource scopes and existing WebViews retain their configurations.
		 */
		@MainThread
		public fun fixWebViewLocale() {
			checkMainThread()
			(getInstance() as? AndroidLocaleEasy)?.reapplyResources()
		}
	}
}

/** Save the preference to follow the device's languages. */
@MainThread
public fun LocaleEasy.followSystem() {
	setPreference(LocalePreference.FollowSystem)
}
