package com.elianfabian.locale_easy

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.content.ContextWrapper
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
import androidx.core.content.edit
import androidx.core.content.ContextCompat
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
	private val preferences = application.getSharedPreferences(LOCALE_SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
	private val handler = Handler(Looper.getMainLooper())
	private val activities = IdentityHashMap<Activity, ActivityState>()
	private val pendingUpdates = ArrayDeque<() -> Unit>()
	private var applyingUpdates = false
	override val localeCatalog = LocaleCatalog.read(application)
	override val initialAppLocale: Locale?
		get() = preferences.getString(KEY_INITIAL_APP_LOCALE, null)?.let(::parseLocaleTag)

	private val localeState = MutableStateFlow(resolveState(readInitialPreference(), readSystemLocales()))
	override val state = localeState.asStateFlow()
	override val systemLocaleList = state.project { it.systemLocales }
	override val preference = state.project { it.preference }
	override val effectiveLocales = state.project { it.effectiveLocales }
	private val localeReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			// A system locale change may leave the app's effective locales unchanged.
			onConfigurationChanged(application.resources.configuration)
		}
	}

	init {
		checkMainThread()
		val migrateFrameworkOverride = ownsFrameworkOverride()
		// Normalize existing storage without recording the fresh-install system default as a choice.
		if (hasSavedPreference() || migrateFrameworkOverride) persist(preference.value)
		// Only clear an override left by a LocaleEasy framework experiment.
		if (Build.VERSION.SDK_INT >= 33 && migrateFrameworkOverride) {
			Api33.clearOverride(application)
		}
		clearFrameworkOwnership()
		updateResources(application, effectiveLocales.value)
		application.registerActivityLifecycleCallbacks(this)
		application.registerComponentCallbacks(this)
		if (Build.VERSION.SDK_INT >= 33) {
			ContextCompat.registerReceiver(application, localeReceiver, IntentFilter(Intent.ACTION_LOCALE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
		}
	}

	override fun setPreference(preference: LocalePreference) {
		checkMainThread()
		val selection = preference.immutableCopy()
		applyUpdate { applyPreference(selection) }
	}

	override fun setInitialLocale(locale: Locale) {
		checkMainThread()
		val selection = LocalePreference.AppLocales(listOf(locale))
		applyUpdate {
			if (!hasSavedPreference()) applyPreference(selection, initialLocale = locale)
		}
	}

	private fun applyPreference(selection: LocalePreference, initialLocale: Locale? = null) {
		if (selection == state.value.preference) {
			// Selecting FollowSystem explicitly must survive a later initial-locale request.
			if (!hasSavedPreference()) persist(selection, initialLocale)
			return
		}
		val next = resolveState(selection, readSystemLocales())
		updateResources(application, next.effectiveLocales)
		persist(next.preference, initialLocale)
		localeState.value = next
		activities.forEach { (activity, state) -> scheduleRefresh(activity, state) }
	}

	override fun localizedContext(base: Context): Context {
		val current = state.value
		// A host's platform override can coexist with locally wrapped activities.
		// Explicitly apply system locales for FollowSystem when the platform base differs.
		val useBase = current.preference == LocalePreference.FollowSystem &&
			localeCatalog.defaultLocaleOrNull == null &&
			(Build.VERSION.SDK_INT < 33 || !Api33.hasOverride(application))
		val localized = if (useBase) base else base.createConfigurationContext(Configuration().apply {
			// A fresh Configuration defaults to font scale 1 on older Android releases.
			fontScale = 0f
			applyLocales(current.effectiveLocales)
		})
		return LocaleEasyContextWrapper(localized, current.effectiveLocales, current.preference)
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

	private fun persist(selection: LocalePreference, initialLocale: Locale? = null) {
		preferences.edit {
			if (initialLocale != null) putString(KEY_INITIAL_APP_LOCALE, initialLocale.toLanguageTag())
			putBoolean(KEY_FOLLOW_SYSTEM_LOCALE, selection == LocalePreference.FollowSystem)
			when (selection) {
				LocalePreference.FollowSystem -> remove(KEY_LANGUAGES)
				is LocalePreference.AppLocales -> putString(KEY_LANGUAGES, selection.locales.joinToString(",") { it.toLanguageTag() })
			}
		}
	}

	private fun hasSavedPreference(): Boolean =
		preferences.contains(KEY_FOLLOW_SYSTEM_LOCALE) || preferences.contains(KEY_LANGUAGES)

	private fun readInitialPreference(): LocalePreference {
		val saved = restoreLocalePreference(
			preferences.getBoolean(KEY_FOLLOW_SYSTEM_LOCALE, true), preferences.getString(KEY_LANGUAGES, null),
		)
		return if (Build.VERSION.SDK_INT >= 33 && ownsFrameworkOverride()) Api33.readPreference(application) else saved
	}

	private fun ownsFrameworkOverride(): Boolean =
		preferences.getBoolean(KEY_FRAMEWORK_RECREATION, false) ||
			preferences.getBoolean(KEY_FRAMEWORK_DIAGNOSTIC_OWNER, false)

	private fun clearFrameworkOwnership() {
		if (ownsFrameworkOverride()) {
			preferences.edit {
				remove(KEY_FRAMEWORK_RECREATION)
				remove(KEY_FRAMEWORK_DIAGNOSTIC_OWNER)
			}
		}
	}

	private fun readSystemLocales(): List<Locale> = if (Build.VERSION.SDK_INT >= 33) {
		Api33.systemLocales(application)
	} else configurationLocales(Resources.getSystem().configuration)

	private fun resolveState(selection: LocalePreference, system: List<Locale>): LocaleState =
		LocaleState(selection, system, localeCatalog)

	override fun onConfigurationChanged(newConfig: Configuration) {
		applyUpdate {
			val current = state.value
			val system = readSystemLocales()
			val next = resolveState(current.preference, system)
			updateResources(application, next.effectiveLocales)
			localeState.value = next
			if (localeCatalog.defaultLocaleOrNull != null &&
				current.effectiveLocales != next.effectiveLocales) {
				activities.forEach { (activity, activityState) -> scheduleRefresh(activity, activityState) }
			}
		}
	}

	private fun scheduleRefresh(activity: Activity, state: ActivityState) {
		if (!state.started || state.pending || state.recreating || state.matchesSelection()) return
		state.pending = true
		handler.post {
			state.pending = false
			if (activities[activity] !== state || !state.started || state.recreating ||
				activity.isFinishing || activity.isDestroyed || state.matchesSelection()) return@post
			state.recreating = true
			// One rebuild owner: never detach fragments or replace content on the outgoing activity.
			// Automatic framework locale relaunches exposed black frames in our window traces.
			ActivityCompat.recreate(activity)
		}
	}

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		// Only explicitly wrapped activities participate. No class/package-name guesses.
		val wrapper = activity.baseContext.localeEasyWrapper() ?: return
		activities[activity] = ActivityState(wrapper.locales, wrapper.selection)
	}

	override fun onActivityStarted(activity: Activity) {
		activities[activity]?.let { activityState ->
			val current = state.value
			activityState.started = true
			if (activityState.selection == LocalePreference.FollowSystem && current.preference == LocalePreference.FollowSystem &&
				localeCatalog.defaultLocaleOrNull == null &&
				configurationLocales(activity.resources.configuration) == current.effectiveLocales) {
				activityState.locales = current.effectiveLocales
			}
			scheduleRefresh(activity, activityState)
		}
	}

	override fun onActivityStopped(activity: Activity) { activities[activity]?.started = false }
	override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
	override fun onActivityResumed(activity: Activity) = Unit
	override fun onActivityPaused(activity: Activity) = Unit
	override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
	@Deprecated("Deprecated in Android")
	override fun onLowMemory() = Unit

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

	@Suppress("DEPRECATION")
	private fun updateResources(context: Context, locales: List<Locale>) {
		if (Build.VERSION.SDK_INT >= 24) LocaleList.setDefault(LocaleList(*locales.toTypedArray()))
		else Locale.setDefault(locales.first())
		val resources = context.resources
		val configuration = Configuration(resources.configuration).apply { applyLocales(locales) }
		resources.updateConfiguration(configuration, resources.displayMetrics)
	}

	private fun Configuration.applyLocales(locales: List<Locale>) {
		if (Build.VERSION.SDK_INT >= 24) setLocales(LocaleList(*locales.toTypedArray()))
		else setLocale(locales.first())
	}

	private fun Context.localeEasyWrapper(): LocaleEasyContextWrapper? {
		var current: Context? = this
		while (current is ContextWrapper) {
			if (current is LocaleEasyContextWrapper) return current
			current = current.baseContext
		}
		return null
	}

	private class LocaleEasyContextWrapper(context: Context, val locales: List<Locale>, val selection: LocalePreference) : ContextWrapper(context) {
		private val inflater by lazy { LayoutInflater.from(baseContext).cloneInContext(this) }
		override fun getSystemService(name: String): Any? =
			if (name == LAYOUT_INFLATER_SERVICE) inflater else super.getSystemService(name)
	}

	private fun ActivityState.matchesSelection(): Boolean {
		val current = state.value
		return selection == current.preference && locales == current.effectiveLocales
	}

	private class ActivityState(var locales: List<Locale>, val selection: LocalePreference, var started: Boolean = false,
		var pending: Boolean = false, var recreating: Boolean = false)

	@RequiresApi(33)
	private object Api33 {
		private fun manager(context: Context): LocaleManager = context.getSystemService(LocaleManager::class.java)
		fun systemLocales(context: Context): List<Locale> = manager(context).systemLocales.toList()
		fun hasOverride(context: Context): Boolean = !manager(context).applicationLocales.isEmpty
		fun readPreference(context: Context): LocalePreference {
			val locales = manager(context).applicationLocales.toList()
			return if (locales.isEmpty()) LocalePreference.FollowSystem else LocalePreference.AppLocales(locales).immutableCopy()
		}
		fun clearOverride(context: Context) {
			manager(context).applicationLocales = LocaleList.getEmptyLocaleList()
		}
		private fun LocaleList.toList(): List<Locale> = Collections.unmodifiableList(List(size()) { get(it) })
	}

	companion object {
		const val LOCALE_SHARED_PREFERENCES_NAME = "locale_easy_prefs"
		const val KEY_FOLLOW_SYSTEM_LOCALE = "KEY_FOLLOW_SYSTEM_LOCALE"
		const val KEY_LANGUAGES = "KEY_LANGUAGES"
		private const val KEY_INITIAL_APP_LOCALE = "KEY_INITIAL_APP_LOCALE"
		private const val KEY_FRAMEWORK_RECREATION = "KEY_FRAMEWORK_RECREATION"
		private const val KEY_FRAMEWORK_DIAGNOSTIC_OWNER = "KEY_FRAMEWORK_DIAGNOSTIC_OWNER"
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
