package com.elianfabian.localeeasy.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

@CacheableTask
public abstract class InstrumentClasses extends DefaultTask {
    private static final String ACTIVITY = "android/app/Activity";
    private static final String APP_COMPAT_ACTIVITY = "androidx/appcompat/app/AppCompatActivity";
    private static final String WEB_VIEW = "android/webkit/WebView";
    private static final String LAYOUT_INFLATER = "android/view/LayoutInflater";
    private static final String LOCALE_EASY = "com/elianfabian/locale_easy/LocaleEasy";
    private static final String COMPANION = LOCALE_EASY + "$Companion";
    private static final String WEB_VIEW_INFLATION = "com/elianfabian/locale_easy/WebViewLayoutInflation";
    private static final String WEB_VIEW_CONTENT = "com/elianfabian/locale_easy/WebViewContentView";
    private static final String ATTACH = "attachBaseContext";
    private static final String CONTEXT = "Landroid/content/Context;";
    private static final String ATTACH_DESCRIPTOR = "(" + CONTEXT + ")V";

    @Classpath public abstract ListProperty<RegularFile> getInputJars();
    @Classpath public abstract ListProperty<Directory> getInputDirectories();
    @Classpath public abstract ConfigurableFileCollection getClasspath();
    @Input public abstract Property<Boolean> getInstrumentActivities();
    @Input public abstract Property<Boolean> getInstrumentWebViewLoads();
    @Input public abstract Property<Boolean> getInstrumentWebViewInflation();
    @Input public abstract Property<Boolean> getInstrumentWebViewCreation();
    @Input public abstract SetProperty<String> getWebViewContentViewActivities();
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
        boolean activities = getInstrumentActivities().get();
        boolean webViewLoads = getInstrumentWebViewLoads().get();
        boolean inflation = getInstrumentWebViewInflation().get();
        boolean creation = getInstrumentWebViewCreation().get();
        var contentViewActivities = getWebViewContentViewActivities().get();
        try (var loader = new URLClassLoader(urls, null);
                var jar = new JarOutputStream(Files.newOutputStream(output))) {
            if (loader.getResource(LOCALE_EASY + ".class") == null) {
                throw new GradleException("Add the LocaleEasy runtime dependency to enable LocaleEasy instrumentation.");
            }
            if (creation && loader.getResource(WEB_VIEW_INFLATION + ".class") == null) {
                throw new GradleException("Use a LocaleEasy runtime that supports instrumentWebViewCreation.");
            }
            if (!contentViewActivities.isEmpty() && loader.getResource(WEB_VIEW_CONTENT + ".class") == null) {
                throw new GradleException("Use a LocaleEasy runtime that supports webViewContentViewActivities.");
            }
            if (!contentViewActivities.isEmpty()
                    && loader.getResource(AppCompatContentInstrumentation.RUNTIME_OWNER + ".class") == null) {
                throw new GradleException("Use a LocaleEasy runtime that supports AppCompat content inflation.");
            }
            var hierarchy = new ClassHierarchy(projectClasses, new HashMap<>(), loader);
            for (var entry : entries.entrySet()) {
                byte[] bytes = entry.getValue();
                if (entry.getKey().endsWith(".class")) {
                    var reader = new ClassReader(bytes);
                    String parent = reader.getSuperName();
                    ActivityClass activity = null;
                    if (activities && !projectClasses.containsKey(parent) && hierarchy.isSubclassOf(parent, ACTIVITY)) {
                        if (hierarchy.hasFinalAttach(parent)) {
                            getLogger().warn("LocaleEasy skipped " + reader.getClassName().replace('/', '.')
                                    + " because its superclass has a final attachBaseContext outside the app module.");
                        } else {
                            activity = projectClasses.get(reader.getClassName());
                        }
                    }
                    if (activity != null || webViewLoads || inflation || creation) {
                        boolean rewriteContentView = contentViewActivities.contains(reader.getClassName().replace('/', '.'))
                                && hierarchy.isSubclassOf(parent, ACTIVITY);
                        boolean appCompatContentView = rewriteContentView && hierarchy.isSubclassOf(parent, APP_COMPAT_ACTIVITY);
                        bytes = instrumentClass(reader, activity, hierarchy, webViewLoads, inflation, creation,
                                rewriteContentView, appCompatContentView);
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
            throw new GradleException("Duplicate class while instrumenting with LocaleEasy: " + name);
        }
    }

    private static byte[] instrumentClass(ClassReader reader, ActivityClass activity, ClassHierarchy hierarchy,
            boolean webViewLoads, boolean inflation, boolean creation, boolean rewriteContentView,
            boolean appCompatContentView) {
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            private boolean wrappedSuperCall;

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!webViewLoads && !inflation && !creation && activity.attachAccess < 0) return method;
                boolean rewriteHere = rewriteContentView && name.equals("onCreate")
                        && descriptor.equals("(Landroid/os/Bundle;)V");
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String calledName,
                            String calledDescriptor, boolean isInterface) {
                        if (activity != null && activity.attachAccess >= 0
                                && opcode == Opcodes.INVOKESPECIAL && owner.equals(activity.parent)
                                && isAttach(calledName, calledDescriptor)) {
                            wrapContext(this);
                            wrappedSuperCall = true;
                        }
                        boolean instanceCall = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL;
                        boolean inflaterCall = (creation || inflation) && instanceCall
                                && isInflaterCall(hierarchy, owner, calledName, calledDescriptor);
                        if (webViewLoads && instanceCall && isWebViewLoad(calledName, calledDescriptor)
                                && hierarchy.isSubtype(owner, WEB_VIEW)) {
                            // Preserve the receiver and navigation arguments already on the stack.
                            readCompanion(this);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COMPANION, "fixWebViewLocale", "()V", false);
                        }
                        boolean activityLayoutCall = (inflation || rewriteHere) && instanceCall
                                && isActivityLayoutCall(hierarchy, owner, calledName, calledDescriptor);
                        if (rewriteHere && opcode == Opcodes.INVOKEVIRTUAL && activityLayoutCall) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    appCompatContentView ? AppCompatContentInstrumentation.RUNTIME_OWNER : WEB_VIEW_CONTENT,
                                    appCompatContentView ? "setAppCompatContentView" : "setContentView",
                                    "(L" + ACTIVITY + ";I)V", false);
                        } else if (creation && inflaterCall && opcode == Opcodes.INVOKEVIRTUAL) {
                            String descriptorWithInflater = "(L" + LAYOUT_INFLATER + ";" + calledDescriptor.substring(1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, WEB_VIEW_INFLATION,
                                    "inflate", descriptorWithInflater, false);
                        } else {
                            super.visitMethodInsn(opcode, owner, calledName, calledDescriptor, isInterface);
                        }
                        if (creation && opcode == Opcodes.INVOKESPECIAL && calledName.equals("<init>")
                                && hierarchy.isSubtype(owner, WEB_VIEW)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, WEB_VIEW_INFLATION,
                                    "repairAfterWebViewCreation", "()V", false);
                        }
                        if (inflation && (inflaterCall || activityLayoutCall)) {
                            // Keep the layout result on the stack for the original caller.
                            readCompanion(this);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COMPANION,
                                    "repairAfterLayoutInflation", "()V", false);
                        }
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (activity == null) {
                    super.visitEnd();
                    return;
                }
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

    private static boolean isWebViewLoad(String name, String descriptor) {
        return switch (name) {
            case "loadUrl" -> descriptor.equals("(Ljava/lang/String;)V")
                    || descriptor.equals("(Ljava/lang/String;Ljava/util/Map;)V");
            case "loadData" -> descriptor.equals("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
            case "loadDataWithBaseURL" -> descriptor.equals(
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
            default -> false;
        };
    }

    private static boolean isActivityLayoutCall(ClassHierarchy hierarchy, String owner, String name, String descriptor) {
        return name.equals("setContentView") && descriptor.equals("(I)V") && hierarchy.isSubtype(owner, ACTIVITY);
    }

    private static boolean isInflaterCall(ClassHierarchy hierarchy, String owner, String name, String descriptor) {
        if (!name.equals("inflate")) return false;
        return switch (descriptor) {
            case "(ILandroid/view/ViewGroup;)Landroid/view/View;",
                 "(ILandroid/view/ViewGroup;Z)Landroid/view/View;",
                 "(Lorg/xmlpull/v1/XmlPullParser;Landroid/view/ViewGroup;)Landroid/view/View;",
                 "(Lorg/xmlpull/v1/XmlPullParser;Landroid/view/ViewGroup;Z)Landroid/view/View;" ->
                    hierarchy.isSubtype(owner, LAYOUT_INFLATER);
            default -> false;
        };
    }

    private static void wrapContext(MethodVisitor method) {
        // Keep the activity receiver under the context argument for the original super call.
        readCompanion(method);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COMPANION, "getInstance", "()L" + LOCALE_EASY + ";", false);
        method.visitInsn(Opcodes.SWAP);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, LOCALE_EASY, "localizedContext",
                "(" + CONTEXT + ")" + CONTEXT, true);
    }

    private static void readCompanion(MethodVisitor method) {
        method.visitFieldInsn(Opcodes.GETSTATIC, LOCALE_EASY, "Companion", "L" + COMPANION + ";");
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

    private record ClassHierarchy(
            Map<String, ActivityClass> project, Map<String, ActivityClass> dependencies, URLClassLoader loader) {
        boolean isSubclassOf(String name, String ancestor) throws IOException {
            while (name != null) {
                if (ancestor.equals(name)) return true;
                ActivityClass type = find(name);
                if (type == null) return false;
                name = type.parent;
            }
            return false;
        }

        boolean isSubtype(String name, String ancestor) {
            try {
                return isSubclassOf(name, ancestor);
            } catch (IOException error) {
                throw new UncheckedIOException("Unable to resolve the hierarchy of " + name, error);
            }
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
