package com.elianfabian.locale_easy

import java.util.Locale

/** The languages configured for the Android Settings app-language picker. */
public sealed interface AndroidSupportedLocales {
	/** The picker API is unavailable before Android 13. */
	public data object Unsupported : AndroidSupportedLocales

	/** Android has no usable locale configuration for this app. */
	public data object NotConfigured : AndroidSupportedLocales

	/** The configured languages, which can be an empty list. */
	public data class Configured(public val locales: List<Locale>) : AndroidSupportedLocales
}
