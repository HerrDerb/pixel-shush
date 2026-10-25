package com.herrderb.pssst

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PssstTheme { PssstScreen() } }
    }

    @Composable
    private fun PssstScreen() {
        val prefs = remember { Prefs(this) }
        val zen = remember { ZenRule(this) }
        var enabled by remember { mutableStateOf(prefs.enabled) }
        var hasAccess by remember { mutableStateOf(zen.hasAccess()) }
        var showAccessDialog by remember { mutableStateOf(false) }

        // Coming back from the DND access page: finish turning on if access was granted.
        LifecycleResumeEffect(Unit) {
            hasAccess = zen.hasAccess()
            if (enabled && hasAccess) PssstService.start(this@MainActivity)
            onPauseOrDispose { }
        }

        fun setEnabled(on: Boolean) {
            if (on && !zen.hasAccess()) {
                showAccessDialog = true
                return
            }
            enabled = on
            prefs.enabled = on
            if (on) {
                askForExtras()
                PssstService.start(this)
            } else {
                PssstService.stop(this)
                zen.remove()
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(stringResource(R.string.main_title), style = MaterialTheme.typography.displaySmall)

                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier
                            .clickable { setEnabled(!enabled) }
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.main_switch),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(16.dp))
                        Switch(checked = enabled, onCheckedChange = { setEnabled(it) })
                    }
                }

                Text(
                    stringResource(R.string.main_summary),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (enabled && !hasAccess) {
                    Text(
                        stringResource(R.string.access_missing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { showAccessDialog = true },
                    )
                }

                SensorHubCard()
            }
        }

        if (showAccessDialog) {
            AlertDialog(
                onDismissRequest = { showAccessDialog = false },
                title = { Text(stringResource(R.string.access_title)) },
                text = { Text(stringResource(R.string.access_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        showAccessDialog = false
                        enabled = true
                        prefs.enabled = true
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }) { Text(stringResource(R.string.access_open)) }
                },
                dismissButton = {
                    TextButton(onClick = { showAccessDialog = false }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }

    /** Notification permission (for the first-use tip) and Doze exemption (screen-off checks). */
    private fun askForExtras() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        PssstService.batteryOptimizationIntent(this)?.let { runCatching { startActivity(it) } }
    }
}

@Composable
private fun PssstTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
