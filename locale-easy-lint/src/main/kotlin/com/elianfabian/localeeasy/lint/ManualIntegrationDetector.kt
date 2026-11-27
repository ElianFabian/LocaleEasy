package com.elianfabian.localeeasy.lint

import com.android.resources.ResourceType
import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.ResourceEvaluator
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.elianfabian.localeeasy.lint.InstrumentationDetector.isType
import com.elianfabian.localeeasy.lint.InstrumentationDetector.parameters
import com.elianfabian.localeeasy.lint.InstrumentationDetector.receiver
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.ClassUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstructorDelegationCall
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USuperExpression
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

class ManualIntegrationDetector : Detector(), SourceCodeScanner {
	override fun getApplicableUastTypes() = listOf(UCallExpression::class.java)

	override fun createUastHandler(context: JavaContext): UElementHandler? {
		if (context.isTestSource) return null
		val config = ContentViewRewriteDetector.buildConfig(context)
		val metadata = config?.findFieldByName("LOCALE_EASY_INSTRUMENTATION", false)
			?.computeConstantValue() as? String
		val flags = metadata?.split(',') ?: INSTRUMENTATION_FLAGS.filter {
			config != null && InstrumentationDetector.flag(config, it)
		}
		// Older plugin versions withheld all rewrite flags when hints were silenced.
		if (metadata == null && flags.isEmpty() && config?.findFieldByName("SUPPORTED_LANGUAGES", false) != null &&
			config.findFieldByName("DEFAULT_LANGUAGE", false) != null) return null
		val creation = "WEB_VIEW_CREATION" in flags
		val inflation = "WEB_VIEW_INFLATION" in flags
		val contentActivities = ContentViewRewriteDetector.contentViewActivities(
			config,
			if (metadata == null) "LOCALE_EASY_LINT_CONTENT_VIEW_ACTIVITIES" else "LOCALE_EASY_CONTENT_VIEW_ACTIVITIES",
		)
		val layouts = WebViewLayoutResources(context)
		val activitiesReported = mutableSetOf<PsiClass>()
		return object : UElementHandler() {
			override fun visitCallExpression(node: UCallExpression) {
				val name = node.methodName
				val constructor = node.kind == UastCallKind.CONSTRUCTOR_CALL
				if (!constructor && name !in PREFERENCE_METHODS && name != "inflate" && name != "setContentView") return
				val method = node.resolve() ?: return
				if (name in PREFERENCE_METHODS && "ACTIVITIES" !in flags && usesLocaleEasy(method)) {
					checkActivity(context, node, activitiesReported)
					return
				}
				if (constructor && isType(context, method.containingClass, WEB_VIEW)) {
					if (!creation && !delegatesToConstructor(node) && !repairedAfter(node)) {
						context.report(WEB_VIEW_CREATION, node, context.getNameLocation(node),
							"WebView can reset application resource locales on API 24+. If you read application " +
								"resources after construction, call `LocaleEasy.fixWebViewLocale()` immediately " +
								"after creating the WebView or enable `instrumentWebViewCreation`.")
					}
					return
				}

				val owner = receiver(node, method)
				val fragmentLayout = constructor && parameters(method, "int") &&
					method.containingClass?.qualifiedName in FRAGMENT_TYPES
				val contentView = name == "setContentView" && parameters(method, "int") &&
					isType(context, owner, ACTIVITY)
				val binding = name == "inflate" && isType(context, owner, VIEW_BINDING)
				val inflater = name == "inflate" && isType(context, owner, INFLATER) &&
					(parameters(method, "int", VIEW_GROUP) || parameters(method, "int", VIEW_GROUP, "boolean"))
				if (!(fragmentLayout || contentView || binding || inflater)) return
				if (creation && (fragmentLayout || binding || (inflater && node.receiver !is USuperExpression))) return

				val layout = if (binding) {
					owner?.name?.let(layouts::bindingLayout)
				} else {
					val resource = ResourceEvaluator.getResource(context.evaluator, node.valueArguments.firstOrNull())
					resource?.takeIf { it.type == ResourceType.LAYOUT && !it.isFramework }?.name
				} ?: return
				val risk = layouts.inspect(layout)
				if (!risk.hasWebView) return
				val rewriteActivity = if (contentView) ContentViewRewriteDetector.activityForRewrite(context, node, method)
					else null
				val canRewriteContentView = rewriteActivity != null &&
					(!risk.mergeRoot || isType(context, rewriteActivity, "androidx.appcompat.app.AppCompatActivity"))
				if (creation && canRewriteContentView && ClassUtil.getJVMClassName(rewriteActivity) in contentActivities) return
				val hasLateRepair = repairedAfter(node) || (inflation && (contentView || inflater || binding))
				if (hasLateRepair && !risk.laterLocalizedView) return
				val message = when {
					risk.laterLocalizedView -> {
						val earlyOption = when {
							canRewriteContentView -> "Enable `instrumentWebViewCreation` and add this Activity to " +
								"`webViewContentViewActivities`"
							contentView -> "Use an explicit `LayoutInflater.inflate(...)` call with `instrumentWebViewCreation`"
							else -> "Enable `instrumentWebViewCreation`"
						}
						"`$layout` contains a WebView before a view that can read localized resources. " +
							"$earlyOption, or create the " +
							"WebView separately and call `LocaleEasy.fixWebViewLocale()` before inflating later views."
					}
					fragmentLayout ->
						"`$layout` contains a WebView. Enable `instrumentWebViewCreation`, or override " +
							"`onCreateView()` and call `LocaleEasy.fixWebViewLocale()` after inflating the layout " +
							"when subsequent code reads application resources."
					else ->
						"`$layout` contains a WebView. If you read application resources after inflation, " +
							"call `LocaleEasy.fixWebViewLocale()` immediately after this call or enable " +
							"`instrumentWebViewInflation`."
				}
				context.report(WEB_VIEW_INFLATION, node, context.getNameLocation(node), message)
			}
		}
	}

