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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

internal class AndroidLocaleEasy(
	val application: Application,
) : LocaleEasy, Application.ActivityLifecycleCallbacks, ComponentCallbacks {
	private val preferences = application.getSharedPreferences(LOCALE_SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
	private val handler = Handler(Looper.getMainLooper())
	private val activities = IdentityHashMap<Activity, ActivityState>()
	// Use localized contexts + a local relaunch on every API for now. In our API-36
	// captures, automatic LocaleManager recreation removed the old app surface before
	// the new one appeared, exposing black frames. The config-callback/local-relaunch
	// prototype avoided that gap, but still needs a consumer integration contract and
	// wider coverage. Keep framework recreation as an explicit, process-local diagnostic
	// opt-in only. See docs/framework-window-handoff-investigation.md in the repository.
	private var frameworkRecreation = false
	private val _systemLocaleList = MutableStateFlow(readSystemLocales())
	override val systemLocaleList = _systemLocaleList.asStateFlow()
	private val _preference = MutableStateFlow(readInitialPreference())
	override val preference = _preference.asStateFlow()
	private val _effectiveLocales = MutableStateFlow(preference.value.resolveLocales(systemLocaleList.value))
	override val effectiveLocales = _effectiveLocales.asStateFlow()
	private val _isFollowingSystemLocale = MutableStateFlow(preference.value == LocalePreference.FollowSystem)
	override val isFollowingSystemLocale = _isFollowingSystemLocale.asStateFlow()
	private val localeReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			// Preference can change without a resource diff, e.g. explicitly selecting
			// the same languages as the system. Observe the system broadcast as well.
			onConfigurationChanged(application.resources.configuration)
		}
	}

	init {
		checkMainThread()
		// Retire the old persisted comparison switch; an earlier experiment must not
		// silently enable framework recreation on the next process start.
		preferences.edit { remove(KEY_FRAMEWORK_RECREATION) }
		persist(preference.value)
		// Clear a previous framework override only when present, so it cannot compete
		// with our wrapped contexts. Normal startup/language updates do not set it.
		if (Build.VERSION.SDK_INT >= 33 && Api33.readPreference(application) != LocalePreference.FollowSystem) {
			Api33.setPreference(application, LocalePreference.FollowSystem)
		}
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
			if (frameworkRecreation == value) return
			frameworkRecreation = value
			if (Build.VERSION.SDK_INT >= 33 && value) {
				Api33.setPreference(application, preference.value)
			} else {
				if (Build.VERSION.SDK_INT >= 33) Api33.setPreference(application, LocalePreference.FollowSystem)
				updateResources(application, effectiveLocales.value)
			}
			// Backend changes also replace wrapped contexts, even if the language is unchanged.
			activities.forEach { (activity, state) -> scheduleRefresh(activity, state) }
		}

	override var followSystemLocale: Boolean
		get() = preference.value == LocalePreference.FollowSystem
		set(value) {
			checkMainThread()
			if (value) followSystem() else if (followSystemLocale) setLocales(effectiveLocales.value)
		}

	override fun setPreference(preference: LocalePreference) {
		checkMainThread()
		val selection = preference.immutableCopy()
		if (selection == this.preference.value) return
		persist(selection)
		publish(selection, readSystemLocales())
		if (Build.VERSION.SDK_INT >= 33 && frameworkRecreation) {
			// The framework owns configuration delivery and activity recreation on Android 13+.
			Api33.setPreference(application, selection)
		} else {
			updateResources(application, effectiveLocales.value)
			activities.forEach { (activity, state) -> scheduleRefresh(activity, state) }
		}
	}

	override fun localizedContext(base: Context): Context {
		val locales = effectiveLocales.value
		val localized = if (frameworkRecreation || preference.value == LocalePreference.FollowSystem) base
		else base.createConfigurationContext(configurationFor(base, locales))
		return LocaleEasyContextWrapper(localized, locales, preference.value, frameworkRecreation)
	}

	private fun publish(selection: LocalePreference, system: List<Locale>) {
		// State is resolved from user preference, never from mutable process defaults.
		_systemLocaleList.value = system
		_preference.value = selection
		_isFollowingSystemLocale.value = selection == LocalePreference.FollowSystem
		_effectiveLocales.value = selection.resolveLocales(system)
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

	private fun readInitialPreference(): LocalePreference {
		val saved = restoreLocalePreference(
			preferences.getBoolean(KEY_FOLLOW_SYSTEM_LOCALE, true), preferences.getString(KEY_LANGUAGES, null),
		)
		if (Build.VERSION.SDK_INT < 33) return saved
		// A previous framework owner may have changed in Android Settings while our
		// process was absent. Import that authoritative selection (including reset),
		// or a first-install OS selection, before clearing the override and old switch.
		val wasFrameworkOwner = preferences.getBoolean(KEY_FRAMEWORK_RECREATION, false)
		return if (!wasFrameworkOwner && preferences.contains(KEY_FOLLOW_SYSTEM_LOCALE)) saved
		else Api33.readPreference(application)
	}

	private fun readSystemLocales(): List<Locale> = if (Build.VERSION.SDK_INT >= 33) {
		Api33.systemLocales(application)
	} else configurationLocales(Resources.getSystem().configuration)

	override fun onConfigurationChanged(newConfig: Configuration) {
		val system = readSystemLocales()
		if (Build.VERSION.SDK_INT >= 33 && frameworkRecreation) {
			val selection = Api33.readPreference(application)
			persist(selection)
			publish(selection, system)
		} else {
			// Android owns recreation for system configuration changes. Do not request a
			// second recreation here. Custom wrapped contexts retain their locale override.
			publish(preference.value, system)
			updateResources(application, effectiveLocales.value)
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
		activities[activity]?.let { state ->
			state.started = true
			if (state.selection == preference.value && preference.value == LocalePreference.FollowSystem &&
				configurationLocales(activity.resources.configuration) == effectiveLocales.value) {
				state.locales = effectiveLocales.value
			}
			if (!frameworkRecreation || state.framework != frameworkRecreation) scheduleRefresh(activity, state)
		}
	}

	override fun onActivityStopped(activity: Activity) { activities[activity]?.started = false }
	override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
	override fun onActivityResumed(activity: Activity) = Unit
	override fun onActivityPaused(activity: Activity) = Unit
	override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
	@Deprecated("Deprecated in Android")
	override fun onLowMemory() = Unit

	/** Reapply native resources after a demonstrated WebView-provider configuration mutation. */
	fun reapplyResources() {
		checkMainThread()
		if (frameworkRecreation) return
		updateResources(application, effectiveLocales.value)
		activities.keys.forEach { updateResources(it, effectiveLocales.value) }
	}

	@Suppress("DEPRECATION")
	private fun updateResources(context: Context, locales: List<Locale>) {
		if (Build.VERSION.SDK_INT >= 24) LocaleList.setDefault(LocaleList(*locales.toTypedArray()))
		else Locale.setDefault(locales.first())
		val resources = context.resources
		resources.updateConfiguration(configurationFor(context, locales), resources.displayMetrics)
	}

	private fun configurationFor(context: Context, locales: List<Locale>): Configuration =
		Configuration(context.resources.configuration).apply {
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

	private fun ActivityState.matchesSelection(): Boolean = framework == frameworkRecreation &&
		selection == preference.value && locales == effectiveLocales.value

	private class ActivityState(var locales: List<Locale>, val selection: LocalePreference, val framework: Boolean, var started: Boolean = false,
		var pending: Boolean = false, var recreating: Boolean = false)

	@RequiresApi(33)
	private object Api33 {
		private fun manager(context: Context): LocaleManager = context.getSystemService(LocaleManager::class.java)
		fun systemLocales(context: Context): List<Locale> = manager(context).systemLocales.toList()
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
		private fun configurationLocales(configuration: Configuration): List<Locale> {
			val locales = ConfigurationCompat.getLocales(configuration)
			return Collections.unmodifiableList(List(locales.size()) { requireNotNull(locales[it]) })
		}
	}
}

internal fun checkMainThread() {
	check(Looper.myLooper() == Looper.getMainLooper()) { "LocaleEasy mutations must run on the main thread" }
}
