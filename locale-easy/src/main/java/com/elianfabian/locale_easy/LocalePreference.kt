package com.elianfabian.locale_easy

import java.util.Collections
import java.util.Locale

/** The user's language selection, independently of the current Android configuration. */
public sealed interface LocalePreference {

	/** Follow system languages, including subsequent changes to them. */
	public data object FollowSystem : LocalePreference

	/**
	 * Prefer [locales] in the supplied order. The list must contain at least one locale
	 * with a language; use [FollowSystem] instead of an empty list.
	 * Treat [locales] as immutable, including when using [copy]. LocaleEasy makes an
	 * immutable copy when applying the preference so later input mutations cannot change its state.
	 * Repeated locales are ignored after their first occurrence.
	 */
	public data class AppLocales(public val locales: List<Locale>) : LocalePreference {

		init {
			require(this.locales.isNotEmpty()) { "App locales must not be empty; use FollowSystem" }
			require(this.locales.all { parseLocaleTag(it.toLanguageTag()) == it }) {
				"App locales must have a language and round-trip as BCP 47 tags"
			}
		}

	}
}

internal fun LocalePreference.immutableCopy(): LocalePreference = when (this) {
	LocalePreference.FollowSystem -> this
	is LocalePreference.AppLocales -> copy(locales = Collections.unmodifiableList(locales.distinct()))
}

internal fun LocalePreference.resolveLocales(systemLocales: List<Locale>): List<Locale> = when (this) {
	LocalePreference.FollowSystem -> systemLocales
	is LocalePreference.AppLocales -> locales
}

internal fun restoreLocalePreference(followSystem: Boolean, storedLocales: String?): LocalePreference {
	if (followSystem || storedLocales.isNullOrBlank()) return LocalePreference.FollowSystem
	// Migrate common legacy Locale.toString() values such as en_US and pt_BR.
	// Discard values we cannot parse strictly rather than accepting partial language tags.
	val locales = storedLocales.split(',').map { tag -> parseLocaleTag(tag.trim().replace('_', '-')) }
	return if (locales.any { it == null }) LocalePreference.FollowSystem
	else LocalePreference.AppLocales(locales.filterNotNull()).immutableCopy()
}

internal fun parseLocaleTag(tag: String): Locale? =
	runCatching { Locale.Builder().setLanguageTag(tag).build() }
		.getOrNull()?.takeIf { it.language.isNotEmpty() }
