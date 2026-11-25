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
import android.os.LocaleList
import android.os.Looper
import android.view.LayoutInflater
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

internal class AndroidLocaleEasy(
	val application: Application,
) : LocaleEasy, Application.ActivityLifecycleCallbacks, ComponentCallbacks {

	private val _preferences = application.getSharedPreferences(
		LOCALE_SHARED_PREFERENCES_NAME,
		Context.MODE_PRIVATE,
	)
	private val _handler = Handler(Looper.getMainLooper())
	private val _activities = IdentityHashMap<Activity, ActivityState>()
	private val _pendingUpdates = ArrayDeque<() -> Unit>()
	private var _applyingUpdates = false

	override val localeCatalog = LocaleCatalog.read(application)

	override val initialAppLocale: Locale?
		get() = _preferences.getString(KEY_INITIAL_APP_LOCALE, null)?.let(::parseLocaleTag)

	private val _localeState = MutableStateFlow(readLocaleState(readInitialPreference()))

	override val systemLocaleList = _localeState.project { it.systemLocales }
	override val preference = _localeState.project { it.preference }
	override val effectiveLocaleList = _localeState.project { it.effectiveLocales }
	override val selectedAndroidAppLocale = _localeState.project { it.selectedAndroidAppLocale }
	override val androidSupportedLocaleList = _localeState.project { it.androidSupportedLocales }

	private val _localeReceiver = object : BroadcastReceiver() {
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
				_localeReceiver,
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
		val current = _localeState.value
		val locales = current.effectiveLocales
		val existing = base.localeEasyWrapper()
		val applied = if (Build.VERSION.SDK_INT >= 24) locales else locales.take(1)
		val baseMatches = configurationLocales(base.resources.configuration) == applied

		if (existing != null && existing.selection == current.preference && existing.locales == locales && baseMatches) {
			return base
		}

		val useBase = baseMatches && existing?.overridesLocales != true
			&& current.preference == LocalePreference.FollowSystem && localeCatalog.defaultLocale == null
			&& current.selectedAndroidAppLocale == null
		val localized = if (useBase) base else createLocalizedContext(base, locales)

		return LocaleEasyContextWrapper(
			context = localized,
			locales = locales,
			selection = current.preference,
			overridesLocales = !useBase,
		)
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
			val locales = _localeState.value.effectiveLocales
			val applied = if (Build.VERSION.SDK_INT >= 24) locales else locales.take(1)
			if (configurationLocales(application.resources.configuration) != applied) {
				updateResources(application, locales)
			}
			// Scoped activity resources belong to their original selection until relaunch.
		}
	}

	private fun applyPreference(selection: LocalePreference, initialLocale: Locale? = null) {
		if (selection == _localeState.value.preference) {
			// Selecting FollowSystem explicitly must survive a later initial-locale request.
			if (!hasSavedPreference()) persist(selection, initialLocale)
			return
		}

		val next = readLocaleState(selection)
		updateResources(application, next.effectiveLocales)
		persist(next.preference, initialLocale)

		_localeState.value = next
		_activities.forEach { (activity, state) -> scheduleRefresh(activity, state) }
	}

	private fun applyUpdate(update: () -> Unit) {
		_pendingUpdates.addLast(update)
		if (_applyingUpdates) {
			return
		}
		// A collector on the main thread can request another change during publication.
		_applyingUpdates = true
		try {
			while (_pendingUpdates.isNotEmpty()) _pendingUpdates.removeFirst().invoke()
		}
		finally {
			_pendingUpdates.clear()
			_applyingUpdates = false
		}
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		applyUpdate {
			val current = _localeState.value
			val next = readLocaleState(current.preference)
			updateResources(application, next.effectiveLocales)
			_localeState.value = next

			if (current.effectiveLocales != next.effectiveLocales) {
				_activities.forEach { (activity, activityState) ->
					if (activityState.overridesLocales) scheduleRefresh(activity, activityState)
				}
			}
		}
	}

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		// Only explicitly wrapped activities participate. No class/package-name guesses.
		val wrapper = activity.baseContext.localeEasyWrapper() ?: return
		_activities[activity] = ActivityState(wrapper.locales, wrapper.selection, wrapper.overridesLocales)
	}

	override fun onActivityStarted(activity: Activity) {
		_activities[activity]?.let { activityState ->
			val current = _localeState.value
			activityState.started = true

			if (
				activityState.selection == LocalePreference.FollowSystem
				&& current.preference == LocalePreference.FollowSystem
				&& !activityState.overridesLocales
				&& configurationLocales(activity.resources.configuration) == (if (Build.VERSION.SDK_INT >= 24) current.effectiveLocales else current.effectiveLocales.take(1))
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
		val current = _localeState.value
		if (Build.VERSION.SDK_INT >= 33) {
			_localeState.value = LocaleState(
				preference = current.preference,
				systemLocales = current.systemLocales,
				catalog = localeCatalog,
				selectedAndroidAppLocale = Api33.selectedAppLocale(application),
				androidSupportedLocales = Api33.supportedLocales(application),
			)
		}
	}

	override fun onActivityPaused(activity: Activity) {
		// no-op
	}

	override fun onActivityStopped(activity: Activity) {
		_activities[activity]?.started = false
	}

	override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
		// no-op
	}

	override fun onActivityDestroyed(activity: Activity) {
		_activities.remove(activity)
	}

	@Deprecated("Deprecated in Android")
	override fun onLowMemory() {
		// no-op
	}

	private fun scheduleRefresh(activity: Activity, state: ActivityState) {
		if (!state.started || state.pending || state.recreating || state.matchesSelection()) return
		state.pending = true
		_handler.post {
			state.pending = false
			if (_activities[activity] !== state || !state.started || state.recreating ||
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
		val current = _localeState.value
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
		_preferences.edit {
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

	private fun hasSavedPreference(): Boolean = _preferences.contains(KEY_FOLLOW_SYSTEM_LOCALE) || _preferences.contains(KEY_LANGUAGES)

	private fun readInitialPreference(): LocalePreference =
		restoreLocalePreference(
			followSystem = _preferences.getBoolean(KEY_FOLLOW_SYSTEM_LOCALE, true),
			storedLocales = _preferences.getString(KEY_LANGUAGES, null),
		)

	private fun readSystemLocales(): List<Locale> {
		if (Build.VERSION.SDK_INT >= 33) {
			return Api33.systemLocales(application)
		}
		return configurationLocales(Resources.getSystem().configuration)
	}

	private fun readLocaleState(selection: LocalePreference): LocaleState {
		return LocaleState(
			preference = selection,
			systemLocales = readSystemLocales(),
			catalog = localeCatalog,
			selectedAndroidAppLocale = if (Build.VERSION.SDK_INT >= 33) Api33.selectedAppLocale(application) else null,
			androidSupportedLocales = if (Build.VERSION.SDK_INT >= 33) Api33.supportedLocales(application)
			else AndroidSupportedLocales.Unsupported,
		)
	}

	@Suppress("DEPRECATION")
	private fun updateResources(context: Context, locales: List<Locale>) {
		if (Build.VERSION.SDK_INT >= 24) {
			LocaleList.setDefault(LocaleList(*locales.toTypedArray()))
		}
		else Locale.setDefault(locales.first())

		val resources = context.resources
		val configuration = Configuration(resources.configuration).apply { applyLocales(locales) }
		resources.updateConfiguration(configuration, resources.displayMetrics)
	}

	private fun Configuration.applyLocales(locales: List<Locale>) {
		if (Build.VERSION.SDK_INT >= 24) {
			setLocales(LocaleList(*locales.toTypedArray()))
		}
		else setLocale(locales.first())
	}

	private fun Context.localeEasyWrapper(): LocaleEasyContextWrapper? {
		var current: Context? = this
		while (current is ContextWrapper) {
			if (current is LocaleEasyContextWrapper) {
				return current
			}
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

		override fun getSystemService(name: String): Any? {
			if (name == LAYOUT_INFLATER_SERVICE) {
				return inflater
			}
			return super.getSystemService(name)
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


@RequiresApi(33)
private object Api33 {
	fun systemLocales(context: Context): List<Locale> = manager(context).systemLocales.toList()

	fun selectedAppLocale(context: Context): Locale? = manager(context).applicationLocales[0]

	fun supportedLocales(context: Context): AndroidSupportedLocales {
		val config = LocaleConfig(context)
		val locales = if (config.status == LocaleConfig.STATUS_SUCCESS) config.supportedLocales else null

		if (locales == null) {
			return AndroidSupportedLocales.NotConfigured
		}

		return AndroidSupportedLocales.Configured(locales.toList())
	}

	private fun manager(context: Context): LocaleManager = context.getSystemService(LocaleManager::class.java)

	private fun LocaleList.toList(): List<Locale> = Collections.unmodifiableList(List(size()) { get(it) })
}

@RequiresApi(34)
private object Api34 {
	fun setSupportedLocales(context: Context, locales: List<Locale>?) {
		val manager = context.getSystemService(LocaleManager::class.java)
		manager.overrideLocaleConfig = locales?.let { LocaleConfig(LocaleList(*it.toTypedArray())) }
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
