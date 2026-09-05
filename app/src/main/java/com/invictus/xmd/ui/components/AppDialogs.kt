package com.invictus.xmd.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.DialogProperties

/**
 * Shared dialog sizing -- every AlertDialog in the app opts into a consistent,
 * compact phone-like aspect ratio across all devices and orientations.
 * [usePlatformDefaultWidth] must be turned off for custom width to actually
 * take effect, since the platform default caps dialog width before Compose
 * sees it.
 *
 * Capped at 400dp max width so dialogs maintain a balanced phone-like aspect ratio
 * on tablets (both landscape and portrait) and phone landscape, while scaling
 * naturally on narrower portrait phones.
 */
internal val WideDialogProperties = DialogProperties(usePlatformDefaultWidth = false)

@Composable
internal fun Modifier.wideDialogWidth(): Modifier {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val targetWidth = minOf(screenWidth * 0.88f, 400.dp)
    val maxHeight = (screenHeight - 40.dp).coerceAtLeast(240.dp)
    return this
        .width(targetWidth)
        .heightIn(max = maxHeight)
}

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