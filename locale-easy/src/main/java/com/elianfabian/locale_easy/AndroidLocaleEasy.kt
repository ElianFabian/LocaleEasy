package com.elianfabian.locale_easy

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

internal class AndroidLocaleEasy(
	val application: Application,
) : LocaleEasy, Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {

	private val _preferences by lazy {
		application.getSharedPreferences(LOCALE_SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
	}

	private val _backstackActivities = CopyOnWriteArrayList<Activity>()

	private val _systemLocaleList = MutableStateFlow(getSystemLocaleList())
	override val systemLocaleList = _systemLocaleList.asStateFlow()

	private val _preference = MutableStateFlow(restoreLocalePreference(
		_preferences.getBoolean(KEY_FOLLOW_SYSTEM_LOCALE, true),
		_preferences.getString(KEY_LANGUAGES, null),
	))
	override val preference = _preference.asStateFlow()

	private val _isFollowingSystemLocale = MutableStateFlow(preference.value == LocalePreference.FollowSystem)
	override val isFollowingSystemLocale = _isFollowingSystemLocale.asStateFlow()

	private val _effectiveLocales = MutableStateFlow(preference.value.resolveLocales(systemLocaleList.value))
	override val effectiveLocales = _effectiveLocales.asStateFlow()


	init {
		updateResources(application, effectiveLocales.value)

		application.registerActivityLifecycleCallbacks(this)
		application.registerComponentCallbacks(this)
	}


	override var followSystemLocale: Boolean
		get() = preference.value == LocalePreference.FollowSystem
		set(value) {
			if (value) followSystem()
			else if (followSystemLocale) setLocales(effectiveLocales.value)
		}

	override fun localizedContext(base: Context): Context {
		return LocaleEasyContextWrapper(createContextConfiguration(base, effectiveLocales.value))
	}

	override fun setPreference(preference: LocalePreference) {
		val selection = preference.immutableCopy()
		if (selection == _preference.value) return
		_preferences.edit {
			putBoolean(KEY_FOLLOW_SYSTEM_LOCALE, selection == LocalePreference.FollowSystem)
			when (selection) {
				LocalePreference.FollowSystem -> remove(KEY_LANGUAGES)
				is LocalePreference.AppLocales -> putString(KEY_LANGUAGES, selection.locales.joinToString(",") { it.toLanguageTag() })
			}
		}
		_preference.value = selection
		_isFollowingSystemLocale.value = selection == LocalePreference.FollowSystem
		applyEffectiveLocales()
	}

	private fun applyEffectiveLocales() {
		val locales = preference.value.resolveLocales(systemLocaleList.value)
		if (locales == effectiveLocales.value) return
		// Publish before scheduling activity recreation so attachBaseContext sees the new selection.
		_effectiveLocales.value = locales
		updateResources(application, locales)
		refreshStartedActivities(locales)
	}

	@Suppress("DEPRECATION")
	private fun getSystemLocaleList(): List<Locale> {
		val systemConfiguration = Resources.getSystem().configuration
		return if (Build.VERSION.SDK_INT >= 24) {
			val localesCompat = ConfigurationCompat.getLocales(systemConfiguration)
			val locales = buildList {
				for (i in 0 until localesCompat.size()) {
					add(localesCompat[i] ?: return@buildList)
				}
			}
			locales.also {
				println("$$$ system locales: $it, adb: ${Settings.System.getString(application.contentResolver, "system_locales")} | ${LocaleListCompat.getDefault()}")
			}
		}
		else listOf(systemConfiguration.locale)
	}


	private fun refreshStartedActivities(localeList: List<Locale>) {
		_backstackActivities.reversed().forEach { activity ->
			updateResources(activity, localeList)
			activity.resetTitle() // TODO: test this
			if (activity is LocaleEasy.ViewCreatorComponent) {
				//val view = activity.createContentView()
				//activity.setContentView(view)
				//ActivityCompat.recreate(activity)
				activity.triggerTrueConfigChangeRecreate()
			}
			else {
				//ActivityCompat.recreate(activity)
				activity.triggerTrueConfigChangeRecreate()
			}
			if (activity is FragmentActivity) {
				refreshStartedFragments(activity.supportFragmentManager)
			}
		}
	}

	private fun refreshStartedFragments(fragmentManager: FragmentManager) {
		fragmentManager.fragments.forEach { fragment ->
			if (!fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && fragment !is NavHostFragment) {
				return@forEach
			}

			try {
				fragmentManager.beginTransaction()
					.detach(fragment)
					.commitNow()
				fragmentManager.beginTransaction()
					.attach(fragment)
					.commitNow()
			}
			catch (_: IllegalStateException) {
				fragmentManager.beginTransaction()
					.detach(fragment)
					.commitNowAllowingStateLoss()
				fragmentManager.beginTransaction()
					.attach(fragment)
					.commitNowAllowingStateLoss()
			}

			refreshStartedFragments(fragment.childFragmentManager)
		}
	}

	@RequiresApi(Build.VERSION_CODES.N)
	private fun LocaleList.toLocales(): List<Locale> {
		if (size() == 0) {
			return emptyList()
		}

		val localeList = this
		return buildList {
			for (i in 0 until localeList.size()) {
				add(localeList[i])
			}
		}
	}

	@Suppress("DEPRECATION")
	@SuppressLint("ObsoleteSdkInt")
	fun updateResources(context: Context, localeList: List<Locale>) {
		val firstLocale = localeList.first()

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			LocaleList.setDefault(LocaleList(*localeList.toTypedArray()))
		}
		else {
			Locale.setDefault(firstLocale)
		}

		val resources = context.resources

//		val currentLocale = if (Build.VERSION.SDK_INT >= 24) {
//			resources.configuration.locales[0]
//		}
//		else resources.configuration.locale
//
//		if (currentLocale == firstLocale && localeList.size == 1) {
//			return
//		}

		val newConfig = Configuration(resources.configuration).apply {
			when {
				Build.VERSION.SDK_INT >= 24 -> {
					setLocales(LocaleList(*localeList.toTypedArray()))
				}
				Build.VERSION.SDK_INT >= 17 -> {
					setLocale(firstLocale)
				}
				else -> {
					this.locale = firstLocale
				}
			}
		}

		resources.updateConfiguration(newConfig, resources.displayMetrics)
	}

	private fun createContextConfiguration(context: Context, localeList: List<Locale>): Context {
		val firstLocale = localeList.first()

		val resources = context.resources

//		val currentLocale = if (Build.VERSION.SDK_INT >= 24) {
//			resources.configuration.locales[0]
//		}
//		else resources.configuration.locale
//
//		if (currentLocale == firstLocale && localeList.size == 1) {
//			return
//		}

		val newConfig = Configuration(resources.configuration).apply {
			when {
				Build.VERSION.SDK_INT >= 24 -> {
					setLocales(LocaleList(*localeList.toTypedArray()))
				}
				Build.VERSION.SDK_INT >= 17 -> {
					setLocale(firstLocale)
				}
				else -> {
					this.locale = firstLocale
				}
			}
		}

		return context.createConfigurationContext(newConfig)
	}

	@Suppress("DEPRECATION")
	private fun Activity.resetTitle() {
		try {
			val info = if (Build.VERSION.SDK_INT >= 33) {
				packageManager.getActivityInfo(componentName, PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
			}
			else {
				packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
			}
			if (info.labelRes != 0) {
				setTitle(info.labelRes)
			}
		}
		catch (e: PackageManager.NameNotFoundException) {
			e.printStackTrace()
		}
	}

	override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
		super.onActivityPreCreated(activity, savedInstanceState)
		println("$$$ onActivityPreCreated1: $activity |  ${activity.resources.configuration.locales}")

		val appPackage = activity.packageName
		val activityClass = activity::class.java.name

		// I'm not sure this check is the best solution to check if an activity belongs to the developer's app
		if (activityClass.startsWith(appPackage)) {
			if (!activity.baseContext.hasLocaleEasyWrapper()) {
				val isDebuggableApp = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
				val errorMessage = """
					Activity $activityClass missing LocaleEasy configuration."
					"You must override attachBaseContext(newBase: Context) and wrap it using LocaleEasy."
				""".trimIndent()

				if (isDebuggableApp) {
					error(errorMessage)
				}
				else {
					Log.e("LocaleEasy", errorMessage)
				}
			}
		}

		if (!_isFollowingSystemLocale.value) {
			val localeList = effectiveLocales.value

			println("$$$ onActivityPreCreated2: $localeList")

			updateResources(activity, localeList)
		}

		println("$$$ onActivityPreCreated3: ${LocaleListCompat.getDefault()}")
	}

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		println("$$$ onActivityCreated: $activity |  ${activity.resources.configuration.locales}")
		_backstackActivities.add(activity)
	}

	override fun onActivityStarted(activity: Activity) {
		println("$$$ onActivityStarted: $activity |  ${activity.resources.configuration.locales}")
	}

	override fun onActivityResumed(activity: Activity) {
		println("$$$ onActivityResumed: $activity |  ${activity.resources.configuration.locales}")
	}

	override fun onActivityPaused(activity: Activity) {
		println("$$$ onActivityPaused: $activity |  ${activity.resources.configuration.locales}")
	}

	override fun onActivityStopped(activity: Activity) {
		println("$$$ onActivityStopped: $activity |  ${activity.resources.configuration.locales}")
	}

	override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
		println("$$$ onActivitySaveInstanceState: $activity")
	}

	override fun onActivityDestroyed(activity: Activity) {
		println("$$$ onActivityDestroyed: $activity |  ${activity.resources.configuration.locales}")
		_backstackActivities.remove(activity)
	}

	override fun onTrimMemory(level: Int) {
		// no-op
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		println("$$$ onConfigurationChanged: $newConfig | ${LocaleListCompat.getDefault()}")
		_systemLocaleList.value = getSystemLocaleList()
		// Reapply to application resources if Android replaced their configuration, but
		// only request a locale refresh when the resolved selection actually changes.
		updateResources(application, preference.value.resolveLocales(systemLocaleList.value))
		applyEffectiveLocales()
	}

	@Deprecated("Deprecated in Java")
	override fun onLowMemory() {
		// no-op
	}

