package com.elianfabian.localeeasy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elianfabian.localeeasy.BuildConfig
import com.elianfabian.localeeasy.ui.settings.LocaleSettingsSheet
import com.elianfabian.localeeasy.ui.settings.LocaleSettingsViewModel
import com.elianfabian.localeeasy.R
import com.elianfabian.localeeasy.ui.theme.TranslatedTextColor
import com.elianfabian.localeeasy.ui.theme.EnglishOnlyColor
import com.elianfabian.localeeasy.ui.theme.localizableButtonColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: LocaleSettingsViewModel,
    onOpenCompose: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenFragment: () -> Unit,
    onOpenWebView: () -> Unit
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val locales by viewModel.effectiveLocaleList.collectAsStateWithLifecycle()
    val stringsXmlLanguages = BuildConfig.STRINGS_XML_LANGUAGE_TAGS.distinct().joinToString()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.main_title), color = TranslatedTextColor) }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(stringResource(R.string.sample_intro), style = MaterialTheme.typography.bodyLarge,
                color = EnglishOnlyColor)
            TextOriginLegend()
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.local_recreation_status), color = EnglishOnlyColor,
                        style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.state_app_locales, locales.joinToString { it.toLanguageTag() }),
                        style = MaterialTheme.typography.bodyMedium, color = TranslatedTextColor)
                    Text(
                        stringResource(
                            R.string.home_strings_xml_languages,
                            stringsXmlLanguages.ifEmpty { stringResource(R.string.locale_not_configured) },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TranslatedTextColor,
                    )
                    Button(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth(), colors = localizableButtonColors()) {
                        Text(stringResource(R.string.open_settings))
                    }
                }
            }
            Text(stringResource(R.string.sample_screens), style = MaterialTheme.typography.titleMedium, color = EnglishOnlyColor)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SampleDestination(R.string.compose_sample_btn, R.string.compose_description, onOpenCompose)
                SampleDestination(R.string.activity_sample_btn, R.string.activity_description, onOpenActivity)
                SampleDestination(R.string.fragment_sample_btn, R.string.fragment_description, onOpenFragment)
                SampleDestination(R.string.webview_sample_btn, R.string.webview_description, onOpenWebView)
            }
        }
        if (showSettings) LocaleSettingsSheet(viewModel) { showSettings = false }
    }
}

@Composable
private fun SampleDestination(title: Int, description: Int, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = TranslatedTextColor)
                Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                    color = EnglishOnlyColor)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = EnglishOnlyColor)
        }
    }
}
