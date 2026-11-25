package com.elianfabian.localeeasy.gradle;

import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

public abstract class LocaleCatalogExtension {
    /** BCP 47 tag for the app's unqualified values resources. */
    public abstract Property<String> getDefaultLanguage();

    /** Generate the XML catalog consumed by LocaleEasy at startup. */
    public abstract Property<Boolean> getGenerateFallbackCatalog();

    /** Apply LocaleEasy to activities compiled in the app module. */
    public abstract Property<Boolean> getInstrumentActivities();

    /** Repair native resources before WebView loads called from the app module. */
    public abstract Property<Boolean> getInstrumentWebViewLoads();

    /** Repair native resources after XML layout inflation performed by the app module. */
    public abstract Property<Boolean> getInstrumentWebViewInflation();

    /** Repair resources while creating WebViews in app code and explicit XML inflation calls. */
    public abstract Property<Boolean> getInstrumentWebViewCreation();

    /** Activities that opt into View Binding style inflation for direct setContentView calls in onCreate. */
    public abstract SetProperty<String> getWebViewContentViewActivities();
}
