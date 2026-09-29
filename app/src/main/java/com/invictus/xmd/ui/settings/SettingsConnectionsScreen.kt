package com.invictus.xmd.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.invictus.xmd.R

private const val MIN_CONNECTIONS = 1
private const val MAX_CONNECTIONS = 24
private const val HIGH_CONNECTIONS = 12
private const val MIN_CONCURRENT = 1
private const val MAX_CONCURRENT = 5

/** (label, KB/s) presets; 0 means unlimited. */
private val SPEED_PRESETS = listOf(
    0 to null,
    512 to "512 KB/s",
    1024 to "1 MB/s",
    2048 to "2 MB/s",
    5120 to "5 MB/s",
)

/**
 * Connections & Speed, as three cards: connections per download (slider with
 * a live value badge), speed limit (preset chips + custom field) and
 * simultaneous downloads (stepper). Every change persists immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsConnectionsScreen(
    connections: Int,
    speedLimitKBps: Int,
    maxConcurrent: Int,
    onConnectionsChanged: (Int) -> Unit,
    onSpeedLimitChanged: (Int) -> Unit,
    onMaxConcurrentChanged: (Int) -> Unit,
) {
    // Keyed on the persisted value so an external change refreshes the field,
    // while an in-progress edit (empty / mid-digit) isn't clobbered.
    var speedLimitText by remember(speedLimitKBps) { mutableStateOf(speedLimitKBps.toString()) }
    val concurrent = maxConcurrent.coerceIn(MIN_CONCURRENT, MAX_CONCURRENT)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Connections per download ─────────────────────────────────
        SettingsSectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_connections),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ValueBadge(connections.toString())
            }
            Slider(
                value = connections.toFloat(),
                onValueChange = { onConnectionsChanged(it.toInt().coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS)) },
                valueRange = MIN_CONNECTIONS.toFloat()..MAX_CONNECTIONS.toFloat(),
                steps = MAX_CONNECTIONS - MIN_CONNECTIONS - 1,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(MIN_CONNECTIONS.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(MAX_CONNECTIONS.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = stringResource(if (connections > HIGH_CONNECTIONS) R.string.conn_hint_high else R.string.conn_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (connections > HIGH_CONNECTIONS) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // ── Speed limit ──────────────────────────────────────────────
        SettingsSectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            Text(
                text = stringResource(R.string.conn_speed_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.conn_speed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SPEED_PRESETS.forEach { (value, label) ->
                    FilterChip(
                        selected = speedLimitKBps == value,
                        onClick = { onSpeedLimitChanged(value) },
                        label = { Text(label ?: stringResource(R.string.conn_unlimited)) },
                    )
                }
            }
            OutlinedTextField(
                value = speedLimitText,
                onValueChange = { input ->
                    val filtered = input.filter(Char::isDigit).take(7)
                    speedLimitText = filtered
                    filtered.toIntOrNull()?.let(onSpeedLimitChanged)
                },
                label = { Text(stringResource(R.string.conn_custom_speed)) },
                suffix = { Text("KB/s") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
        }

        // ── Simultaneous downloads ───────────────────────────────────
        SettingsSectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.conn_concurrent_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.conn_concurrent_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalIconButton(
                    onClick = { onMaxConcurrentChanged(concurrent - 1) },
                    enabled = concurrent > MIN_CONCURRENT,
                ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                Text(
                    text = concurrent.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(36.dp),
                )
                FilledTonalIconButton(
                    onClick = { onMaxConcurrentChanged(concurrent + 1) },
                    enabled = concurrent < MAX_CONCURRENT,
                ) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ValueBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp).widthIn(min = 28.dp),
            textAlign = TextAlign.Center,
        )
    }
}