	private fun checkActivity(context: JavaContext, node: UCallExpression, activitiesReported: MutableSet<PsiClass>) {
		val activity = generateSequence(node.uastParent) { it.uastParent }.filterIsInstance<UClass>().firstOrNull()
			?: return
		val type = activity.javaPsi
		if (!isType(context, type, ACTIVITY) || !activitiesReported.add(type)) return
		val overrides = type.findMethodsByName("attachBaseContext", true)
			.filter { parameters(it, "android.content.Context") }
		if (overrides.any { method ->
				method.toUElementOfType<UMethod>()?.uastBody?.hasCall {
					it.methodName == "localizedContext" && it.resolve()?.let(::usesLocaleEasy) == true
				} == true
			}) return
		if (overrides.any { it.containingClass != type && it.hasModifierProperty(PsiModifier.FINAL) }) return
		context.report(ACTIVITY_CONTEXT, activity, context.getNameLocation(activity),
			"This Activity changes the LocaleEasy preference. To apply it to this Activity's resources, " +
				"pass `LocaleEasy.getInstance().localizedContext(base)` to `super.attachBaseContext(...)`, " +
				"or enable `instrumentActivities`.")
	}

	private fun repairedAfter(call: UCallExpression): Boolean {
		if (repairedInScope(call)) return true
		var statement: UElement = call
		while (true) {
			val parent = statement.uastParent ?: return false
			if (parent is UMethod || parent is UClass) return false
			if (parent is UBlockExpression) {
				val index = parent.expressions.indexOf(statement)
				val nextCall = if (index >= 0) parent.expressions.getOrNull(index + 1)?.asCall() else null
				return nextCall?.let(::isWebViewRepair) == true
			}
			statement = parent
		}
	}

	private fun repairedInScope(call: UCallExpression): Boolean {
		val source = call.sourcePsi ?: return false
		val qualified = source.parent as? KtQualifiedExpression ?: return false
		if (qualified.receiverExpression != source) return false
		val scopeCall = qualified.selectorExpression as? KtCallExpression ?: return false
		val name = scopeCall.calleeExpression?.text
		if (name != "also" && name != "apply") return false
		val first = scopeCall.lambdaArguments.singleOrNull()?.getLambdaExpression()
			?.bodyExpression?.statements?.firstOrNull()
		return first?.toUElementOfType<UExpression>()?.asCall()?.let(::isWebViewRepair) == true
	}

	private fun delegatesToConstructor(call: UCallExpression): Boolean = when (val source = call.sourcePsi) {
		is KtSuperTypeCallEntry, is KtConstructorDelegationCall -> true
		is PsiMethodCallExpression -> source.methodExpression.referenceName in listOf("super", "this")
		else -> false
	}

	private fun UElement.asCall(): UCallExpression? = when (this) {
		is UCallExpression -> this
		is UQualifiedReferenceExpression -> selector as? UCallExpression
		else -> null
	}

	private fun isWebViewRepair(call: UCallExpression): Boolean =
		call.methodName == "fixWebViewLocale" && call.resolve()?.let(::usesLocaleEasy) == true

	private fun usesLocaleEasy(method: PsiMethod): Boolean =
		method.containingClass?.qualifiedName in LOCALE_EASY_OWNERS

	private fun UElement.hasCall(matches: (UCallExpression) -> Boolean): Boolean {
		var found = false
		accept(object : AbstractUastVisitor() {
			override fun visitCallExpression(node: UCallExpression): Boolean {
				if (matches(node)) found = true
				return found
			}
		})
		return found
	}

	companion object {
		private const val ACTIVITY = "android.app.Activity"
		private const val WEB_VIEW = "android.webkit.WebView"
		private const val INFLATER = "android.view.LayoutInflater"
		private const val VIEW_GROUP = "android.view.ViewGroup"
		private const val VIEW_BINDING = "androidx.viewbinding.ViewBinding"
		private val FRAGMENT_TYPES = setOf("androidx.fragment.app.Fragment", "androidx.fragment.app.DialogFragment")
		private val INSTRUMENTATION_FLAGS = listOf("ACTIVITIES", "WEB_VIEW_CREATION", "WEB_VIEW_INFLATION", "WEB_VIEW_LOADS")
		private val PREFERENCE_METHODS = setOf("setPreference", "setAppLocale", "setAppLocaleList", "followSystem", "setInitialLocale")
		private val LOCALE_EASY_OWNERS = setOf(
			"com.elianfabian.locale_easy.LocaleEasy",
			"com.elianfabian.locale_easy.LocaleEasy.Companion",
			"com.elianfabian.locale_easy.LocaleEasyKt",
		)

		private fun issue(id: String, title: String, explanation: String): Issue = Issue.create(
			id, title, explanation, Category.I18N, 3, Severity.INFORMATIONAL,
			Implementation(ManualIntegrationDetector::class.java, Scope.JAVA_FILE_SCOPE),
		)

		@JvmField val ACTIVITY_CONTEXT = issue("LocaleEasyManualActivityContext", "Localize this Activity's base context",
			"Activities that manage their LocaleEasy preference can wrap their base context before inflating views.")
		@JvmField val WEB_VIEW_CREATION = issue("LocaleEasyManualWebViewCreation", "Check locales after WebView creation",
			"On Android 7.0 and later, initializing WebView can change application resource locales.")
		@JvmField val WEB_VIEW_INFLATION = issue("LocaleEasyManualWebViewInflation", "Check locales when inflating a WebView layout",
			"WebView initialization can affect resource reads during and after inflation.")
	}
}
