package com.elianfabian.localeeasy.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.colorResource
import com.elianfabian.localeeasy.R
import androidx.compose.material3.ButtonDefaults

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Origin markers: green means an Android localizable resource, even when its
// current value/fallback is English. Gray means intentionally English-only text.
val TranslatedTextColor: Color
    @Composable @ReadOnlyComposable get() = colorResource(R.color.translated_text)
val EnglishOnlyColor: Color
    @Composable @ReadOnlyComposable get() = colorResource(R.color.english_only_text)

@Composable
fun localizableButtonColors() = ButtonDefaults.buttonColors(
    containerColor = colorResource(R.color.localized_surface), contentColor = TranslatedTextColor)
