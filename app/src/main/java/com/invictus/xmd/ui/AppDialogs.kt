package com.invictus.xmd.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Shared dialog sizing -- every AlertDialog in the app opts into a wider,
 * consistent width instead of Material3's cramped default (which shrinks
 * to fit content and looks especially narrow on tall/large-screen phones).
 * [usePlatformDefaultWidth] must be turned off for the fillMaxWidth below
 * to actually take effect, since the platform default caps dialog width
 * before Compose ever sees it.
 */
internal val WideDialogProperties = DialogProperties(usePlatformDefaultWidth = false)

internal fun Modifier.wideDialogWidth(): Modifier = this.fillMaxWidth(fraction = 0.92f)

/**
 * Filled pill-shaped confirm button -- used only for a dialog's primary
 * "Start" action (Add Download / Add Torrent), so it visually stands out
 * from the plain-text Cancel button next to it. Other confirm actions
 * (Save, OK, Delete, etc.) intentionally keep the default TextButton look.
 */
@Composable
internal fun StartChipButton(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        content = content,
    )
}

internal data class AppMessageDialogState(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String? = null,
    val onConfirm: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onDismissAction: (() -> Unit)? = null,
)

@Composable
internal fun AppMessageDialog(
    state: AppMessageDialogState,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    onDismissAction: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.wideDialogWidth(),
        properties = WideDialogProperties,
        title = { Text(state.title) },
        text = { Text(state.message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(state.confirmLabel)
            }
        },
        dismissButton = state.dismissLabel?.let { label ->
            {
                TextButton(onClick = onDismissAction) {
                    Text(label)
                }
            }
        },
    )
}

@Composable
internal fun AppChoiceDialog(
    title: String,
    choices: List<String>,
    dismissLabel: String,
    onChoice: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.wideDialogWidth(),
        properties = WideDialogProperties,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                choices.forEachIndexed { index, choice ->
                    TextButton(
                        onClick = { onChoice(index) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(choice, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel)
            }
        },
    )
}