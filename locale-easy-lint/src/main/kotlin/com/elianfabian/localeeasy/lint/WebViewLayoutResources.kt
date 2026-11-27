package com.elianfabian.localeeasy.lint

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.resources.ResourceType
import com.android.tools.lint.client.api.ResourceRepositoryScope
import com.android.tools.lint.detector.api.JavaContext
import org.w3c.dom.Element

internal class WebViewLayoutResources(private val context: JavaContext) {
	private val resources by lazy { context.client.getResources(context.project, ResourceRepositoryScope.PROJECT_ONLY) }
	private val layouts = mutableMapOf<String, List<Element>>()

	fun bindingLayout(bindingClassName: String): String? =
		resources.getResourceNames(ResourceNamespace.RES_AUTO, ResourceType.LAYOUT).singleOrNull { resource ->
			resource.split('_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Binding" ==
				bindingClassName
		}

	fun inspect(layout: String): LayoutRisk = inspect(layout, mutableSetOf(), seenWebView = false)

	private fun inspect(layout: String, visiting: MutableSet<String>, seenWebView: Boolean): LayoutRisk {
		if (!visiting.add(layout)) return LayoutRisk(hasWebView = seenWebView)
		val result = layouts.getOrPut(layout) {
			resources.getResources(ResourceNamespace.RES_AUTO, ResourceType.LAYOUT, layout).mapNotNull { item ->
				item.source?.toFile()?.let { context.client.getXmlDocument(it)?.documentElement }
			}
		}.map { root ->
			inspect(root, visiting, seenWebView).copy(
				// A plain Activity's resource-ID rewrite skips layouts with a merge root.
				mergeRoot = root.tagName == "merge",
			)
		}.fold(LayoutRisk(hasWebView = seenWebView), ::combine)
		visiting.remove(layout)
		return result
	}

	private fun inspect(element: Element, visiting: MutableSet<String>, seenWebView: Boolean): LayoutRisk {
		if (element.tagName == "include") {
			val layout = element.getAttribute("layout")
			if (layout.startsWith("@layout/")) return inspect(layout.removePrefix("@layout/"), visiting, seenWebView)
		}
		val className = if (element.tagName == "view") element.getAttribute("class") else element.tagName
		val webView = className == "WebView" || className == WEB_VIEW ||
			('.' in className && InstrumentationDetector.isType(context, context.evaluator.findClass(className), WEB_VIEW))
		var risk = LayoutRisk(
			hasWebView = seenWebView || webView,
			laterLocalizedView = seenWebView && !webView && ('.' in className || hasLocalizedAttribute(element)),
		)
		var child = element.firstChild
		while (child != null) {
			if (child is Element) risk = combine(risk, inspect(child, visiting, risk.hasWebView))
			child = child.nextSibling
		}
		return risk
	}

	private fun hasLocalizedAttribute(element: Element): Boolean =
		(0 until element.attributes.length).any { index ->
			val attribute = element.attributes.item(index)
			attribute.namespaceURI != TOOLS_URI &&
				(attribute.nodeValue.startsWith("@string/") || attribute.nodeValue.startsWith("@plurals/"))
		}

	private fun combine(first: LayoutRisk, second: LayoutRisk): LayoutRisk =
		LayoutRisk(
			hasWebView = first.hasWebView || second.hasWebView,
			laterLocalizedView = first.laterLocalizedView || second.laterLocalizedView,
			mergeRoot = first.mergeRoot || second.mergeRoot,
		)

	internal data class LayoutRisk(
		val hasWebView: Boolean = false,
		val laterLocalizedView: Boolean = false,
		val mergeRoot: Boolean = false,
	)

	private companion object {
		const val WEB_VIEW = "android.webkit.WebView"
		const val TOOLS_URI = "http://schemas.android.com/tools"
	}
}
