package com.elianfabian.locale_easy

import android.content.Context
import androidx.core.os.LocaleListCompat
import org.xmlpull.v1.XmlPullParser
import java.util.Collections
import java.util.Locale

/** The languages declared by the app's generated resource catalog. */
public class LocaleCatalog internal constructor(
	/** Language of the unqualified resources, if the app declares a catalog. */
	public val defaultLocale: Locale?,
	supportedLocales: List<Locale>,
) {
	public val supportedLocales: List<Locale> =
		Collections.unmodifiableList((listOfNotNull(defaultLocale) + supportedLocales).distinct())

	internal fun resolveSystemLocales(systemLocales: List<Locale>): List<Locale> {
		val fallback = defaultLocale ?: return systemLocales
		return systemLocales.filter { desired ->
			supportedLocales.any { supported -> LocaleListCompat.matchesLanguageAndScript(supported, desired) }
		}.ifEmpty { listOf(fallback) }
	}

	internal companion object {
		val Empty = LocaleCatalog(null, emptyList())

		fun read(context: Context): LocaleCatalog =
			context.resources.getXml(R.xml.locale_easy_catalog).use { parser ->
				var event = parser.next()
				while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
					event = parser.next()
				}
				if (event == XmlPullParser.END_DOCUMENT) return Empty
				val defaultLocale = parser.getAttributeValue(null, "defaultLocale")?.let(::parseLocaleTag)
					?: return Empty
				val locales = buildList {
					while (parser.next() != XmlPullParser.END_DOCUMENT) {
						if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
							parser.getAttributeValue(null, "tag")?.let(::parseLocaleTag)?.let(::add)
						}
					}
				}
				LocaleCatalog(defaultLocale, locales)
			}
	}
}
