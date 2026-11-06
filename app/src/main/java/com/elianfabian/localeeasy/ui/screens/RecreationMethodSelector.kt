package com.elianfabian.localeeasy.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elianfabian.localeeasy.MainViewModel
import com.elianfabian.localeeasy.R

@Composable
fun RecreationMethodSelector(viewModel: MainViewModel) {
    val framework by viewModel.frameworkRecreation.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.recreation_method), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().testTag("recreation_method")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(if (framework) R.string.recreation_framework else R.string.recreation_manual), modifier = Modifier.weight(1f))
                Text("▾")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.recreation_manual)) },
                modifier = Modifier.testTag("recreation_manual"),
                onClick = { expanded = false; viewModel.setFrameworkRecreation(false) })
            DropdownMenuItem(text = { Text(stringResource(R.string.recreation_framework)) },
                modifier = Modifier.testTag("recreation_framework"), enabled = Build.VERSION.SDK_INT >= 33,
                onClick = { expanded = false; viewModel.setFrameworkRecreation(true) })
        }
        Text(stringResource(if (Build.VERSION.SDK_INT >= 33) R.string.recreation_hint else R.string.recreation_legacy_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
