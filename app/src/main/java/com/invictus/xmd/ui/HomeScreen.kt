package com.invictus.xmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import com.invictus.xmd.ui.icons.Icon
import com.invictus.xmd.ui.icons.Icons
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.invictus.xmd.R

internal data class HomeQuickStats(
    val downloading: Int = 0,
    val paused: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
) {
    val isEmpty: Boolean
        get() = downloading == 0 && paused == 0 && done == 0 && failed == 0
}

@Composable
internal fun HomeScreen(
    linksText: String,
    onLinksTextChange: (String) -> Unit,
    clipboardLink: String?,
    quickStats: HomeQuickStats,
    needsPrepare: Boolean,
    onClipboardAdd: () -> Unit,
    onClipboardDismiss: () -> Unit,
    onPasteClipboard: () -> Unit,
    onAddTorrent: () -> Unit,
    onPrepare: () -> Unit,
    onDownload: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        clipboardLink?.let { link ->
            ClipboardLinkBanner(
                link = link,
                onAdd = onClipboardAdd,
                onDismiss = onClipboardDismiss,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionLabel()
            Spacer(Modifier.height(12.dp))

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // URL input row -- plain borderless multiline field, no
                    // box/outline of its own; the card itself is the outline.
                    // Trailing icon: manual paste from clipboard into the
                    // field (any text). The banner above handles
                    // auto-detected recognized links separately.
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            imageVector = Icons.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 11.dp, end = 12.dp)
                                .size(18.dp),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            BasicTextField(
                                value = linksText,
                                onValueChange = onLinksTextChange,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    lineHeight = 18.sp,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                minLines = 4,
                                maxLines = 8,
                                decorationBox = { innerTextField ->
                                    if (linksText.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.hint_links),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 14.sp,
                                            lineHeight = 18.sp,
                                        )
                                    }
                                    innerTextField()
                                },
                            )
                        }
                        IconButton(
                            onClick = onPasteClipboard,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Paste,
                                contentDescription = stringResource(R.string.action_paste),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    // Divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp, bottom = 12.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )

                    // Action buttons row: torrent icon | Prepare | Download
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalIconButton(
                            onClick = onAddTorrent,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape),
                        ) {
                            Icon(
                                imageVector = Icons.Torrent,
                                contentDescription = stringResource(R.string.action_add_torrent),
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        if (needsPrepare) {
                            FilledTonalButton(
                                onClick = onPrepare,
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Text(stringResource(R.string.action_prepare))
                            }
                        }

                        Button(onClick = onDownload) {
                            Text(
                                stringResource(
                                    if (needsPrepare) R.string.action_download
                                    else R.string.action_download_direct
                                )
                            )
                        }
                    }
                }
            }

            if (!quickStats.isEmpty) {
                Spacer(Modifier.height(16.dp))
                QuickStatsPill(stats = quickStats, onClick = onOpenDownloads)
            }

            // Empty hint -- just two centered text lines, no icon, matching
            // the original fragment_home.xml exactly.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.home_paste_links_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Text(
                    text = stringResource(R.string.home_supported_sources),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ClipboardLinkBanner(
    link: String,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.clipboard_link_detected, link),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onAdd) {
                Text(stringResource(R.string.action_add))
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.section_add_new_download),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Compact tappable status pill -- matches the original Widget.Xmd.StatusPill:
 * a small wrap-content, fully-rounded tertiaryContainer capsule with bold
 * 12sp text, not a full-width card.
 */
@Composable
private fun QuickStatsPill(stats: HomeQuickStats, onClick: () -> Unit) {
    val parts = mutableListOf<String>()
    if (stats.downloading > 0) {
        parts += stringResource(R.string.home_stat_downloading, stats.downloading)
    }
    if (stats.paused > 0) {
        parts += stringResource(R.string.home_stat_paused, stats.paused)
    }
    if (stats.done > 0) {
        parts += stringResource(R.string.home_stat_done, stats.done)
    }
    if (stats.failed > 0) {
        parts += stringResource(R.string.home_stat_failed, stats.failed)
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.wrapContentWidth(),
        shape = RoundedCornerShape(100.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = parts.joinToString("  •  "),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}