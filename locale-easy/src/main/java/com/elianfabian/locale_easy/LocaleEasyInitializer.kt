package com.elianfabian.locale_easy

import android.content.Context
import androidx.startup.Initializer

public class LocaleEasyInitializer : Initializer<LocaleEasy> {

	override fun create(context: Context): LocaleEasy {
		println("$$$ LocaleEasyInitializer")
		LocaleEasy.install(context)
		return LocaleEasy.getInstance()
	}

	override fun dependencies(): List<Class<out Initializer<*>?>?> = emptyList()
}
