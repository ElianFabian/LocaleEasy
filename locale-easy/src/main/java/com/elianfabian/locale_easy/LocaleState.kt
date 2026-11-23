package com.elianfabian.locale_easy

import java.util.Collections
import java.util.Locale

/** A single observation of the app's language preference and the system locale list. */
public class LocaleState internal constructor(
	preference: LocalePreference,
	systemLocales: List<Locale>,
	catalog: LocaleCatalog,
) {
	public constructor(preference: LocalePreference, systemLocales: List<Locale>) :
		this(preference, systemLocales, LocaleCatalog.Empty)

	public val preference: LocalePreference = preference.immutableCopy()
	public val systemLocales: List<Locale> = Collections.unmodifiableList(ArrayList(systemLocales))

	/** Ordered locale preference used for resources. Android resolves fallback per resource. */
	public val effectiveLocales: List<Locale> = when (val selection = this.preference) {
		LocalePreference.FollowSystem ->
			Collections.unmodifiableList(catalog.resolveSystemLocales(this.systemLocales))
		is LocalePreference.AppLocales -> selection.locales
	}

	override fun equals(other: Any?): Boolean =
		other is LocaleState && preference == other.preference && systemLocales == other.systemLocales &&
			effectiveLocales == other.effectiveLocales

	override fun hashCode(): Int = 31 * (31 * preference.hashCode() + systemLocales.hashCode()) + effectiveLocales.hashCode()

	override fun toString(): String = "LocaleState(preference=$preference, systemLocales=$systemLocales)"
}
