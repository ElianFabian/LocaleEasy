package com.elianfabian.locale_easy

import android.app.Activity
import android.app.Application
import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.LocaleList
import android.view.LayoutInflater
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

internal class AndroidLocaleEasy(
	val application: Application,
) : LocaleEasy, Application.ActivityLifecycleCallbacks, ComponentCallbacks {
	private val preferences = application.getSharedPreferences(
		LOCALE_SHARED_PREFERENCES_NAME,
		Context.MODE_PRIVATE,
	)
	private val handler = Handler(Looper.getMainLooper())
	private val activities = IdentityHashMap<Activity, ActivityState>()
	private val pendingUpdates = ArrayDeque<() -> Unit>()
	private var applyingUpdates = false

	override val localeCatalog = LocaleCatalog.read(application)

	override val initialAppLocale: Locale?
		get() = preferences.getString(KEY_INITIAL_APP_LOCALE, null)?.let(::parseLocaleTag)

	private val localeState = MutableStateFlow(readLocaleState(readInitialPreference()))
	private val state = localeState.asStateFlow()
	override val systemLocaleList = state.project { it.systemLocales }
	override val preference = state.project { it.preference }
	override val effectiveLocaleList = state.project { it.effectiveLocales }
	override val selectedAndroidAppLocale = state.project { it.selectedAndroidAppLocale }
	override val androidSupportedLocaleList = state.project { it.androidSupportedLocales }

	private val localeReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			// A system locale change may leave the app's effective locales unchanged.
			onConfigurationChanged(application.resources.configuration)
		}
	}

	init {
		checkMainThread()
		// Normalize existing storage without recording the fresh-install system default as a choice.
		if (hasSavedPreference()) persist(preference.value)
		updateResources(application, effectiveLocaleList.value)
		application.registerActivityLifecycleCallbacks(this)
		application.registerComponentCallbacks(this)
		if (Build.VERSION.SDK_INT >= 33) {
			ContextCompat.registerReceiver(
				application,
				localeReceiver,
				IntentFilter(Intent.ACTION_LOCALE_CHANGED),
				ContextCompat.RECEIVER_NOT_EXPORTED,
			)
		}
	}

	override fun setInitialLocale(locale: Locale) {
		checkMainThread()
		val selection = LocalePreference.AppLocales(listOf(locale))
		applyUpdate {
			if (!hasSavedPreference()) applyPreference(selection, initialLocale = locale)
		}
	}

	override fun setPreference(preference: LocalePreference) {
		checkMainThread()
		val selection = preference.immutableCopy()
		applyUpdate { applyPreference(selection) }
	}

	@RequiresApi(34)
	override fun setAndroidSupportedLocaleList(locales: List<Locale>?) {
		checkMainThread()
		val selection = locales?.distinct()
		if (!selection.isNullOrEmpty()) LocalePreference.AppLocales(selection)
		applyUpdate {
			Api34.setSupportedLocales(application, selection)
			refreshAndroidLocales()
		}
	}

	override fun localizedContext(base: Context): Context {
		val current = state.value
		val locales = current.effectiveLocales
		val existing = base.localeEasyWrapper()
		val applied = if (Build.VERSION.SDK_INT >= 24) locales else locales.take(1)
		val baseMatches = configurationLocales(base.resources.configuration) == applied
		if (existing != null && existing.selection == current.preference && existing.locales == locales && baseMatches) {
			return base
		}
		val useBase = baseMatches && existing?.overridesLocales != true &&
			current.preference == LocalePreference.FollowSystem && localeCatalog.defaultLocale == null &&
			current.selectedAndroidAppLocale == null
		val localized = if (useBase) base else createLocalizedContext(base, locales)
		return LocaleEasyContextWrapper(localized, locales, current.preference, overridesLocales = !useBase)
	}

	override fun localizedContext(base: Context, locales: List<Locale>): Context {
		val orderedLocales = locales.distinct()
		val selection = LocalePreference.AppLocales(orderedLocales)
		return createLocalizedContext(base, selection.locales)
	}

	private fun createLocalizedContext(base: Context, locales: List<Locale>): Context {
		val override = configurationOverrides(base).apply { applyLocales(locales) }
		return base.createConfigurationContext(override)
	}

	/** Repair the legacy application resources after a WebView-provider configuration mutation. */
	fun reapplyResources() {
		checkMainThread()
		applyUpdate {
			val locales = state.value.effectiveLocales
			val applied = if (Build.VERSION.SDK_INT >= 24) locales else locales.take(1)
			if (configurationLocales(application.resources.configuration) != applied) {
				updateResources(application, locales)
			}
			// Scoped activity resources belong to their original selection until relaunch.
		}
	}

	private fun applyPreference(selection: LocalePreference, initialLocale: Locale? = null) {
		if (selection == state.value.preference) {
			// Selecting FollowSystem explicitly must survive a later initial-locale request.
			if (!hasSavedPreference()) persist(selection, initialLocale)
			return
		}
		val next = readLocaleState(selection)
		updateResources(application, next.effectiveLocales)
		persist(next.preference, initialLocale)
		localeState.value = next
		activities.forEach { (activity, state) -> scheduleRefresh(activity, state) }
	}

	private fun applyUpdate(update: () -> Unit) {
		pendingUpdates.addLast(update)
		if (applyingUpdates) return
		// A collector on the main thread can request another change during publication.
		applyingUpdates = true
		try {
			while (pendingUpdates.isNotEmpty()) pendingUpdates.removeFirst().invoke()
		} finally {
			pendingUpdates.clear()
			applyingUpdates = false
		}
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		applyUpdate {
			val current = state.value
			val next = readLocaleState(current.preference)
			updateResources(application, next.effectiveLocales)
			localeState.value = next
			if (current.effectiveLocales != next.effectiveLocales) {
				activities.forEach { (activity, activityState) ->
					if (activityState.overridesLocales) scheduleRefresh(activity, activityState)
				}
			}
		}
	}

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		// Only explicitly wrapped activities participate. No class/package-name guesses.
		val wrapper = activity.baseContext.localeEasyWrapper() ?: return
		activities[activity] = ActivityState(wrapper.locales, wrapper.selection, wrapper.overridesLocales)
	}

	override fun onActivityStarted(activity: Activity) {
		activities[activity]?.let { activityState ->
			val current = state.value
			activityState.started = true
			if (activityState.selection == LocalePreference.FollowSystem &&
				current.preference == LocalePreference.FollowSystem &&
				!activityState.overridesLocales &&
				configurationLocales(activity.resources.configuration) ==
					(if (Build.VERSION.SDK_INT >= 24) current.effectiveLocales else current.effectiveLocales.take(1))
			) {
				activityState.locales = current.effectiveLocales
			}
			scheduleRefresh(activity, activityState)
		}
	}

	override fun onActivityResumed(activity: Activity) {
		if (Build.VERSION.SDK_INT >= 33) applyUpdate { refreshAndroidLocales() }
	}

	private fun refreshAndroidLocales() {
		val current = state.value
		if (Build.VERSION.SDK_INT >= 33) {
			localeState.value = LocaleState(
				current.preference,
				current.systemLocales,
				localeCatalog,
				Api33.selectedAppLocale(application),
				Api33.supportedLocales(application),
			)
		}
	}

	override fun onActivityPaused(activity: Activity) = Unit

	override fun onActivityStopped(activity: Activity) {
		activities[activity]?.started = false
	}

	override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

	override fun onActivityDestroyed(activity: Activity) {
		activities.remove(activity)
	}

	@Deprecated("Deprecated in Android")
	override fun onLowMemory() = Unit

	private fun scheduleRefresh(activity: Activity, state: ActivityState) {
		if (!state.started || state.pending || state.recreating || state.matchesSelection()) return
		state.pending = true
		handler.post {
			state.pending = false
			if (activities[activity] !== state || !state.started || state.recreating ||
				activity.isFinishing || activity.isDestroyed || state.matchesSelection()
			) {
				return@post
			}
			state.recreating = true
			// One rebuild owner: never detach fragments or replace content on the outgoing activity.
			// Automatic framework locale relaunches exposed black frames in our window traces.
			ActivityCompat.recreate(activity)
		}
	}

	private fun ActivityState.matchesSelection(): Boolean {
		val current = state.value
		return selection == current.preference && locales == current.effectiveLocales
	}

	private class ActivityState(
		var locales: List<Locale>,
		val selection: LocalePreference,
		val overridesLocales: Boolean,
		var started: Boolean = false,
		var pending: Boolean = false,
		var recreating: Boolean = false,
	)

	private fun persist(selection: LocalePreference, initialLocale: Locale? = null) {
		preferences.edit {
			if (initialLocale != null) putString(KEY_INITIAL_APP_LOCALE, initialLocale.toLanguageTag())
			putBoolean(KEY_FOLLOW_SYSTEM_LOCALE, selection == LocalePreference.FollowSystem)
			when (selection) {
				LocalePreference.FollowSystem -> remove(KEY_LANGUAGES)
				is LocalePreference.AppLocales -> putString(
					KEY_LANGUAGES,
					selection.locales.joinToString(",") { it.toLanguageTag() },
				)
			}
		}
	}

	private fun hasSavedPreference(): Boolean =
		preferences.contains(KEY_FOLLOW_SYSTEM_LOCALE) || preferences.contains(KEY_LANGUAGES)

	private fun readInitialPreference(): LocalePreference =
		restoreLocalePreference(
			preferences.getBoolean(KEY_FOLLOW_SYSTEM_LOCALE, true),
			preferences.getString(KEY_LANGUAGES, null),
		)

	private fun readSystemLocales(): List<Locale> =
		if (Build.VERSION.SDK_INT >= 33) {
			Api33.systemLocales(application)
		} else {
			configurationLocales(Resources.getSystem().configuration)
		}

	private fun readLocaleState(selection: LocalePreference): LocaleState =
		LocaleState(
			preference = selection,
			systemLocales = readSystemLocales(),
			catalog = localeCatalog,
			selectedAndroidAppLocale = if (Build.VERSION.SDK_INT >= 33) Api33.selectedAppLocale(application) else null,
			androidSupportedLocales = if (Build.VERSION.SDK_INT >= 33) Api33.supportedLocales(application)
				else AndroidSupportedLocales.Unsupported,
		)

	@Suppress("DEPRECATION")
	private fun updateResources(context: Context, locales: List<Locale>) {
		if (Build.VERSION.SDK_INT >= 24) {
			LocaleList.setDefault(LocaleList(*locales.toTypedArray()))
		} else {
			Locale.setDefault(locales.first())
		}

		val resources = context.resources
		val configuration = Configuration(resources.configuration).apply { applyLocales(locales) }
		resources.updateConfiguration(configuration, resources.displayMetrics)
	}

	private fun Configuration.applyLocales(locales: List<Locale>) {
		if (Build.VERSION.SDK_INT >= 24) {
			setLocales(LocaleList(*locales.toTypedArray()))
		} else {
			setLocale(locales.first())
		}
	}

	private fun Context.localeEasyWrapper(): LocaleEasyContextWrapper? {
		var current: Context? = this
		while (current is ContextWrapper) {
			if (current is LocaleEasyContextWrapper) return current
			current = current.baseContext
		}
		return null
	}

	private class LocaleEasyContextWrapper(
		context: Context,
		val locales: List<Locale>,
		val selection: LocalePreference,
		val overridesLocales: Boolean,
	) : ContextWrapper(context) {
		private val inflater by lazy { LayoutInflater.from(baseContext).cloneInContext(this) }

		override fun getSystemService(name: String): Any? =
			if (name == LAYOUT_INFLATER_SERVICE) inflater else super.getSystemService(name)
	}

	@RequiresApi(33)
	private object Api33 {
		fun systemLocales(context: Context): List<Locale> = manager(context).systemLocales.toList()

		fun selectedAppLocale(context: Context): Locale? = manager(context).applicationLocales[0]

		fun supportedLocales(context: Context): AndroidSupportedLocales {
			val config = LocaleConfig(context)
			val locales = if (config.status == LocaleConfig.STATUS_SUCCESS) config.supportedLocales else null
			return locales?.let { AndroidSupportedLocales.Configured(it.toList()) }
				?: AndroidSupportedLocales.NotConfigured
		}

		private fun manager(context: Context): LocaleManager =
			context.getSystemService(LocaleManager::class.java)

		private fun LocaleList.toList(): List<Locale> = Collections.unmodifiableList(List(size()) { get(it) })
	}

	@RequiresApi(34)
	private object Api34 {
		fun setSupportedLocales(context: Context, locales: List<Locale>?) {
			context.getSystemService(LocaleManager::class.java).overrideLocaleConfig =
				locales?.let { LocaleConfig(LocaleList(*it.toTypedArray())) }
		}
	}

	companion object {
		const val LOCALE_SHARED_PREFERENCES_NAME = "locale_easy_prefs"
		const val KEY_FOLLOW_SYSTEM_LOCALE = "KEY_FOLLOW_SYSTEM_LOCALE"
		const val KEY_LANGUAGES = "KEY_LANGUAGES"
		private const val KEY_INITIAL_APP_LOCALE = "KEY_INITIAL_APP_LOCALE"

		private fun configurationLocales(configuration: Configuration): List<Locale> {
			val locales = ConfigurationCompat.getLocales(configuration)
			return Collections.unmodifiableList(List(locales.size()) { requireNotNull(locales[it]) })
		}
	}
}

private fun <T, R> StateFlow<T>.project(transform: (T) -> R): StateFlow<R> {
	val source = this
	val changes = map(transform).distinctUntilChanged()
	return object : StateFlow<R> {
		override val value: R get() = transform(source.value)
		override val replayCache: List<R> get() = listOf(value)

		override suspend fun collect(collector: FlowCollector<R>): Nothing {
			changes.collect(collector)
			error("StateFlow collection completed")
		}
	}
}

internal fun checkMainThread() {
	check(Looper.myLooper() == Looper.getMainLooper()) { "LocaleEasy mutations must run on the main thread" }
}
