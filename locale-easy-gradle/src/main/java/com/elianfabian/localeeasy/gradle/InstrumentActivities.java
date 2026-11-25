package com.elianfabian.localeeasy.gradle;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

@CacheableTask
public abstract class InstrumentActivities extends DefaultTask {
    private static final String ACTIVITY = "android/app/Activity";
    private static final String LOCALE_EASY = "com/elianfabian/locale_easy/LocaleEasy";
    private static final String ATTACH = "attachBaseContext";
    private static final String CONTEXT = "Landroid/content/Context;";
    private static final String ATTACH_DESCRIPTOR = "(" + CONTEXT + ")V";

    @Classpath public abstract ListProperty<RegularFile> getInputJars();
    @Classpath public abstract ListProperty<Directory> getInputDirectories();
    @Classpath public abstract ConfigurableFileCollection getClasspath();
    @OutputFile public abstract RegularFileProperty getOutputJar();

    @TaskAction
    public void instrument() throws IOException {
        Map<String, byte[]> entries = readProjectEntries();
        Map<String, ActivityClass> projectClasses = new HashMap<>();
        entries.forEach((name, bytes) -> {
            if (name.endsWith(".class")) {
                var reader = new ClassReader(bytes);
                projectClasses.put(reader.getClassName(), ActivityClass.read(reader));
            }
        });

        var classpath = getClasspath().getFiles();
        var urls = new URL[classpath.size()];
        int index = 0;
        for (var file : classpath) urls[index++] = file.toURI().toURL();
        Path output = getOutputJar().get().getAsFile().toPath();
        Files.createDirectories(output.getParent());
        try (var loader = new URLClassLoader(urls, null);
                var jar = new JarOutputStream(Files.newOutputStream(output))) {
            if (loader.getResource(LOCALE_EASY + ".class") == null) {
                throw new GradleException("Add the LocaleEasy runtime dependency to use instrumentActivities.");
            }
            var hierarchy = new ActivityHierarchy(projectClasses, new HashMap<>(), loader);
            for (var entry : entries.entrySet()) {
                byte[] bytes = entry.getValue();
                if (entry.getKey().endsWith(".class")) {
                    var reader = new ClassReader(bytes);
                    String parent = reader.getSuperName();
                    if (!projectClasses.containsKey(parent) && hierarchy.isActivity(parent)) {
                        if (hierarchy.hasFinalAttach(parent)) {
                            getLogger().warn("LocaleEasy skipped " + reader.getClassName().replace('/', '.')
                                    + " because its superclass has a final attachBaseContext outside the app module.");
                        } else {
                            bytes = instrumentRoot(reader, projectClasses.get(reader.getClassName()));
                        }
                    }
                }
                var outputEntry = new JarEntry(entry.getKey());
                outputEntry.setTime(0);
                jar.putNextEntry(outputEntry);
                jar.write(bytes);
                jar.closeEntry();
            }
        }
    }

    private Map<String, byte[]> readProjectEntries() throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (Directory directory : getInputDirectories().get()) {
            Path root = directory.getAsFile().toPath();
            try (var paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String name = root.relativize(file).toString().replace('\\', '/');
                    putEntry(result, name, Files.readAllBytes(file));
                }
            }
        }
        for (RegularFile input : getInputJars().get()) {
            try (var jar = new JarFile(input.getAsFile())) {
                for (var entry : jar.stream().filter(item -> !item.isDirectory()).toList()) {
                    try (var content = jar.getInputStream(entry)) {
                        putEntry(result, entry.getName(), content.readAllBytes());
                    }
                }
            }
        }
        return result;
    }

    private static void putEntry(Map<String, byte[]> entries, String name, byte[] bytes) {
        if (entries.putIfAbsent(name, bytes) != null && name.endsWith(".class")) {
            throw new GradleException("Duplicate class while instrumenting LocaleEasy activities: " + name);
        }
    }

    private static byte[] instrumentRoot(ClassReader reader, ActivityClass activity) {
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            private boolean wrappedSuperCall;

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (activity.attachAccess < 0) return method;
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String calledName,
                            String calledDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESPECIAL && owner.equals(activity.parent)
                                && isAttach(calledName, calledDescriptor)) {
                            wrapContext(this);
                            wrappedSuperCall = true;
                        }
                        super.visitMethodInsn(opcode, owner, calledName, calledDescriptor, isInterface);
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (activity.attachAccess < 0) {
                    MethodVisitor method = super.visitMethod(Opcodes.ACC_PROTECTED, ATTACH, ATTACH_DESCRIPTOR, null, null);
                    method.visitCode();
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    wrapContext(method);
                    method.visitMethodInsn(Opcodes.INVOKESPECIAL, activity.parent, ATTACH, ATTACH_DESCRIPTOR, false);
                    method.visitInsn(Opcodes.RETURN);
                    method.visitMaxs(0, 0);
                    method.visitEnd();
                } else if (!wrappedSuperCall) {
                    throw new GradleException("Cannot find the superclass attachBaseContext call in "
                            + reader.getClassName().replace('/', '.'));
                }
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static boolean isAttach(String name, String descriptor) {
        return ATTACH.equals(name) && ATTACH_DESCRIPTOR.equals(descriptor);
    }

    private static void wrapContext(MethodVisitor method) {
        // Keep the activity receiver under the context argument for the original super call.
        String companion = LOCALE_EASY + "$Companion";
        method.visitFieldInsn(Opcodes.GETSTATIC, LOCALE_EASY, "Companion", "L" + companion + ";");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, companion, "getInstance", "()L" + LOCALE_EASY + ";", false);
        method.visitInsn(Opcodes.SWAP);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, LOCALE_EASY, "localizedContext",
                "(" + CONTEXT + ")" + CONTEXT, true);
    }

    private static final class ActivityClass {
        final String parent;
        int attachAccess = -1;

        private ActivityClass(String parent) {
            this.parent = parent;
        }

        static ActivityClass read(ClassReader reader) {
            var metadata = new ActivityClass(reader.getSuperName());
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    if (isAttach(name, descriptor)) metadata.attachAccess = access;
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return metadata;
        }
    }

    private record ActivityHierarchy(
            Map<String, ActivityClass> project, Map<String, ActivityClass> dependencies, URLClassLoader loader) {
        boolean isActivity(String name) throws IOException {
            while (name != null) {
                if (ACTIVITY.equals(name)) return true;
                ActivityClass type = find(name);
                if (type == null) return false;
                name = type.parent;
            }
            return false;
        }

        boolean hasFinalAttach(String name) throws IOException {
            while (name != null) {
                ActivityClass type = find(name);
                if (type == null) return false;
                if (type.attachAccess >= 0) return (type.attachAccess & Opcodes.ACC_FINAL) != 0;
                name = type.parent;
            }
            return false;
        }

        private ActivityClass find(String name) throws IOException {
            ActivityClass local = project.get(name);
            if (local != null) return local;
            if (!dependencies.containsKey(name)) {
                try (var bytes = loader.getResourceAsStream(name + ".class")) {
                    dependencies.put(name, bytes == null ? null : ActivityClass.read(new ClassReader(bytes)));
                }
            }
            return dependencies.get(name);
        }
    }
}
