package com.invictus.xmd.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.invictus.xmd.ui.icons.Icon
import com.invictus.xmd.ui.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.xmd.R
import com.invictus.xmd.core.FaviconLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Phase 5 (Browser) conversion of the old showTabsDialog() -- previously a
 * BottomSheetDialog hand-building one MaterialCardView pill row per tab
 * inside a plain LinearLayout. Kept the same single-column pill-list layout
 * (not the Chrome-style grid this migration originally sketched -- a
 * deliberate scope call, see COMPOSE_MIGRATION.md) and the same
 * favicon+title row content (no page thumbnails).
 *
 * Hosted in tabsListOverlay, a dedicated full-bleed ComposeView sibling to
 * browserDialogHost (not reusing that host) -- this is an overlay with real
 * on-screen bounds anchored in fragment_browser.xml, not a Dialog-window
 * popup, same reasoning Phase 5 Step 3 used for AddressBarSuggestions.
 *
 * BrowserTabState (BrowserViewModel.tabs) isn't Compose-observable, so
 * BrowserFragment owns a one-shot [tabs] snapshot here -- mirrors the
 * sniffedSheetStreams/suggestionItems pattern already used elsewhere in
 * this Fragment -- refreshed explicitly after every mutation (open/close)
 * rather than this composable reading BrowserViewModel directly.
 *
 * "New tab" used to be its own FloatingActionButton in a dedicated row
 * below the list -- pushed well clear of the last tab row and needing an
 * 80dp bottom-padding reservation to sit above the app's own bottom nav.
 * It's now an IconButton in the header instead (Chrome/Firefox tab-switcher
 * convention: title + add, top-aligned), which is what let the bottom
 * reservation shrink to just [navigationBarsPadding] -- see the header Row
 * and the Column's padding below.
 */
data class TabOverlayItem(
    val id: Long,
    val title: String,
    val url: String?,
    val isPrivate: Boolean,
)

// Private tabs get a fixed dark tonal treatment regardless of app theme --
// same idea as Chrome's distinct grey/black incognito tab strip, ported
// unchanged from the old dialog's hardcoded hex values.
private val PrivateTabActiveColor = Color(0xFF3A3A3A)
private val PrivateTabInactiveColor = Color(0xFF2A2A2A)

@Composable
fun TabsListOverlay(
    visible: Boolean,
    tabs: List<TabOverlayItem>,
    currentTabId: Long?,
    onSwitch: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onAddNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(120)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        // Swallows taps that land on panel whitespace so
                        // they don't fall through to the scrim's dismiss
                        // click behind it.
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.tabs_overlay_header, tabs.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onAddNew) {
                            Icon(
                                imageVector = Icons.Add,
                                contentDescription = stringResource(R.string.action_new_tab),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            TabRow(
                                item = tab,
                                index = index,
                                isActive = tab.id == currentTabId,
                                onClick = { onSwitch(tab.id) },
                                onCloseClick = { onClose(tab.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabRow(
    item: TabOverlayItem,
    index: Int,
    isActive: Boolean,
    onClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    // Small staggered fade-in so the list doesn't just pop in -- same idea
    // as the old dialog's row.animate().alpha(1f)... entrance, minus the
    // translationY rise (fade-only; not reproduced 1:1, a deliberate
    // simplification).
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(item.id) {
        delay((index * 24L).coerceAtMost(200L))
        alpha.animateTo(1f, tween(160))
    }

    val tonalColor = when {
        item.isPrivate -> if (isActive) PrivateTabActiveColor else PrivateTabInactiveColor
        isActive -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val onTonalColor = when {
        item.isPrivate -> Color.White
        isActive -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    // Swipe-to-close in either direction, same haptic-on-confirm pattern
    // DownloadsScreen's queue-item swipe-to-clear already uses -- see that
    // file's rememberSwipeToDismissBoxState call for the twin of this one.
    val haptics = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd || value == SwipeToDismissBoxValue.EndToStart) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onCloseClick()
                true
            } else {
                false
            }
        },
    )
    // Tab list entries get reused/recomposed by id as tabs open and close
    // (same reasoning as the queue-item version) -- without this, a row
    // that reused a just-closed tab's slot could inherit a mid-swipe state.
    LaunchedEffect(item.id) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha.value),
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val alignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment,
            ) {
                Icon(
                    imageVector = Icons.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            color = tonalColor,
            shape = RoundedCornerShape(28.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 10.dp),
            ) {
                TabFavicon(item = item, tint = onTonalColor)
                Text(
                    text = item.title.ifBlank { item.url ?: stringResource(R.string.action_new_tab) },
                    color = onTonalColor,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                IconButton(onClick = onCloseClick) {
                    Icon(
                        imageVector = Icons.Close,
                        contentDescription = stringResource(R.string.action_dismiss),
                        tint = onTonalColor,
                    )
                }
            }
        }
    }
}

/**
 * Private tabs always show the incognito glyph, never the site's real
 * favicon -- fetching/showing it here would be a minor but real leak of
 * what a "private" tab is looking at (ported unchanged from the old
 * dialog's same check).
 */
@Composable
private fun TabFavicon(item: TabOverlayItem, tint: Color) {
    val bitmapState = if (!item.isPrivate && item.url != null) {
        produceState<android.graphics.Bitmap?>(initialValue = null, key1 = item.url) {
            value = withContext(Dispatchers.IO) { FaviconLoader.load(item.url) }
        }
    } else null

    Box(
        modifier = Modifier
            .size(28.dp)
            .background(color = Color.White, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = bitmapState?.value
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Icon(
                imageVector = if (item.isPrivate) Icons.VisibilityOff else Icons.Link,
                contentDescription = null,
                tint = Color(0xFF1A1A1A),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
