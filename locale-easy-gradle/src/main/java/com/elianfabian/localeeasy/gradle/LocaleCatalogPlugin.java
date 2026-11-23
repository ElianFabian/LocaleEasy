package com.elianfabian.localeeasy.gradle;

import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import com.android.build.api.variant.BuildConfigField;
import com.android.build.api.variant.SourceDirectories;
import java.io.File;
import java.util.List;
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
        locales.getGenerateFallbackCatalog().convention(false);
        project.getPluginManager().withPlugin("com.android.application", plugin -> {
            ApplicationAndroidComponentsExtension android =
                    project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
            android.finalizeDsl((Action<ApplicationExtension>) dsl -> dsl.getBuildFeatures().setBuildConfig(true));
            android.onVariants(android.selector().all(),
                    (Action<ApplicationVariant>) variant -> configureCatalog(project, variant, locales));
        });
    }

    private static void configureCatalog(Project project, ApplicationVariant variant, LocaleCatalogExtension extension) {
        SourceDirectories.Layered resources = variant.getSources().getRes();
        Provider<List<File>> roots = resources.getStatic().map(layers ->
                layers.stream().flatMap(layer -> layer.stream()).map(Directory::getAsFile).toList());
        Provider<String> defaultLanguage = extension.getDefaultLanguage()
                .orElse(roots.map(ResourceLocales::readDefaultLanguage))
                .map(ResourceLocales::validateDefaultLanguage);
        Provider<List<String>> catalog = roots.zip(defaultLanguage, ResourceLocales::discover);
        variant.getBuildConfigFields().put("DEFAULT_LANGUAGE", defaultLanguage.map(tag ->
                new BuildConfigField<>("String", quote(tag), "Language of unqualified app resources.")));

        Provider<String> supportedLanguages = catalog.map(tags -> {
            StringJoiner entries = new StringJoiner(", ", "new String[] {", "}");
            tags.forEach(tag -> entries.add(quote(tag)));
            return entries.toString();
        });
        variant.getBuildConfigFields().put("SUPPORTED_LANGUAGES", supportedLanguages.map(tags ->
                new BuildConfigField<>("String[]", tags, "Language tags from the variant's app resources.")));

        if (extension.getGenerateFallbackCatalog().get()) {
            var task = project.getTasks().register("generateLocaleEasyCatalog_" + variant.getName(),
                    GenerateLocaleCatalog.class, generate -> {
                        generate.getLanguageTags().set(catalog);
                        generate.getOutputDirectory().set(project.getLayout().getBuildDirectory()
                                .dir("generated/localeEasy/" + variant.getName()));
                    });
            resources.addGeneratedSourceDirectory(task, GenerateLocaleCatalog::getOutputDirectory);
        }
    }

    private static String quote(String languageTag) {
        return "\"" + languageTag + "\"";
    }
}
