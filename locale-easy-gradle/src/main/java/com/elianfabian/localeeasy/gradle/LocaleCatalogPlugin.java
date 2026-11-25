package com.elianfabian.localeeasy.gradle;

import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.instrumentation.InstrumentationScope;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import com.android.build.api.variant.BuildConfigField;
import com.android.build.api.variant.ScopedArtifacts;
import com.android.build.api.variant.SourceDirectories;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import kotlin.Unit;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
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
        locales.getInstrumentActivities().convention(false);
        locales.getInstrumentWebViewLoads().convention(false);
        locales.getInstrumentWebViewInflation().convention(false);
        locales.getInstrumentWebViewCreation().convention(false);
        locales.getWebViewContentViewActivities().convention(Set.of());
        project.getPluginManager().withPlugin("com.android.application", plugin -> {
            ApplicationAndroidComponentsExtension android =
                    project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
            android.finalizeDsl((Action<ApplicationExtension>) dsl -> dsl.getBuildFeatures().setBuildConfig(true));
            android.onVariants(android.selector().all(),
                    (Action<ApplicationVariant>) variant -> {
                        configureCatalog(project, variant, locales);
                        boolean activities = locales.getInstrumentActivities().get();
                        boolean webViewLoads = locales.getInstrumentWebViewLoads().get();
                        boolean inflation = locales.getInstrumentWebViewInflation().get();
                        boolean creation = locales.getInstrumentWebViewCreation().get();
                        var contentViewActivities = locales.getWebViewContentViewActivities().get();
                        if (!contentViewActivities.isEmpty() && !creation) {
                            throw new GradleException("webViewContentViewActivities requires instrumentWebViewCreation.");
                        }
                        if (!contentViewActivities.isEmpty()) {
                            variant.getInstrumentation().transformClassesWith(AppCompatContentInstrumentation.class,
                                    InstrumentationScope.ALL, unused -> Unit.INSTANCE);
                        }
                        if (activities || webViewLoads || inflation || creation) {
                            var task = project.getTasks().register(
                                    "instrumentLocaleEasyClasses_" + variant.getName(),
                                    InstrumentClasses.class, instrument -> {
                                        instrument.getInstrumentActivities().set(activities);
                                        instrument.getInstrumentWebViewLoads().set(webViewLoads);
                                        instrument.getInstrumentWebViewInflation().set(inflation);
                                        instrument.getInstrumentWebViewCreation().set(creation);
                                        instrument.getWebViewContentViewActivities().set(contentViewActivities);
                                        instrument.getClasspath().from(variant.getCompileClasspath(),
                                                android.getSdkComponents().getBootClasspath());
                                    });
                            variant.getArtifacts().forScope(ScopedArtifacts.Scope.PROJECT).use(task).toTransform(
                                    ScopedArtifact.CLASSES.INSTANCE,
                                    InstrumentClasses::getInputJars,
                                    InstrumentClasses::getInputDirectories,
                                    InstrumentClasses::getOutputJar);
                        }
                    });
        });
    }

    private static void configureCatalog(Project project, ApplicationVariant variant, LocaleCatalogExtension extension) {
        SourceDirectories.Layered resources = variant.getSources().getRes();
        Provider<List<File>> roots = resources.getStatic().map(layers ->
                layers.stream().flatMap(layer -> layer.stream()).map(Directory::getAsFile).toList());
        Provider<String> defaultLanguage = extension.getDefaultLanguage()
                .orElse(roots.map(ResourceLocales::readDefaultLanguage));
        boolean catalogOptional = (extension.getInstrumentActivities().get()
                || extension.getInstrumentWebViewLoads().get() || extension.getInstrumentWebViewInflation().get()
                || extension.getInstrumentWebViewCreation().get())
                && !extension.getGenerateFallbackCatalog().get();
        Provider<List<String>> catalog = roots.zip(defaultLanguage, (directories, language) ->
                catalogOptional && language.isEmpty() ? List.of()
                        : ResourceLocales.discover(directories, ResourceLocales.validateDefaultLanguage(language)));

        variant.getBuildConfigFields().putAll(catalog.map(tags -> {
            if (tags.isEmpty()) return Map.of();
            StringJoiner entries = new StringJoiner(", ", "new String[] {", "}");
            tags.forEach(tag -> entries.add(quote(tag)));
            return Map.of(
                    "DEFAULT_LANGUAGE", new BuildConfigField<>("String", quote(tags.get(0)),
                            "Language of unqualified app resources."),
                    "SUPPORTED_LANGUAGES", new BuildConfigField<>("String[]", entries.toString(),
                            "Language tags from the variant's app resources."));
        }));

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
