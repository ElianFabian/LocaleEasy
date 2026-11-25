package com.elianfabian.localeeasy.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.ClassUtil
import java.util.Base64
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.USuperExpression

class ContentViewRewriteDetector : Detector(), SourceCodeScanner {
	override fun getApplicableMethodNames() = listOf("setContentView")

	override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
		if (context.isTestSource || node.receiver is USuperExpression || !method.takes("int")) return
		val receiver = when (val type = node.receiverType) {
			is PsiClassType -> type.resolve()
			else -> method.containingClass
		}
		if (!isActivity(context, receiver)) return

		var enclosing = node.uastParent
		while (enclosing != null && enclosing !is UMethod) {
			if (enclosing is ULambdaExpression || enclosing is UClass) return
			enclosing = enclosing.uastParent
		}
		val onCreate = enclosing?.javaPsi ?: return
		if (onCreate.name != "onCreate" || !onCreate.takes("android.os.Bundle")) return
		val activity = onCreate.containingClass ?: return
		if (!isActivity(context, activity) || !isEnabledForActivity(context, activity)) return

		val reason = "On API 24+, creating a WebView can change the app's resource locales, causing later views " +
			"in the same layout to load text in the wrong language. LocaleEasy restores the locales after " +
			"creating the WebView, before inflating the next view."
		val rewrite = if (context.evaluator.extendsClass(activity, APP_COMPAT, false)) {
			"LocaleEasy keeps `setContentView(id)` and replaces AppCompat's `inflate(id, contentParent)` " +
				"with `inflateAppCompatContent(inflater, id, contentParent)`, preserving the content parent."
		} else {
			"For ordinary layouts, LocaleEasy replaces `setContentView(id)` with instrumented " +
				"`inflate(id, null, false)` followed by `setContentView(view)`. Root layout parameters can " +
				"change. Layouts with `<merge>` keep the original call."
		}
		context.report(ISSUE, node, context.getNameLocation(node), "$reason $rewrite$SILENCE_HINTS")
	}

	private fun PsiMethod.takes(parameterType: String): Boolean =
		returnType == PsiTypes.voidType() &&
			parameterList.parameters.singleOrNull()?.type?.equalsToText(parameterType) == true

	private fun isActivity(context: JavaContext, type: PsiClass?): Boolean =
		type != null && (type.qualifiedName == ACTIVITY || context.evaluator.extendsClass(type, ACTIVITY, false))

	private fun isEnabledForActivity(context: JavaContext, activity: PsiClass): Boolean {
		val encoded = buildConfig(context)
			?.findFieldByName(ACTIVITIES_FIELD, false)
			?.computeConstantValue() as? String ?: return false
		val names = try {
			Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
		} catch (_: IllegalArgumentException) {
			return false
		}
		return ClassUtil.getJVMClassName(activity) in names.split('\n')
	}

	companion object {
		private const val ACTIVITY = "android.app.Activity"
		private const val APP_COMPAT = "androidx.appcompat.app.AppCompatActivity"
		private const val ACTIVITIES_FIELD = "LOCALE_EASY_LINT_CONTENT_VIEW_ACTIVITIES"
		const val SILENCE_HINTS = " To hide LocaleEasy instrumentation hints, set " +
			"`showInstrumentationHints.set(false)` in `localeEasyLocales`."

		@JvmField
		val ISSUE: Issue = Issue.create(
			"LocaleEasyContentViewRewrite",
			"LocaleEasy instruments this content view to fix a WebView locale issue",
			"An activity opted into LocaleEasy content inflation may have its setContentView layout call " +
				"rewritten at build time. Earlier repair restores application resource locales as soon as the " +
				"WebView is created, before a later view can read an unintended language. " +
				"The call can stay as written.",
			Category.I18N,
			2,
			Severity.INFORMATIONAL,
			Implementation(ContentViewRewriteDetector::class.java, Scope.JAVA_FILE_SCOPE),
		)

		@JvmStatic
		fun buildConfig(context: JavaContext): PsiClass? {
			val namespace = context.project.buildVariant?.module?.namespace ?: return null
			return context.evaluator.findClass("$namespace.BuildConfig")
		}
	}
}
