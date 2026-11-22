package com.elianfabian.locale_easy

import java.util.Collections
import java.util.Locale

/** A single observation of the app's language preference and the system locale list. */
public class LocaleState(preference: LocalePreference, systemLocales: List<Locale>) {
	public val preference: LocalePreference = preference.immutableCopy()
	public val systemLocales: List<Locale> = Collections.unmodifiableList(ArrayList(systemLocales))

	/** Ordered locale preference used for resources. Android resolves fallback per resource. */
	public val effectiveLocales: List<Locale> = this.preference.resolveLocales(this.systemLocales)

	override fun equals(other: Any?): Boolean =
		other is LocaleState && preference == other.preference && systemLocales == other.systemLocales

	override fun hashCode(): Int = 31 * preference.hashCode() + systemLocales.hashCode()

	override fun toString(): String = "LocaleState(preference=$preference, systemLocales=$systemLocales)"
}
