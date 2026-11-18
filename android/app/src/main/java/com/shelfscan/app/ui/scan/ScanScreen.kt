package com.shelfscan.app.ui.scan

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.shelfscan.app.ui.components.BookCover
import com.shelfscan.app.ui.components.EditBookDialog
import com.shelfscan.app.data.PendingScan
import com.shelfscan.app.util.BookBarcodeScanner
import com.shelfscan.app.util.ImageUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onSearch: () -> Unit,
    vm: ScanViewModel = viewModel(factory = ScanViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); vm.dismissError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.step is ScanStep.Review) "Review books" else "Add books") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val step = state.step) {
                ScanStep.Pick -> PickPhotos(
                    state.photos, vm::addPhotos, vm::removePhoto, vm::identify,
                    onBarcode = vm::lookupBarcode, onBarcodeError = vm::showError, onSearch = onSearch,
                    pending = pending, onReviewPending = { vm.reviewPending(it) }, onDiscardPending = { vm.discardPending(it) },
                )
                is ScanStep.Queued -> Queued(step.photoCount, onScanMore = vm::reset, onDone = onFinished)
                ScanStep.Identifying -> Identifying(state.photos.size)
                ScanStep.LookingUp -> Busy("Looking up that book…", "Finding the title, author and cover for this ISBN.")
                is ScanStep.Review -> Review(step, vm::toggle, vm::edit, vm::save, onRetake = vm::reset)
                is ScanStep.Done -> Done(step.added, onScanMore = vm::reset, onFinished = onFinished)
            }
        }
    }
}

@Composable
private fun PickPhotos(
    photos: List<Uri>,
    onAdd: (List<Uri>) -> Unit,
    onRemove: (Uri) -> Unit,
    onIdentify: () -> Unit,
    onBarcode: (String) -> Unit,
    onBarcodeError: (String) -> Unit,
    onSearch: () -> Unit,
    pending: List<PendingScan>,
    onReviewPending: (PendingScan) -> Unit,
    onDiscardPending: (PendingScan) -> Unit,
) {
    val context = LocalContext.current
    val scanner = remember(context) { BookBarcodeScanner(context) }
    // Fetch the scanner module now if it's missing, so it's ready by the time Barcode is tapped.
    LaunchedEffect(scanner) { scanner.prepare() }
    var pendingCapture by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pendingCapture?.takeIf { ok }?.let { onAdd(listOf(it)) }
        pendingCapture = null
    }
    val remaining = MAX_PHOTOS - photos.size
    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)
    ) { uris -> if (uris.isNotEmpty()) onAdd(uris) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (pending.isNotEmpty()) {
            PendingScans(pending, onReviewPending, onDiscardPending)
            Spacer(Modifier.height(16.dp))
        }
        Text(
            "Snap covers or a row of spines. Up to $MAX_PHOTOS photos at a time — good light and straight-on shots work best.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val uri = ImageUtils.newCaptureUri(context)
                    pendingCapture = uri
                    camera.launch(uri)
                },
                enabled = remaining > 0,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Take photo")
            }
            OutlinedButton(
                onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = remaining > 0,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Gallery")
            }
        }
        Spacer(Modifier.height(8.dp))
        // One book at a time: its barcode, or its title typed in.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { scanner.scan(onCode = onBarcode, onWaiting = onBarcodeError, onError = onBarcodeError) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Barcode")
            }
            OutlinedButton(onClick = onSearch, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Search, null); Spacer(Modifier.width(8.dp)); Text("Type a title")
            }
        }
        Spacer(Modifier.height(16.dp))

        if (photos.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "Your photos will appear here", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(photos, key = { it.toString() }) { uri ->
                    Box {
                        AsyncImage(
                            model = uri, contentDescription = "Selected photo", contentScale = ContentScale.Crop,
                            modifier = Modifier.aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp)),
                        )
                        FilledIconButton(
                            onClick = { onRemove(uri) },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp),
                        ) { Icon(Icons.Filled.Close, "Remove photo", Modifier.size(16.dp)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onIdentify,
            enabled = photos.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (photos.size > 1) "Identify books in ${photos.size} photos" else "Identify books")
        }
    }
}

