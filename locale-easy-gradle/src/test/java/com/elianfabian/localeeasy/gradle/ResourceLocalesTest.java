package com.elianfabian.localeeasy.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.GradleException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ResourceLocalesTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void discoversLanguagesAcrossVariantSourceDirectories() throws IOException {
        File main = temporaryFolder.newFolder("main");
        File debug = temporaryFolder.newFolder("debug");
        for (String directory : List.of("values", "values-fil", "values-fr", "values-fr-rca-night", "values-b+zh+Hant+TW",
                "values-mcc310-mnc004-es-rUS", "values-night", "values-car", "values-v31", "values-en")) {
            addValuesFile(main, directory);
        }
        addValuesFile(debug, "values-fr");
        addValuesFile(debug, "values-b+pt+BR");
        Files.createDirectory(debug.toPath().resolve("values-it"));
        Path colors = Files.createDirectory(debug.toPath().resolve("values-de"));
        Files.writeString(colors.resolve("colors.xml"), "<resources><color name=\"accent\">#ffffff</color></resources>");

        assertEquals(List.of("en", "es-US", "fil", "fr", "fr-CA", "pt-BR", "zh-Hant-TW"),
                ResourceLocales.discover(List.of(main, debug), "en"));
        assertEquals(List.of("en", "es-US", "fil", "fr", "fr-CA", "zh-Hant-TW"),
                ResourceLocales.discover(List.of(main), "en"));
    }

    @Test
    public void canonicalizesAndValidatesTheDeclaredDefaultLanguage() {
        assertEquals("pt-BR", ResourceLocales.validateDefaultLanguage("pt-br"));
        for (String invalid : List.of("", "und", "pt_BR", "en-!")) {
            assertThrows(GradleException.class, () -> ResourceLocales.validateDefaultLanguage(invalid));
        }
    }

    @Test
    public void discoversLegacyAliasesScriptsAndNumericRegions() throws IOException {
        File resources = temporaryFolder.newFolder("localeQualifiers");
        for (String qualifier : List.of("values-iw", "values-he", "values-in", "values-id",
                "values-ji", "values-yi", "values-b+sr+Latn+RS", "values-b+es+419")) {
            addValuesFile(resources, qualifier);
        }

        assertEquals(List.of("en", "es-419", "he", "id", "sr-Latn-RS", "yi"),
                ResourceLocales.discover(List.of(resources), "en"));
    }

    private static void addValuesFile(File root, String qualifier) throws IOException {
        Path directory = Files.createDirectory(root.toPath().resolve(qualifier));
        Files.writeString(directory.resolve("strings.xml"), "<resources><string name=\"label\">Label</string></resources>");
    }
}
