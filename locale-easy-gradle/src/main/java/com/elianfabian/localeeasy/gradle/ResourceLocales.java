package com.elianfabian.localeeasy.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.gradle.api.GradleException;

final class ResourceLocales {
    // AAPT reserves bare "car" for the UI mode. Its locale spelling is "b+car".
    private static final Pattern LOCALE_QUALIFIER = Pattern.compile(
            "^values-(?:mcc[0-9]{3}-)?(?:mnc[0-9]{1,3}-)?(b\\+[a-z0-9+]+|(?!car(?:-|$))[a-z]{2,3}(?:-r[a-z]{2})?)(?:-|$)");

    static List<String> discover(Collection<File> roots, String defaultLanguage) {
        TreeSet<String> locales = new TreeSet<>();
        for (File root : roots) {
            File[] valuesDirectories = root.listFiles(file -> file.isDirectory() && file.getName().startsWith("values-"));
            if (valuesDirectories == null) continue;
            for (File directory : valuesDirectories) {
                String tag = languageTag(directory.getName());
                if (tag != null && hasValuesFile(directory)) locales.add(tag);
            }
        }
        locales.remove(defaultLanguage);
        List<String> supported = new ArrayList<>(locales.size() + 1);
        supported.add(defaultLanguage);
        supported.addAll(locales);
        return supported;
    }

    static String validateDefaultLanguage(String value) {
        try {
            String languageTag = canonicalLanguageTag(value);
            if (languageTag != null) return languageTag;
        } catch (IllformedLocaleException ignored) {
            // Report an actionable Gradle error for invalid input.
        }
        throw new GradleException("Set localeEasyLocales.defaultLanguage to a BCP 47 tag such as en or pt-BR.");
    }

    private static boolean hasValuesFile(File directory) {
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".xml"));
        return files != null && files.length != 0;
    }

    private static String languageTag(String directory) {
        var match = LOCALE_QUALIFIER.matcher(directory.toLowerCase(Locale.ROOT));
        if (!match.find()) return null;
        String qualifier = match.group(1);
        String tag = qualifier.startsWith("b+") ? qualifier.substring(2).replace('+', '-')
                : qualifier.replace("-r", "-");
        try {
            return canonicalLanguageTag(tag);
        } catch (IllformedLocaleException ignored) {
            return null; // Android's resource compiler reports malformed resource directories.
        }
    }

    private static String canonicalLanguageTag(String tag) {
        Locale locale = new Locale.Builder().setLanguageTag(tag).build();
        return locale.getLanguage().isEmpty() ? null : locale.toLanguageTag();
    }

    private ResourceLocales() {}
}
