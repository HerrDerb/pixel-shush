package com.herrderb.pssst

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sensor hub setup for rooted Pixels. Shows nothing on phones without root or Magisk,
 * so the normal app keeps its single switch.
 */
@Composable
fun SensorHubCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<RootSetup.Status?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        busy = true
        status = withContext(Dispatchers.IO) { RootSetup.check(context.applicationContext) }
        busy = false
    }

    fun run(action: () -> Boolean) {
        busy = true
        failed = false
        scope.launch {
            failed = !withContext(Dispatchers.IO) { action() }
            refresh++
        }
    }

    val s = status ?: return
    if (!s.relevant) return

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.hub_title), style = MaterialTheme.typography.titleLarge)
            CheckRow(stringResource(R.string.hub_check_root), s.rooted)
            CheckRow(stringResource(R.string.hub_check_magisk), s.magisk)
            CheckRow(stringResource(R.string.hub_check_nanoapp), s.nanoapp)
            CheckRow(stringResource(R.string.hub_check_system), s.privileged)

            val message = when {
                s.removalPending -> R.string.hub_removed
                s.rebootNeeded -> R.string.hub_reboot_needed
                s.usingHub -> R.string.hub_active
                s.privileged -> R.string.hub_no_nanoapp
                !s.rooted -> R.string.hub_grant_root
                !s.magisk -> R.string.hub_needs_magisk
                s.nanoapp == false -> R.string.hub_no_nanoapp
                else -> R.string.hub_install_hint
            }
            Text(
                stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (failed) {
                Text(
                    stringResource(R.string.hub_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    s.rebootNeeded -> Button(enabled = !busy, onClick = { run { RootSetup.reboot(); true } }) {
                        Text(stringResource(R.string.hub_reboot))
                    }
                    s.rooted && s.magisk && s.nanoapp == true && !s.moduleInstalled ->
                        Button(enabled = !busy, onClick = { run { RootSetup.installModule(context) } }) {
                            Text(stringResource(R.string.hub_install))
                        }
                    !s.rooted -> OutlinedButton(enabled = !busy, onClick = { refresh++ }) {
                        Text(stringResource(R.string.hub_check_again))
                    }
                }
                if (s.moduleInstalled) {
                    OutlinedButton(enabled = !busy, onClick = { run { RootSetup.removeModule() } }) {
                        Text(stringResource(R.string.hub_remove))
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean?) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            when (ok) {
                true -> "✓"
                false -> "✗"
                null -> "?"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = when (ok) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
