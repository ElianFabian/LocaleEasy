# LocaleEasy Sample App Walkthrough

I have completely restructured the sample app to provide a better testing experience. The app now features separated navigation for each sample type, global localization controls, and multi-level activity navigation.

## Key Accomplishments

### 1. Separated Navigation Flows
Each sample is now isolated to allow for independent testing:
- **Compose Screen**: A dedicated Compose screen for modern UI testing.
- **Activity Navigation**: Demonstrates navigating between multiple activities (`Activity 1` → `Activity 2`) and back, ensuring locale state is preserved across the backstack.
- **Fragment Sample**: A dedicated fragment sample using XML layouts.
- **WebView Sample**: A specialized sample to test the WebView locale bug and its fix.

### 2. Global Localization Control
- I implemented a **Global Settings Sheet** (`LocaleSettingsSheet.kt`) that can be opened from **any screen** in the app.
- This allows you to change the language or toggle "Follow System Locale" regardless of where you are in the navigation flow.
- The state (Following System, App Locales, System Locales) is displayed reactively in this global sheet.

### 3. Modernized Legacy UI with ViewBinding
- All legacy components (`SampleActivity`, `SampleFragment`, `WebViewFragment`) now use **ViewBinding**.
- I removed `ConstraintLayout` and replaced it with basic, lightweight layouts (`LinearLayout`, `FrameLayout`) to simplify the view hierarchy.

### 4. High-Contrast Localization Highlighting
- **Dark Green (#1B5E20)**: Applied to all translated string resources and localized dates.
- **Dark Red (#B71C1C)**: Applied to hardcoded markers (e.g., "Hardcoded Marker (Activity)") to distinguish them from localized content.

### 5. Multi-language Support
Added full translations for:
- English, Spanish, French, Portuguese, and Italian.

## Verification Summary
- **Build Success**: The project builds successfully with `./gradlew :app:assembleDebug`.
- **Navigation**: Verified that all navigation paths (Compose, Activities, Fragments, WebView) are correctly implemented.
- **Title Persistence**: Verified that activity titles are correctly updated and displayed in a `Toolbar` in `SampleActivity`.

## How to Test
1. **Explore Navigation**: From the main screen, try each sample button to see the isolated flows.
2. **Test Activity Backstack**: Go to "Activity Navigation", click "Next Activity", change the language using "Open State & Controls", and then go back. Verify that all activities in the stack reflect the new language.
3. **Global Control**: Use the "Open State & Controls" button found on every screen to change locales on the fly.
4. **Localization Check**: Look for **Green** text to verify translations and **Red** text to see where hardcoding is intentional for testing.
5. **WebView Fix**: Use the WebView sample and the "Fix WebView Locale & Reload" button to test the specific Android 7.0+ fix.
