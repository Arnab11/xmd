package com.invictus.xmd.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Not wired into any real screen -- exists purely so Phase 0 (Compose
 * setup) can be verified end-to-end: run `ComposeSmokeTestActivity` or drop
 * [ComposeSmokeTest] into a `ComposeView`/`setContent {}` anywhere to check
 * that the Gradle deps resolve, the compose compiler runs against Kotlin
 * 1.9.24, and [XmdTheme] picks up the currently-applied AppTheme correctly
 * (background/primary/surface colors below should match whichever theme is
 * active, not Compose's baseline purple defaults).
 *
 * Safe to delete once Phase 1 (Settings screens) lands and provides a real
 * on-screen check instead.
 */
@Composable
fun ComposeSmokeTest() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = "XMD \u2014 Compose is alive",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxSize()
                    .height(120.dp),
            ) {}
            Text(
                text = "primaryContainer swatch above should match the active AppTheme, not Compose defaults.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ComposeSmokeTestPreview() {
    XmdTheme {
        ComposeSmokeTest()
    }
}
