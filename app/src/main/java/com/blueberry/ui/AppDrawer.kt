package com.blueberry.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.blueberry.router.AppEntry
import com.blueberry.router.Catalogue

/**
 * The escape hatch.
 *
 * Built first and on purpose: when the router misbehaves this is what keeps the phone usable, so it
 * must work with no voice, no model and no network.
 */
@Composable
fun AppDrawer(
    catalogue: Catalogue,
    iconFor: (String) -> Drawable?,
    onLaunch: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val apps = remember(catalogue, query) {
        if (query.isBlank()) {
            catalogue.apps.sortedBy { it.label.lowercase() }
        } else {
            // Prefix matches first — they are what the user meant — then anything containing it.
            val needle = Catalogue.normalizeLabel(query)
            val prefix = catalogue.labelsStartingWith(query, limit = 200).toSet()
            prefix.sortedBy { it.label.lowercase() } +
                catalogue.apps
                    .filter { it !in prefix && Catalogue.normalizeLabel(it.label).contains(needle) }
                    .sortedBy { it.label.lowercase() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search apps") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )

        if (apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text(
                    text = if (catalogue.size == 0) "No apps found." else "Nothing matches \"$query\".",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 48.dp),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(app, iconFor) { onLaunch(app.packageName) }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, iconFor: (String) -> Drawable?, onClick: () -> Unit) {
    val icon: ImageBitmap? = remember(app.packageName) {
        runCatching { iconFor(app.packageName)?.toBitmap(ICON_PX, ICON_PX)?.asImageBitmap() }.getOrNull()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(40.dp),
                )
            } else {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val ICON_PX = 128
