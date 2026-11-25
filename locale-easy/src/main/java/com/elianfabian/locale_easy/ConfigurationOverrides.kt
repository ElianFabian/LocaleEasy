package com.elianfabian.locale_easy

import android.content.Context
import android.content.res.Configuration
import android.os.Build

internal fun configurationOverrides(base: Context): Configuration {
	// Android derives a configuration context from the platform configuration for this
	// window/display. Compare against that baseline to carry over the host's overrides
	// without freezing inherited settings such as night mode and font scale.
	val baseline = Configuration().apply { fontScale = 0f }
	val inherited = base.createConfigurationContext(baseline).resources.configuration
	val current = base.resources.configuration
	val delta = Configuration(baseline)
	if (current.fontScale != inherited.fontScale) delta.fontScale = current.fontScale
	if (current.mcc != inherited.mcc) delta.mcc = current.mcc
	if (current.mnc != inherited.mnc) delta.mnc = current.mnc
	if (current.touchscreen != inherited.touchscreen) delta.touchscreen = current.touchscreen
	if (current.keyboard != inherited.keyboard) delta.keyboard = current.keyboard
	if (current.keyboardHidden != inherited.keyboardHidden) delta.keyboardHidden = current.keyboardHidden
	if (current.hardKeyboardHidden != inherited.hardKeyboardHidden) delta.hardKeyboardHidden = current.hardKeyboardHidden
	if (current.navigation != inherited.navigation) delta.navigation = current.navigation
	if (current.navigationHidden != inherited.navigationHidden) delta.navigationHidden = current.navigationHidden
	if (current.orientation != inherited.orientation) delta.orientation = current.orientation
	if (current.screenWidthDp != inherited.screenWidthDp) delta.screenWidthDp = current.screenWidthDp
	if (current.screenHeightDp != inherited.screenHeightDp) delta.screenHeightDp = current.screenHeightDp
	if (current.smallestScreenWidthDp != inherited.smallestScreenWidthDp) {
		delta.smallestScreenWidthDp = current.smallestScreenWidthDp
	}
	if (current.densityDpi != inherited.densityDpi) delta.densityDpi = current.densityDpi
	delta.uiMode = changedBits(inherited.uiMode, current.uiMode, Configuration.UI_MODE_TYPE_MASK) or
		changedBits(inherited.uiMode, current.uiMode, Configuration.UI_MODE_NIGHT_MASK)
	delta.screenLayout = changedBits(inherited.screenLayout, current.screenLayout, Configuration.SCREENLAYOUT_SIZE_MASK) or
		changedBits(inherited.screenLayout, current.screenLayout, Configuration.SCREENLAYOUT_LONG_MASK)
	if (Build.VERSION.SDK_INT >= 23) {
		delta.screenLayout = delta.screenLayout or
			changedBits(inherited.screenLayout, current.screenLayout, Configuration.SCREENLAYOUT_ROUND_MASK)
	}
	if (Build.VERSION.SDK_INT >= 26) {
		delta.colorMode = changedBits(inherited.colorMode, current.colorMode, Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_MASK) or
			changedBits(inherited.colorMode, current.colorMode, Configuration.COLOR_MODE_HDR_MASK)
	}
	if (Build.VERSION.SDK_INT >= 31 && current.fontWeightAdjustment != inherited.fontWeightAdjustment) {
		delta.fontWeightAdjustment = current.fontWeightAdjustment
	}
	return delta
}

private fun changedBits(inherited: Int, current: Int, mask: Int): Int =
	if ((inherited xor current) and mask == 0) 0 else current and mask
