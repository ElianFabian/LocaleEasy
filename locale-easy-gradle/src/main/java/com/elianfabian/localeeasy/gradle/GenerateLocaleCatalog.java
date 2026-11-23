package com.elianfabian.localeeasy.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

@CacheableTask
public abstract class GenerateLocaleCatalog extends DefaultTask {
    @Input public abstract ListProperty<String> getLanguageTags();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void generate() throws IOException {
        List<String> tags = getLanguageTags().get();
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        xml.append("<locale-catalog defaultLocale=\"").append(tags.get(0)).append("\">\n");
        for (String tag : tags.subList(1, tags.size())) {
            xml.append("    <locale tag=\"").append(tag).append("\" />\n");
        }
        xml.append("</locale-catalog>\n");

        Path directory = getOutputDirectory().get().getAsFile().toPath().resolve("xml");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("locale_easy_catalog.xml"), xml);
    }
}
