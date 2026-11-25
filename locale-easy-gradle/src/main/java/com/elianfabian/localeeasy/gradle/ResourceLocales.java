package com.elianfabian.localeeasy.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
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
                if (tag != null && hasTextResources(directory)) locales.add(tag);
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
        throw new GradleException("Set unqualifiedResLocale in res/resources.properties or "
                + "localeEasyLocales.defaultLanguage to a BCP 47 tag such as en or pt-BR.");
    }

    static String readDefaultLanguage(List<File> roots) {
        for (File root : roots) {
            File file = new File(root, "resources.properties");
            if (!file.isFile()) continue;
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(file.toPath())) {
                properties.load(reader);
            } catch (IOException error) {
                throw new GradleException("Unable to read " + file, error);
            }
            String language = properties.getProperty("unqualifiedResLocale");
            if (language != null) return language.trim();
        }
        return "";
    }

    private static boolean hasTextResources(File directory) {
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".xml"));
        if (files == null) return false;
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        for (File file : files) {
            if (containsTextResource(factory, file)) return true;
        }
        return false;
    }

    private static boolean containsTextResource(XMLInputFactory factory, File file) {
        try (var stream = Files.newInputStream(file.toPath())) {
            var xml = factory.createXMLStreamReader(stream);
            try {
                int depth = 0;
                while (xml.hasNext()) {
                    int event = xml.next();
                    if (event == XMLStreamConstants.START_ELEMENT && ++depth == 2) {
                        String type = xml.getLocalName();
                        if ("item".equals(type)) type = xml.getAttributeValue(null, "type");
                        if ("string".equals(type) || "plurals".equals(type) || "string-array".equals(type)) {
                            return true;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        depth--;
                    }
                }
                return false;
            } finally {
                xml.close();
            }
        } catch (IOException | XMLStreamException error) {
            throw new GradleException("Unable to inspect locale resources in " + file, error);
        }
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
