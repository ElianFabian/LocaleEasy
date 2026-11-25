# Android locale pitfalls

An Android app can have a saved language preference, an ordered list used for resource resolution, and views that were already built under an earlier configuration. These values can diverge. LocaleEasy tracks the preference and effective locales, gives participating activities a localized context, and lets Android rebuild their views. The cases below explain where to check when a screen displays the wrong language. [LocaleEasy integration guide](locale-preference-api.md)

## Device languages, app preference, and available translations

Keep the device language list, the in-app preference, and Android Settings' per-app language separate. On an API 35 emulator, selecting French in Android Settings changed the process's `Resources.getSystem().configuration.locales` from `en-US` to `fr,en-US`. `LocaleManager.systemLocales` still returned `en-US`. LocaleEasy uses `LocaleManager` to read device locales from API 33 and exposes Android's saved app language separately. Its own explicit preference can still determine what a wrapped activity displays. [Device locale test](locale-verification.md#system-locales-with-an-android-per-app-language)

The catalog describes the languages the app is designed to support. Declare the language of unqualified `res/values` resources because the folder name carries no language tag. An Android Settings picker can have no usable configuration or be configured with an empty list. Android 14 and later also allow a runtime picker override. LocaleEasy's resource catalog and Android's picker answer separate questions, and either list can include a language for which a Play language split has yet to be installed. The release bundle experiment verified that English and French APK sets from the same catalog contained different language splits. [Catalog and picker checks](locale-verification.md#context-composition-and-android-picker)

## Ordered locales and old language codes

Pass locale preferences in priority order. On API 24 and later, Android resource resolution can fall back through the ordered list. On API 21–23, LocaleEasy keeps the whole preference and applies the first locale to resource configuration. A string missing from the first locale can therefore resolve differently across those versions. Test a real resource lookup as well as the effective locale list. [Context lookup tests](locale-verification.md#context-composition-and-android-picker)

Use BCP 47 tags for persisted or exchanged locale identifiers and let Android normalize aliases. API 21 accepted both `he` and `iw`, `id` and `in`, and `yi` and `ji`, while serializing the legacy spellings on the tested image. The same fixture checked traditional Android resource qualifiers alongside script and region qualifiers such as Serbian Latin and Latin American Spanish. Preserve scripts, regions, and list order when parsing a saved preference. [Android 5 locale checks](locale-verification.md#legacy-locale-aliases-on-android-5)

## Apply a language before the first resource lookup

The activity needs a localized base context before inflating XML, creating a binding, or resolving strings in Compose. LocaleEasy can attach that context through `attachBaseContext()` or insert the call with its Gradle plugin for activities compiled in the app module. A separate `localizedContext(base, locale)` or ordered-list overload can resolve text for a worker or notification without changing the app's preference. Obtain a fresh context after a preference change. [Activity and resource lookup examples](locale-preference-api.md#android-integration)

Keep a first-run preference separate from the catalog fallback. LocaleEasy uses the optional catalog default if it follows the device and none of the device languages match the catalog. `setInitialLocale()` saves a first-run choice only when there is no saved preference. An explicit Follow System choice also counts as a saved preference. Wait until the library is installed before accessing it. With automatic initialization disabled, call `install(this)` in `Application.onCreate()` before the first activity starts. This startup path was verified in fresh processes on API 21, 24, and 35. [Startup checks](locale-verification.md#applying-a-language-selection-and-manual-initialization)

## Activity recreation, saved state, and window transitions

A locale state emission means that LocaleEasy applied the preference. The replacement activity, its views, and the first translated frame follow through the lifecycle. In the Pixel 8 Pro comparison, `Activity.recreate()`, `ActivityCompat.recreate()`, and rotation all constructed new activity instances and replaced the content. The explicit routes reused the decor in the measured runs and restored user input. That decor reuse is an Android implementation detail. If a view remains in the old language, compare the activity's resource, the inflater context's resource, and the final label after state restoration to find where the old value entered. [Pixel comparison](recreation-comparison.md#pixel-8-pro-verification-2026-11-22)

System configuration changes and explicit recreation reach common client relaunch code through different paths. In an API 36 test, an automatic app-language change through `LocaleManager` removed the old window surface before the new one appeared, producing black frames. Disabling animations did not remove that gap. LocaleEasy's current in-app language switch uses local recreation. Verify any reported flash with a recording or surface trace, and check toolbar insets, the return of dialogs and bottom sheets, and retained form state independently. Timing and garbage collection varied across experimental runs. [Window handoff investigation](framework-window-handoff-investigation.md) · [Pixel method and allocation comparison](recreation-comparison.md#three-way-method-allocation-and-frame-comparison)

## The first WebView can reset application resources

The first WebView in a fresh process changed French application resources to the English device language on tested API 24, 32, and 35 providers. Tests with the providers on API 21–23 kept French. The localized activity resources stayed French during the reset. The provider can initialize in the constructor, before any page load. [WebView provider results](locale-verification.md#webview-resource-repair-and-page-state)

| Where the app reads a native string | What the experiments showed |
| --- | --- |
| A sibling view constructor after an XML WebView | The application context returned English. The localized view context returned French. The label assigned English stayed in English after the layout finished. |
| After an XML layout returns | Repair after inflation made the next application lookup French. That worked with direct inflation, View Binding, and `setContentView(layoutResId)`. |
| Inside a Compose `AndroidView` factory, just after `WebView(context)` | The application context returned English. The factory context and Compose's `stringResource()` returned French. Repair immediately after construction protected the early application lookup. |
| Before a WebView navigation call | Repair restored the application resources used by later reads. An existing label kept the string it was already assigned. |

Use the localized view or composition context for UI resources. For code that needs the application context, repair after creating the WebView and before looking up a string. The repair affects native resources. The existing WebView retained its page, form, scroll position, and history when the helper ran. If activity recreation builds a replacement WebView, the screen must save and restore any web state it wants to carry forward. The HTML's language is determined separately. [WebView verification and integration](locale-verification.md#webview-resource-repair-and-page-state)

The experimental `instrumentWebViewCreation` option performs the early repair automatically. On API 24 and 35, it covered direct construction in Compose and XML passed through an instrumented inflater, including View Binding with AppCompat. `setContentView(layoutResId)` still repairs after the layout returns through the separate inflation option, so a sibling view constructor can read English during that call. Existing applications can start with the load or post-inflation option and enable the earlier one if they need reads inside inflation to work. [Plugin options and scope](locale-preference-api.md#optional-webview-repair)

## Verify the exact point where the language changes

- Compare the saved preference, effective locales, and a known translated resource.
- Check the activity context, the inflater or view context, and the application context separately.
- For WebView, measure a fresh process and record the provider version. Read before construction, immediately after construction, and after the first navigation call.
- For activity changes, compare the activity and root view identities, then verify the displayed strings and saved state. Use video when the problem involves a transient frame.
- Test at least one pre-24 device and one newer device for ordered locales, and use an API 33+ device when checking Android's app-language interaction.
