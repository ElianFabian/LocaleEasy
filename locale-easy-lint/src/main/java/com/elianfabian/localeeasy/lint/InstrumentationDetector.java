package com.elianfabian.localeeasy.lint;

import static com.elianfabian.localeeasy.lint.ContentViewRewriteDetector.SILENCE_HINTS;

import com.android.tools.lint.client.api.UElementHandler;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.detector.api.SourceCodeScanner;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UMethod;
import org.jetbrains.uast.USuperExpression;
import org.jetbrains.uast.UastCallKind;

public final class InstrumentationDetector extends Detector implements SourceCodeScanner {
    private static final String ACTIVITY = "android.app.Activity";
    private static final String INFLATER = "android.view.LayoutInflater";
    private static final String WEB_VIEW = "android.webkit.WebView";
    private static final String FRAGMENT = "androidx.fragment.app.Fragment";
    private static final String DIALOG_FRAGMENT = "androidx.fragment.app.DialogFragment";
    private static final String VIEW_GROUP = "android.view.ViewGroup";
    private static final String PARSER = "org.xmlpull.v1.XmlPullParser";
    private static final String STRING = "java.lang.String";
    private static final String EARLY_REPAIR = "Creating a WebView can change the app's resource locales on API 24+, "
            + "causing later views in the same layout to load text in the wrong language.";
    private static final Implementation IMPLEMENTATION =
            new Implementation(InstrumentationDetector.class, Scope.JAVA_FILE_SCOPE);

    static final Issue ACTIVITY_CONTEXT = issue("LocaleEasyActivityContextRewrite",
            "LocaleEasy wraps this Activity's base context",
            "The plugin supplies a localized Activity context before views read its resources.");
    static final Issue WEB_VIEW_CONSTRUCTION = issue("LocaleEasyWebViewConstructionRewrite",
            "LocaleEasy repairs locales after WebView construction", EARLY_REPAIR);
    static final Issue LAYOUT_INFLATION = issue("LocaleEasyLayoutInflationRewrite",
            "LocaleEasy instruments this layout inflation",
            "The plugin checks for changes to application resource locales during or after layout inflation.");
    static final Issue WEB_VIEW_LOAD = issue("LocaleEasyWebViewLoadRewrite",
            "LocaleEasy repairs locales before WebView navigation",
            "The optional navigation hook checks application resource locales before loading this WebView's content.");
    static final Issue FRAGMENT_LAYOUT = issue("LocaleEasyFragmentLayoutRewrite",
            "LocaleEasy instruments AndroidX Fragment layout inflation", EARLY_REPAIR);

