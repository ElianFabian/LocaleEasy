package com.elianfabian.localeeasy.gradle;

import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import com.android.build.api.variant.BuildConfigField;
import com.android.build.api.variant.SourceDirectories;
import java.util.StringJoiner;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;

public final class LocaleCatalogPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        LocaleCatalogExtension locales =
                project.getExtensions().create("localeEasyLocales", LocaleCatalogExtension.class);
        project.getPluginManager().withPlugin("com.android.application", plugin -> {
            ApplicationAndroidComponentsExtension android =
                    project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
            android.finalizeDsl((Action<ApplicationExtension>) dsl -> dsl.getBuildFeatures().setBuildConfig(true));
            Provider<String> defaultLanguage = locales.getDefaultLanguage().orElse("")
                    .map(ResourceLocales::validateDefaultLanguage);
            android.onVariants(android.selector().all(),
                    (Action<ApplicationVariant>) variant -> addFields(variant, defaultLanguage));
        });
    }

    private static void addFields(ApplicationVariant variant, Provider<String> defaultLanguage) {
        variant.getBuildConfigFields().put("DEFAULT_LANGUAGE", defaultLanguage.map(tag ->
                new BuildConfigField<>("String", quote(tag), "Language of unqualified app resources.")));

        SourceDirectories.Layered resources = variant.getSources().getRes();
        Provider<String> supportedLanguages = resources.getStatic().zip(defaultLanguage, (layers, defaultTag) -> {
            var roots = layers.stream().flatMap(layer -> layer.stream()).map(Directory::getAsFile).toList();
            StringJoiner entries = new StringJoiner(", ", "new String[] {", "}");
            ResourceLocales.discover(roots, defaultTag).forEach(tag -> entries.add(quote(tag)));
            return entries.toString();
        });
        variant.getBuildConfigFields().put("SUPPORTED_LANGUAGES", supportedLanguages.map(tags ->
                new BuildConfigField<>("String[]", tags, "Language tags from the variant's app resources.")));
    }

    private static String quote(String languageTag) {
        return "\"" + languageTag + "\"";
    }
}