//	private fun Activity.smoothRecreateActivity() {
//		val restartIntent = this.intent.apply {
//			addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
//		}
//
//		startActivity(restartIntent)
//		finish()
//
//		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
//			overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
//		} else {
//			@Suppress("DEPRECATION")
//			overridePendingTransition(0, 0)
//		}
//	}

//	fun Activity.forceRealConfigChange() {
//		val originalOrientation = this.requestedOrientation
//
//		// Toggle orientation to force a real OS-driven configuration change
//		this.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
//
//		Handler(Looper.getMainLooper()).postDelayed({
//			// Restore to original after the system processed the destruction/creation
//			this.requestedOrientation = originalOrientation
//		}, 1)
//	}

	private fun Activity.triggerTrueConfigChangeRecreate() {
//		try {
//			// 1. Access the hidden primitive boolean field inside the native Activity class
//			val changingConfigField: Field = Activity::class.java.getDeclaredField("mChangingConfigurations")
//			changingConfigField.isAccessible = true
//
//			// 2. Force it to true so the OS and Jetpack components think a hardware change is happening
//			changingConfigField.setBoolean(this, true)
//		} catch (e: Exception) {
//			// Fallback for strict environment restrictions or non-standard ROMs
//			e.printStackTrace()
//		}
//
//		// 3. Trigger the official platform recreation with the flag armed
		ActivityCompat.recreate(this)
	}

	fun Context.hasLocaleEasyWrapper(): Boolean {
		var currentContext: Context? = this
		while (currentContext is ContextWrapper) {
			if (currentContext is LocaleEasyContextWrapper) {
				return true
			}
			currentContext = currentContext.baseContext
		}
		return false
	}


	private class LocaleEasyContextWrapper(context: Context) : ContextWrapper(context)


	companion object {
		const val LOCALE_SHARED_PREFERENCES_NAME = "locale_easy_prefs"
		const val KEY_FOLLOW_SYSTEM_LOCALE = "KEY_FOLLOW_SYSTEM_LOCALE"
		const val KEY_LANGUAGES = "KEY_LANGUAGES"
	}
}
