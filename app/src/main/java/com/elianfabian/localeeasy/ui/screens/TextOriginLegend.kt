package com.elianfabian.localeeasy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elianfabian.localeeasy.R
import com.elianfabian.localeeasy.ui.theme.EnglishOnlyColor
import com.elianfabian.localeeasy.ui.theme.TranslatedTextColor

/** The green key is localizable too; the gray key deliberately stays English. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextOriginLegend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("● " + stringResource(R.string.legend_localizable), color = TranslatedTextColor,
            style = MaterialTheme.typography.labelMedium)
        Text("● " + stringResource(R.string.legend_english_only), color = EnglishOnlyColor,
            style = MaterialTheme.typography.labelMedium)
    }
}
