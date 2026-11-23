package com.elianfabian.localeeasy.gradle;

import org.gradle.api.provider.Property;

public abstract class LocaleCatalogExtension {
    /** BCP 47 tag for the app's unqualified values resources. */
    public abstract Property<String> getDefaultLanguage();
}
