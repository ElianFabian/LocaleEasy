package com.elianfabian.locale_easy

import java.util.Collections
import java.util.Locale

internal class LocaleState(
	preference: LocalePreference,
	systemLocales: List<Locale>,
	catalog: LocaleCatalog,
	val selectedAndroidAppLocale: Locale? = null,
) {
	val preference: LocalePreference = preference.immutableCopy()
	val systemLocales: List<Locale> = Collections.unmodifiableList(ArrayList(systemLocales))

	/** Ordered locale preference used for resources. Android resolves fallback per resource. */
	val effectiveLocales: List<Locale> = when (val selection = this.preference) {
		LocalePreference.FollowSystem ->
			Collections.unmodifiableList(catalog.resolveSystemLocales(this.systemLocales))
		is LocalePreference.AppLocales -> selection.locales
	}

	override fun equals(other: Any?): Boolean =
		other is LocaleState && preference == other.preference && systemLocales == other.systemLocales &&
			effectiveLocales == other.effectiveLocales && selectedAndroidAppLocale == other.selectedAndroidAppLocale

	override fun hashCode(): Int {
		var result = preference.hashCode()
		result = 31 * result + systemLocales.hashCode()
		result = 31 * result + effectiveLocales.hashCode()
		return 31 * result + selectedAndroidAppLocale.hashCode()
	}
}
