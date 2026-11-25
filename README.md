# LocaleEasy

LocaleEasy is an Android library for changing the app language and observing the user preference, device languages and effective locales. The runtime supports Android 5.0 (API 21) and later. The sample includes Compose, XML activities, fragments and WebView.

Apps consuming the runtime AAR need Android `compileSdk` 35 or higher. The AAR uses Kotlin 1.9 language and API settings, with a consumer build verified on Kotlin 1.8.22 and 1.9.24. The sample builds with Kotlin 2.2.10. See the [compatibility checks](docs/locale-verification.md#kotlin-consumer-compatibility).

## Try it in this checkout

Open the root project in Android Studio or run `./gradlew :app:assembleDebug`. The sample uses `implementation(project(":locale-easy"))` and applies the optional activity plugin. See the [integration guide](docs/locale-preference-api.md) for the Gradle and AndroidX Startup setup.

AndroidX Startup installs LocaleEasy. Use its Kotlin extensions on the main thread:

```kotlin
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.followSystem
import com.elianfabian.locale_easy.setAppLocale
import java.util.Locale

val locales = LocaleEasy.getInstance()
locales.setAppLocale(Locale.FRENCH)
locales.followSystem()
```

Collect `preference`, `systemLocaleList`, and `effectiveLocaleList` from your UI with lifecycle-aware collection. LocaleEasy applies the selection and schedules recreation of participating activities. UI restoration and drawing then proceed through the Android lifecycle.

## Integrate activities

Wrap the activity base context manually:

```kotlin
override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(LocaleEasy.getInstance().localizedContext(newBase))
}
```

The optional Gradle plugin inserts the wrap for activities compiled in the application module. Activities supplied directly by other modules can use the manual integration where they are defined. See the [plugin setup and scope](docs/locale-preference-api.md#optional-gradle-integration).

For WebViews, the optional `instrumentWebViewLoads` setting repairs native application resources before direct page loads from the application module. See [WebView integration](docs/locale-preference-api.md#optional-webview-repair).

Use `localizedContext(context)` at the point of use for notifications, workers, and other resource lookups. Pass an explicit `Locale` or ordered `List<Locale>` when the text needs to follow someone else's language preferences.

## Choose which language state to read

| Property | Meaning |
| --- | --- |
| `preference` | The user's LocaleEasy choice |
| `effectiveLocaleList` | Ordered locales LocaleEasy applies |
| `systemLocaleList` | The device's ordered languages |
| `localeCatalog` | The optional catalog used by LocaleEasy and its fallback |
| `selectedAndroidAppLocale` | The primary per-app language saved by Android |
| `androidSupportedLocaleList` | Configuration of the Android Settings picker |

LocaleEasy's in-app choice and Android Settings are independent. If Settings saves Italian while LocaleEasy has French selected, wrapped screens use French and `selectedAndroidAppLocale` reports Italian. On API 34+, `setAndroidSupportedLocaleList()` changes which languages the Android picker offers.

See the [API and usage examples](docs/locale-preference-api.md) and [verification results](docs/locale-verification.md).
