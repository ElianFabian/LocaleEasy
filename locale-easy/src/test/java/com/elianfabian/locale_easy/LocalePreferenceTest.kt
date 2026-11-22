package com.elianfabian.locale_easy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LocalePreferenceTest {
	@Test fun appLocalesRequiresAtLeastOneLocale() {
		assertTrue(runCatching { LocalePreference.AppLocales(emptyList()) }.exceptionOrNull() is IllegalArgumentException)
	}

	@Test fun appLocalesRequiresALanguage() {
		assertTrue(runCatching { LocalePreference.AppLocales(listOf(Locale.ROOT)) }.exceptionOrNull() is IllegalArgumentException)
	}

	@Test fun appLocalesRejectsLanguagesThatCannotRoundTrip() {
		assertTrue(runCatching {
			LocalePreference.AppLocales(listOf(Locale("fr-CA")))
		}.exceptionOrNull() is IllegalArgumentException)
	}

	@Test fun duplicatesCollapseToFirstOccurrenceWhenAppliedOrRestored() {
		val frenchCanadian = Locale.forLanguageTag("fr-CA")
		val expected = LocalePreference.AppLocales(listOf(Locale.FRENCH, frenchCanadian))
		assertEquals(expected, LocalePreference.AppLocales(listOf(Locale.FRENCH, Locale.FRENCH, frenchCanadian)).immutableCopy())
		assertEquals(expected, restoreLocalePreference(false, "fr,fr,fr-CA"))
	}

	@Test fun immutableCopyProtectsInputAndComparesByOrderedValue() {
		val source = mutableListOf(Locale.FRENCH, Locale.ENGLISH)
		val preference = LocalePreference.AppLocales(source).immutableCopy() as LocalePreference.AppLocales
		source.clear()
		assertEquals(listOf(Locale.FRENCH, Locale.ENGLISH), preference.locales)
		assertEquals(LocalePreference.AppLocales(preference.locales), preference)
		assertEquals(LocalePreference.AppLocales(preference.locales).hashCode(), preference.hashCode())
		assertNotEquals(LocalePreference.AppLocales(preference.locales.reversed()), preference)
		assertTrue(runCatching { (preference.locales as MutableList).clear() }.isFailure)
	}

	@Test fun dataClassSupportsCopyAndDestructuring() {
		val preference = LocalePreference.AppLocales(listOf(Locale.FRENCH))
		val (locales) = preference
		assertEquals(listOf(Locale.FRENCH), locales)
		assertEquals(preference, preference.copy())
		assertEquals(
			LocalePreference.AppLocales(listOf(Locale.ITALIAN)),
			preference.copy(locales = listOf(Locale.ITALIAN)),
		)
		assertTrue(runCatching { preference.copy(locales = emptyList()) }.exceptionOrNull() is IllegalArgumentException)
		assertTrue(runCatching { preference.copy(locales = listOf(Locale.ROOT)) }.exceptionOrNull() is IllegalArgumentException)
	}

	@Test fun followSystemResolvesNewSystemLanguagesWhileCustomStaysFixed() {
		val custom = LocalePreference.AppLocales(listOf(Locale.FRENCH))
		for (system in listOf(listOf(Locale.ENGLISH), listOf(Locale.ITALIAN, Locale.ENGLISH))) {
			assertEquals(system, LocalePreference.FollowSystem.resolveLocales(system))
			assertEquals(listOf(Locale.FRENCH), custom.resolveLocales(system))
		}
	}

	@Test fun storedCustomRoundTripsRegionsScriptsAndOrder() {
		val locales = listOf("zh-Hant-TW", "pt-BR", "en-US-u-nu-arab").map(Locale::forLanguageTag)
		val stored = locales.joinToString(",") { it.toLanguageTag() }
		assertEquals(LocalePreference.AppLocales(locales), restoreLocalePreference(false, stored))
	}

	@Test fun legacyRegionTagsAreMigrated() {
		assertEquals(
			LocalePreference.AppLocales(listOf(Locale.forLanguageTag("en-US"), Locale.forLanguageTag("pt-BR"))),
			restoreLocalePreference(false, "en_US,pt_BR"),
		)
	}

	@Test fun missingOrInvalidStoredOverridesRecoverToSystem() {
		for (stored in listOf(null, "", " ", "und", "en,", "en-!", "en-US-#invalid")) {
			assertEquals(LocalePreference.FollowSystem, restoreLocalePreference(false, stored))
		}
	}

	@Test fun followingSystemIgnoresPreviousStoredOverride() {
		assertEquals(LocalePreference.FollowSystem, restoreLocalePreference(true, "fr"))
	}
}
