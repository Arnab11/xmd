package com.invictus.xmd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.xmd.R
import com.invictus.xmd.ui.icons.Icon
import com.invictus.xmd.ui.icons.Icons

/**
 * Auto-retry, default save location, folder categorization, and Wi-Fi-only.
 * Each control persists immediately via its own [onXChanged] callback (no
 * Save button), matching the original fragment's behavior including the
 * wifi-only-just-enabled pause-in-flight-downloads side effect (handled in
 * SettingsActivity's DownloadsRoute, not here -- this composable is presentation
 * only).
 *
 * [defaultLocationPath] and [onChangeDefaultLocation] replace the old
 * "Save to Downloads Folder" switch with a folder picker -- the same SAF
 * ACTION_OPEN_DOCUMENT_TREE flow used by the per-download "Change" button in
 * AddDownloadDialog/AddTorrentDialog -- so the user picks any folder as the
 * default instead of a fixed choice between the app folder and Downloads.
 * [categorizeIntoFolders] is the new, independent toggle for whether
 * downloads are still sorted into Videos/Music/Documents/... subfolders
 * under that location.
 */
@Composable
fun SettingsDownloadsScreen(
    autoRetry: Boolean,
    defaultLocationPath: String,
    categorizeIntoFolders: Boolean,
    wifiOnly: Boolean,
    onAutoRetryChanged: (Boolean) -> Unit,
    onChangeDefaultLocation: () -> Unit,
    onCategorizeIntoFoldersChanged: (Boolean) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SettingsSectionCard {
            SwitchSettingRow(
                title = stringResource(R.string.settings_auto_retry),
                subtitle = stringResource(R.string.settings_auto_retry_hint),
                checked = autoRetry,
                onCheckedChange = onAutoRetryChanged,
            )
            SettingsDivider()
            DefaultLocationRow(
                path = defaultLocationPath,
                onChangeClick = onChangeDefaultLocation,
            )
            SettingsDivider()
            SwitchSettingRow(
                title = stringResource(R.string.settings_disable_categorization),
                subtitle = stringResource(R.string.settings_disable_categorization_hint),
                checked = !categorizeIntoFolders,
                onCheckedChange = { disabled -> onCategorizeIntoFoldersChanged(!disabled) },
            )
            SettingsDivider()
            SwitchSettingRow(
                title = stringResource(R.string.settings_wifi_only),
                subtitle = stringResource(R.string.settings_wifi_only_hint),
                checked = wifiOnly,
                onCheckedChange = onWifiOnlyChanged,
            )
        }
    }
}

/**
 * Title + current path row with a "Change" button that launches the SAF
 * folder picker (via [onChangeClick], wired to ActivityResultContracts.
 * OpenDocumentTree by the caller) -- same layout as the "Save to" row in
 * AddDownloadDialog/AddTorrentDialog's Advanced section, reused here so
 * picking the default location feels like the same action.
 */
@Composable
private fun DefaultLocationRow(
    path: String,
    onChangeClick: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.settings_default_location),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.settings_default_location_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = path,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onChangeClick,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.torrent_dialog_change_path),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