/** Scans saved while the server was unreachable: read ones can be reviewed, any can be thrown away. */
@Composable
private fun PendingScans(scans: List<PendingScan>, onReview: (PendingScan) -> Unit, onDiscard: (PendingScan) -> Unit) {
    var confirming by remember { mutableStateOf<PendingScan?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            scans.forEach { scan ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(
                            "${scan.photoCount} photo${if (scan.photoCount == 1) "" else "s"} \u00b7 " +
                                DateUtils.getRelativeTimeSpanString(scan.createdAt),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            when {
                                scan.isReady -> "Ready to review \u00b7 ${scan.books.size} book${if (scan.books.size == 1) "" else "s"} found"
                                scan.isFailed -> "Couldn't be read${scan.lastError?.let { ": $it" }.orEmpty()}"
                                else -> "Waiting for the server"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    if (scan.isReady) TextButton(onClick = { onReview(scan) }) { Text("Review") }
                    IconButton(onClick = { confirming = scan }) {
                        Icon(Icons.Outlined.Delete, "Discard", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
    confirming?.let { scan ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Discard this scan?") },
            text = { Text("Its ${scan.photoCount} photo${if (scan.photoCount == 1) "" else "s"} will be deleted.") },
            confirmButton = { TextButton(onClick = { onDiscard(scan); confirming = null }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Keep") } },
        )
    }
}

/** The server couldn't be reached, so the photos were kept for later instead of failing. */
@Composable
private fun Queued(photoCount: Int, onScanMore: () -> Unit, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.CloudUpload, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Saved for later", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Can't reach the Shelfsight server right now. Your ${if (photoCount == 1) "photo is" else "$photoCount photos are"} " +
                "saved and will be read automatically once it's back. They'll wait here under Add books for you to review.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onScanMore, modifier = Modifier.fillMaxWidth()) { Text("Scan more") }
    }
}

@Composable
private fun Busy(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Identifying(photoCount: Int) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text("Reading your shelf…", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Identifying books in $photoCount photo${if (photoCount == 1) "" else "s"} and looking up details. This can take up to a minute.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Review(
    step: ScanStep.Review,
    onToggle: (Int) -> Unit,
    onEdit: (Int, String, String, List<String>) -> Unit,
    onSave: () -> Unit,
    onRetake: () -> Unit,
) {
    var editing by remember { mutableStateOf<Candidate?>(null) }
    val selectedCount = step.candidates.count { it.selected }

    if (step.candidates.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No books found", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                step.notes ?: "Try a closer, brighter photo with the titles facing the camera.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetake) { Text("Try again") }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            item {
                Text(
                    "Found ${step.candidates.size} books. Uncheck anything wrong, or tap the pencil to fix it.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                step.notes?.let { NoteCard(it) }
            }
            items(step.candidates, key = { it.id }) { c ->
                CandidateRow(c, onToggle = { onToggle(c.id) }, onEdit = { editing = c })
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("Start over") }
                Button(onClick = onSave, enabled = selectedCount > 0, modifier = Modifier.weight(1.6f)) {
                    Text("Add $selectedCount to library")
                }
            }
        }
    }

    editing?.let { c ->
        EditBookDialog(
            title = c.book.title, author = c.book.author, genres = c.book.genres,
            onDismiss = { editing = null },
            onSave = { t, a, g -> onEdit(c.id, t, a, g); editing = null },
        )
    }
}

@Composable
private fun NoteCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun CandidateRow(c: Candidate, onToggle: () -> Unit, onEdit: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !c.alreadyOwned, onClick = onToggle),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(c.book.coverUrl, c.book.title, c.book.author, Modifier.width(52.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(c.book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val tag = when {
                    c.alreadyOwned -> "Already in your library"
                    c.lowConfidence -> "Not sure about this one — check it"
                    else -> c.book.genres.take(2).joinToString(" · ")
                }
                if (tag.isNotEmpty()) {
                    Text(
                        tag, style = MaterialTheme.typography.labelMedium,
                        color = if (c.lowConfidence && !c.alreadyOwned) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (c.alreadyOwned) {
                Icon(Icons.Filled.CheckCircle, "Already owned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
            } else {
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit ${c.book.title}") }
                Checkbox(checked = c.selected, onCheckedChange = { onToggle() })
            }
        }
    }
}

@Composable
private fun Done(added: Int, onScanMore: () -> Unit, onFinished: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (added == 1) "1 book added" else "$added books added",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) { Text("View library") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onScanMore, modifier = Modifier.fillMaxWidth()) { Text("Scan more") }
    }
}
