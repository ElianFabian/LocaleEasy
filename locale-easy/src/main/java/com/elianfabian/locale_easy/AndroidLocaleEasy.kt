package com.elianfabian.locale_easy

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
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

	private val _isFollowingSystemLocale = MutableStateFlow(_preferences.getBoolean(KEY_FOLLOW_SYSTEM_LOCALE, true))
	override val isFollowingSystemLocale = _isFollowingSystemLocale.asStateFlow()

	private val _systemLocaleList = MutableStateFlow(getSystemLocaleList())
	override val systemLocaleList = _systemLocaleList.asStateFlow()

	private val _appLocaleList = MutableStateFlow(getAppLocaleList())
	override val appLocaleList = _appLocaleList.asStateFlow()


	init {
		if (!_isFollowingSystemLocale.value) {
			val localeList = getPersistedAppLocaleList()

			println("$$$ AndroidLocaleEasy init1: $localeList")

			updateResources(application, localeList)
		}
		println("$$$ AndroidLocaleEasy init2: ${getAppLocaleList()}")

		application.registerActivityLifecycleCallbacks(this)
		application.registerComponentCallbacks(this)
	}


	override var followSystemLocale: Boolean
		get() = _isFollowingSystemLocale.value
		set(value) {
			persistFollowSystemLocale(value)
		}

	override fun setAppLocale(locale: Locale) {
		setAppLocaleList(listOf(locale))
	}

	override fun setAppLocaleList(localeList: List<Locale>) {
		persistFollowSystemLocale(false)
		setAppLocaleListInternal(localeList)
	}

	override fun localizedContext(base: Context): Context {
		return createContextConfiguration(base, appLocaleList.value)
	}

	private fun setAppLocaleListInternal(localeList: List<Locale>) {
//		if (localeList == _appLocaleList.value) {
//			// It seems reasonable to skip updating the language when we set it to the same previous value
//			return
//		}
		// I prefer avoiding this since the developer is then forced to use AppCompactActivity
		//AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(*localeList.toTypedArray()))
		_preferences.edit {
			putString(KEY_LANGUAGES, localeList.joinToString(","))
		}
		updateResources(application, localeList)
		refreshStartedActivities(localeList)
		_appLocaleList.value = getAppLocaleList()
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

	private fun getAppLocaleList(): List<Locale> {
		return if (Build.VERSION.SDK_INT >= 24) {
			LocaleList.getDefault().toLocales().also {
				println("$$$ appLocales: $it | ${LocaleListCompat.getDefault()}")
			}
		}
		else listOf(Locale.getDefault())
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

	private fun persistFollowSystemLocale(value: Boolean) {
		_preferences.edit { putBoolean(KEY_FOLLOW_SYSTEM_LOCALE, value) }
		_isFollowingSystemLocale.value = value
		if (value) {
			setAppLocaleListInternal(getSystemLocaleList())
		}
	}

	private fun getPersistedAppLocaleList(): List<Locale> {
		return _preferences.getString(KEY_LANGUAGES, null)
			.orEmpty()
			.split(",")
			.map { Locale.forLanguageTag(it) }
	}

	override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
		super.onActivityPreCreated(activity, savedInstanceState)
		println("$$$ onActivityPreCreated1: $activity |  ${activity.resources.configuration.locales}")

		if (!_isFollowingSystemLocale.value) {
			val localeList = getPersistedAppLocaleList()

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
		// preservers language after a configuration change (there must be a better way to do this)
			val localeList = getPersistedAppLocaleList()

			println("$$$ AndroidLocaleEasy init3: $localeList")

			updateResources(application, localeList)
			refreshStartedActivities(localeList)

		_appLocaleList.value = if (_isFollowingSystemLocale.value) {
			getSystemLocaleList()
		}
		else {
			updateResources(application, getPersistedAppLocaleList())
			getAppLocaleList()
		}
		_systemLocaleList.value = getSystemLocaleList()
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

	fun Activity.triggerTrueConfigChangeRecreate() {
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

	companion object {
		const val LOCALE_SHARED_PREFERENCES_NAME = "locale_easy_prefs"
		const val KEY_FOLLOW_SYSTEM_LOCALE = "KEY_FOLLOW_SYSTEM_LOCALE"
		const val KEY_LANGUAGES = "KEY_LANGUAGES"
	}
}
