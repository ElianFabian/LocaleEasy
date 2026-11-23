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
	// Use localized contexts + a local relaunch on every API for now. In our API-36
	// captures, automatic LocaleManager recreation removed the old app surface before
	// the new one appeared, exposing black frames. The config-callback/local-relaunch
	// prototype avoided that gap, but still needs a consumer integration contract and
	// wider coverage. Keep framework recreation as an explicit, process-local diagnostic
	// opt-in only. See docs/framework-window-handoff-investigation.md in the repository.
	@Volatile private var frameworkRecreation = false
	private val localeState = MutableStateFlow(LocaleState(readInitialPreference(), readSystemLocales()))
	override val state = localeState.asStateFlow()
	override val systemLocaleList = state.project { it.systemLocales }
	override val preference = state.project { it.preference }
	override val effectiveLocales = state.project { it.effectiveLocales }
	override val isFollowingSystemLocale = state.project { it.preference == LocalePreference.FollowSystem }
	private val localeReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			// Preference can change without a resource diff, e.g. explicitly selecting
			// the same languages as the system. Observe the system broadcast as well.
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
			Api33.setPreference(application, LocalePreference.FollowSystem)
		}
		clearFrameworkOwnership()
		updateResources(application, effectiveLocales.value)
		application.registerActivityLifecycleCallbacks(this)
		application.registerComponentCallbacks(this)
		if (Build.VERSION.SDK_INT >= 33) {
			ContextCompat.registerReceiver(application, localeReceiver, IntentFilter(Intent.ACTION_LOCALE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
		}
	}

	override var useFrameworkLocaleRecreation: Boolean
		get() = frameworkRecreation
		set(value) {
			checkMainThread()
			require(!value || Build.VERSION.SDK_INT >= 33) { "Framework locale recreation requires API 33+" }
			applyUpdate {
				if (frameworkRecreation == value) return@applyUpdate
				val current = state.value
				if (Build.VERSION.SDK_INT >= 33 && value) {
					Api33.setPreference(application, current.preference)
					preferences.edit { putBoolean(KEY_FRAMEWORK_DIAGNOSTIC_OWNER, true) }
				} else {
					if (Build.VERSION.SDK_INT >= 33) Api33.setPreference(application, LocalePreference.FollowSystem)
					clearFrameworkOwnership()
					updateResources(application, current.effectiveLocales)
				}
				frameworkRecreation = value
				// Backend changes also replace wrapped contexts, even if the language is unchanged.
				activities.forEach { (activity, state) -> scheduleRefresh(activity, state) }
			}
		}

	override var followSystemLocale: Boolean
		get() = preference.value == LocalePreference.FollowSystem
		set(value) {
			checkMainThread()
			applyUpdate {
				val current = state.value
				if (value) applyPreference(LocalePreference.FollowSystem)
				else if (current.preference == LocalePreference.FollowSystem) {
					applyPreference(LocalePreference.AppLocales(current.effectiveLocales))
				}
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
			if (!hasSavedPreference()) applyPreference(selection)
		}
	}

	private fun applyPreference(selection: LocalePreference) {
		if (selection == state.value.preference) {
			// Selecting FollowSystem explicitly must survive a later initial-locale request.
			if (!hasSavedPreference()) persist(selection)
			return
		}
		val next = LocaleState(selection, readSystemLocales())
		if (Build.VERSION.SDK_INT >= 33 && frameworkRecreation) {
			// The framework owns configuration delivery and activity recreation on Android 13+.
			Api33.setPreference(application, next.preference)
		} else {
			updateResources(application, next.effectiveLocales)
		}
		persist(next.preference)
		localeState.value = next
		if (!frameworkRecreation) activities.forEach { (activity, state) -> scheduleRefresh(activity, state) }
	}

	override fun localizedContext(base: Context): Context {
		val current = state.value
		val framework = frameworkRecreation
		// A host's platform override can coexist with locally wrapped activities.
		// Explicitly apply system locales for FollowSystem when the platform base differs.
		val useBase = framework || current.preference == LocalePreference.FollowSystem &&
			(Build.VERSION.SDK_INT < 33 || !Api33.hasOverride(application))
		val localized = if (useBase) base else base.createConfigurationContext(Configuration().apply {
			// A fresh Configuration defaults to font scale 1 on older Android releases.
			fontScale = 0f
			applyLocales(current.effectiveLocales)
		})
		return LocaleEasyContextWrapper(localized, current.effectiveLocales, current.preference, framework)
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

	private fun persist(selection: LocalePreference) {
		preferences.edit {
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

	override fun onConfigurationChanged(newConfig: Configuration) {
		applyUpdate {
			val current = state.value
			val system = readSystemLocales()
			val next = if (Build.VERSION.SDK_INT >= 33 && frameworkRecreation) {
				val selection = Api33.readPreference(application)
				if (selection != current.preference) persist(selection)
				LocaleState(selection, system)
			} else {
				// Android owns recreation for system configuration changes.
				LocaleState(current.preference, system).also { updateResources(application, it.effectiveLocales) }
			}
			localeState.value = next
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
			ActivityCompat.recreate(activity)
		}
	}

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		// Only explicitly wrapped activities participate. No class/package-name guesses.
		val wrapper = activity.baseContext.localeEasyWrapper() ?: return
		activities[activity] = ActivityState(wrapper.locales, wrapper.selection, wrapper.framework)
	}

	override fun onActivityStarted(activity: Activity) {
		activities[activity]?.let { activityState ->
			val current = state.value
			activityState.started = true
			if (activityState.selection == LocalePreference.FollowSystem && current.preference == LocalePreference.FollowSystem &&
				configurationLocales(activity.resources.configuration) == current.effectiveLocales) {
				activityState.locales = current.effectiveLocales
			}
			if (!frameworkRecreation || activityState.framework != frameworkRecreation) scheduleRefresh(activity, activityState)
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
			if (frameworkRecreation) return@applyUpdate
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

	private class LocaleEasyContextWrapper(context: Context, val locales: List<Locale>, val selection: LocalePreference,
		val framework: Boolean) : ContextWrapper(context) {
		private val inflater by lazy { LayoutInflater.from(baseContext).cloneInContext(this) }
		override fun getSystemService(name: String): Any? =
			if (name == LAYOUT_INFLATER_SERVICE) inflater else super.getSystemService(name)
	}

	private fun ActivityState.matchesSelection(): Boolean {
		val current = state.value
		return framework == frameworkRecreation && selection == current.preference && locales == current.effectiveLocales
	}

	private class ActivityState(var locales: List<Locale>, val selection: LocalePreference, val framework: Boolean, var started: Boolean = false,
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
		fun setPreference(context: Context, selection: LocalePreference) {
			val locales = (selection as? LocalePreference.AppLocales)?.locales.orEmpty()
			manager(context).applicationLocales = LocaleList(*locales.toTypedArray())
		}
		private fun LocaleList.toList(): List<Locale> = Collections.unmodifiableList(List(size()) { get(it) })
	}

	companion object {
		const val LOCALE_SHARED_PREFERENCES_NAME = "locale_easy_prefs"
		const val KEY_FOLLOW_SYSTEM_LOCALE = "KEY_FOLLOW_SYSTEM_LOCALE"
		const val KEY_LANGUAGES = "KEY_LANGUAGES"
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
