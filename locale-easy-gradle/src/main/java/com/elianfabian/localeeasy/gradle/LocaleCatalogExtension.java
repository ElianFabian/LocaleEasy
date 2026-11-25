package com.elianfabian.localeeasy.gradle;

import org.gradle.api.provider.Property;

public abstract class LocaleCatalogExtension {
    /** BCP 47 tag for the app's unqualified values resources. */
    public abstract Property<String> getDefaultLanguage();

    /** Generate the XML catalog consumed by LocaleEasy at startup. */
    public abstract Property<Boolean> getGenerateFallbackCatalog();

    /** Apply LocaleEasy to activities compiled in the app module. */
    public abstract Property<Boolean> getInstrumentActivities();
}
