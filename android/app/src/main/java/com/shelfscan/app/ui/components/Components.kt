package com.shelfscan.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/** Book cover with a typeset fallback when there's no image (or it fails to load). */
@Composable
fun BookCover(url: String?, title: String, author: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 6.dp, bottomEnd = 6.dp)
    Box(modifier.aspectRatio(2f / 3f).clip(shape)) {
        val context = LocalContext.current
        SubcomposeAsyncImage(
            // Fade covers in: large Open Library covers arrive a beat after the placeholder.
            model = remember(url) { ImageRequest.Builder(context).data(url).crossfade(true).build() },
            contentDescription = "Cover of $title",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { PlaceholderCover(title, author) },
            error = { PlaceholderCover(title, author) },
        )
    }
}

@Composable
private fun PlaceholderCover(title: String, author: String) {
    // Pick one of a few cloth colors from the title so the shelf isn't monotone.
    val palette = listOf(
        MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary,
        MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.onSecondary,
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
    )
    val (bg, fg) = palette[Math.floorMod(title.hashCode(), palette.size)]
    Column(
        Modifier.fillMaxSize().background(bg).padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title, color = fg, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Serif),
            textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis,
        )
        Text(
            author, color = fg.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector = Icons.AutoMirrored.Outlined.MenuBook,
    title: String,
    body: String,
    action: String? = null,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.width(56.dp).height(56.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

/** Fix a misread title/author/genres before (or after) saving. */
@Composable
fun EditBookDialog(
    title: String,
    author: String,
    genres: List<String>,
    onDismiss: () -> Unit,
    onSave: (title: String, author: String, genres: List<String>) -> Unit,
) {
    var t by remember { mutableStateOf(title) }
    var a by remember { mutableStateOf(author) }
    var g by remember { mutableStateOf(genres.joinToString(", ")) }
    val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(t, { t = it }, label = { Text("Title") }, singleLine = true, keyboardOptions = words, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(a, { a = it }, label = { Text("Author") }, singleLine = true, keyboardOptions = words, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(g, { g = it }, label = { Text("Genres (comma separated)") }, keyboardOptions = words, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = t.isNotBlank() && a.isNotBlank(),
                onClick = { onSave(t.trim(), a.trim(), g.split(",").map { it.trim() }.filter { it.isNotEmpty() }) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