    private static Issue issue(String id, String title, String explanation) {
        return Issue.create(id, title, explanation + " The source code can stay as written.",
                Category.I18N, 2, Severity.INFORMATIONAL, IMPLEMENTATION);
    }

    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        return List.of(UCallExpression.class, UClass.class);
    }

    @Override
    public UElementHandler createUastHandler(JavaContext context) {
        if (context.isTestSource()) return null;
        PsiClass config = ContentViewRewriteDetector.buildConfig(context);
        if (config == null) return null;
        boolean activities = flag(config, "ACTIVITIES");
        boolean creation = flag(config, "WEB_VIEW_CREATION");
        boolean inflation = flag(config, "WEB_VIEW_INFLATION");
        boolean loads = flag(config, "WEB_VIEW_LOADS");
        if (!activities && !creation && !inflation && !loads) return null;
        String bindingPackage = config.getQualifiedName().replace(".BuildConfig", ".databinding.");
        return new UElementHandler() {
            @Override
            public void visitClass(UClass node) {
                if (activities) reportActivityContext(context, node);
            }

            @Override
            public void visitCallExpression(UCallExpression node) {
                if (UastCallKind.CONSTRUCTOR_CALL.equals(node.getKind())) {
                    if (creation) reportConstructor(context, node);
                    return;
                }
                String name = node.getMethodName();
                if (name == null) return;
                switch (name) {
                    case "setContentView":
                        if (inflation) {
                            PsiMethod method = node.resolve();
                            if (method != null && parameters(method, "int")
                                    && isType(context, receiver(node, method), ACTIVITY)) {
                                reportLateInflation(context, node);
                            }
                        }
                        break;
                    case "inflate":
                        if (creation || inflation) {
                            reportInflation(context, node, bindingPackage, creation, inflation);
                        }
                        break;
                    case "loadUrl":
                    case "loadData":
                    case "loadDataWithBaseURL":
                        if (loads) reportLoad(context, node, name);
                        break;
                    default:
                }
            }
        };
    }

    private static boolean flag(PsiClass config, String name) {
        PsiField field = config.findFieldByName("LOCALE_EASY_LINT_" + name, false);
        return field != null && Boolean.TRUE.equals(field.computeConstantValue());
    }

    private static void reportActivityContext(JavaContext context, UClass node) {
        PsiClass parent = node.getJavaPsi().getSuperClass();
        if (!isType(context, parent, ACTIVITY) || isAppSource(context, parent)) return;
        for (PsiMethod method : parent.findMethodsByName("attachBaseContext", true)) {
            if (parameters(method, "android.content.Context") && method.hasModifierProperty("final")) return;
        }
        String wrap = "LocaleEasy passes `localizedContext(base)` to `super.attachBaseContext(...)` "
                + "so the Activity's resources use the selected locales." + SILENCE_HINTS;
        for (UMethod method : node.getMethods()) {
            if (method.getName().equals("attachBaseContext") && parameters(method.getJavaPsi(), "android.content.Context")) {
                context.report(ACTIVITY_CONTEXT, method, context.getNameLocation(method), wrap);
                return;
            }
        }
        context.report(ACTIVITY_CONTEXT, node, context.getNameLocation(node),
                "LocaleEasy generates an `attachBaseContext` override. " + wrap);
    }

    private static boolean isAppSource(JavaContext context, PsiClass type) {
        var file = type.getNavigationElement().getContainingFile();
        var virtualFile = file == null ? null : file.getVirtualFile();
        if (virtualFile == null || !virtualFile.isInLocalFileSystem()) return false;
        var path = Path.of(virtualFile.getPath());
        return context.getProject().getJavaSourceFolders().stream()
                .anyMatch(folder -> path.startsWith(folder.toPath()));
    }

    private static void reportConstructor(JavaContext context, UCallExpression node) {
        PsiMethod constructor = node.resolve();
        if (constructor == null) return;
        PsiClass owner = constructor.getContainingClass();
        if (isType(context, owner, WEB_VIEW)) {
            context.report(WEB_VIEW_CONSTRUCTION, node, context.getNameLocation(node),
                    EARLY_REPAIR + " LocaleEasy inserts `repairAfterWebViewCreation()` after this constructor returns."
                            + SILENCE_HINTS);
        } else if (parameters(constructor, "int") && owner != null
                && (FRAGMENT.equals(owner.getQualifiedName()) || DIALOG_FRAGMENT.equals(owner.getQualifiedName()))) {
            context.report(FRAGMENT_LAYOUT, node, context.getNameLocation(node),
                    EARLY_REPAIR + " When AndroidX uses the layout ID, LocaleEasy routes "
                            + "`Fragment.onCreateView` through its WebView-aware inflater, keeping the original container "
                            + "and `attachToRoot = false`." + SILENCE_HINTS);
        }
    }

    private static void reportInflation(JavaContext context, UCallExpression node, String bindingPackage,
            boolean creation, boolean inflation) {
        PsiMethod method = node.resolve();
        if (method == null) return;
        PsiClass owner = receiver(node, method);
        boolean binding = owner != null && owner.getQualifiedName() != null
                && owner.getQualifiedName().startsWith(bindingPackage)
                && isType(context, owner, "androidx.viewbinding.ViewBinding");
        if (!binding && (!isType(context, owner, INFLATER)
                || !(parameters(method, "int", VIEW_GROUP) || parameters(method, "int", VIEW_GROUP, "boolean")
                    || parameters(method, PARSER, VIEW_GROUP) || parameters(method, PARSER, VIEW_GROUP, "boolean")))) return;
        if (creation && !(node.getReceiver() instanceof USuperExpression)) {
            String call = binding ? "the `LayoutInflater.inflate(...)` call generated by View Binding"
                    : "this `LayoutInflater.inflate(...)` call";
            String fallback = inflation ? " The fallback also checks locales after inflation returns." : "";
            context.report(LAYOUT_INFLATION, node, context.getNameLocation(node),
                    EARLY_REPAIR + " LocaleEasy routes " + call + " through a WebView-aware clone of "
                            + "the inflater and restores locales before inflating the next view." + fallback + SILENCE_HINTS);
        } else if (inflation) {
            reportLateInflation(context, node);
        }
    }

    private static void reportLateInflation(JavaContext context, UCallExpression node) {
        context.report(LAYOUT_INFLATION, node, context.getNameLocation(node),
                "With the optional post-inflation hook, LocaleEasy inserts `repairAfterLayoutInflation()` "
                        + "after inflation returns to restore application resource locales changed by WebView on API 24+."
                        + SILENCE_HINTS);
    }

    private static void reportLoad(JavaContext context, UCallExpression node, String name) {
        PsiMethod method = node.resolve();
        if (method == null || !isType(context, receiver(node, method), WEB_VIEW)) return;
        boolean supported = ("loadUrl".equals(name)
                && (parameters(method, STRING) || parameters(method, STRING, "java.util.Map")))
                || ("loadData".equals(name) && parameters(method, STRING, STRING, STRING))
                || ("loadDataWithBaseURL".equals(name) && parameters(method, STRING, STRING, STRING, STRING, STRING));
        if (supported) {
            context.report(WEB_VIEW_LOAD, node, context.getNameLocation(node),
                    "LocaleEasy inserts `fixWebViewLocale()` before this call to restore application resource "
                            + "locales if the WebView provider changed them on API 24+." + SILENCE_HINTS);
        }
    }

    private static PsiClass receiver(UCallExpression node, PsiMethod method) {
        return node.getReceiverType() instanceof PsiClassType
                ? ((PsiClassType) node.getReceiverType()).resolve() : method.getContainingClass();
    }

    private static boolean isType(JavaContext context, PsiClass type, String name) {
        return type != null && (name.equals(type.getQualifiedName())
                || context.getEvaluator().inheritsFrom(type, name, false));
    }

    private static boolean parameters(PsiMethod method, String... types) {
        var params = method.getParameterList().getParameters();
        if (params.length != types.length) return false;
        for (int i = 0; i < types.length; i++) {
            var type = params[i].getType();
            if (type instanceof PsiClassType) type = ((PsiClassType) type).rawType();
            if (!type.equalsToText(types[i])) return false;
        }
        return true;
    }
}
