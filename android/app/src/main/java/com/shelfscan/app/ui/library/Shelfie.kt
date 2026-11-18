package com.shelfscan.app.ui.library

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.shelfscan.app.data.Book
import com.shelfscan.app.data.ShelfStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Preview of the shelfie card, with a Share button. */
@Composable
fun ShelfieDialog(loadBooks: suspend () -> List<Book>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var card by remember { mutableStateOf<Pair<Bitmap, ShelfStats>?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            val books = loadBooks()
            val stats = ShelfStats.of(books)
            val picks = ShelfStats.coverPicks(books)
            val covers = loadCovers(context, picks)
            withContext(Dispatchers.Default) { ShelfieCard.render(stats, picks.zip(covers)) } to stats
        }.onSuccess { card = it }.onFailure { failed = true }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share your shelf") },
        text = {
            Box(Modifier.fillMaxWidth().aspectRatio(ShelfieCard.WIDTH / ShelfieCard.HEIGHT.toFloat()), contentAlignment = Alignment.Center) {
                val ready = card
                when {
                    ready != null -> Image(
                        ready.first.asImageBitmap(),
                        contentDescription = ready.second.headline,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    )
                    failed -> Text("Couldn't make the card. Try again in a moment.")
                    else -> CircularProgressIndicator()
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = card != null,
                onClick = { card?.let { (bitmap, stats) -> scope.launch { share(context, bitmap, stats) } } },
            ) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Covers from Coil's cache (already downloaded for the library) or the network; null where there's none. */
private suspend fun loadCovers(context: Context, books: List<Book>): List<Bitmap?> = coroutineScope {
    val loader = context.imageLoader
    books.map { book ->
        async {
            val url = book.coverUrl ?: return@async null
            val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(400, 600).build()
            withTimeoutOrNull(COVER_TIMEOUT_MS) {
                (loader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
            }
        }
    }.awaitAll()
}

private const val COVER_TIMEOUT_MS = 8_000L

private suspend fun share(context: Context, bitmap: Bitmap, stats: ShelfStats) {
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shelfie").apply { mkdirs() }
        val file = File(dir, "shelfie.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, "${stats.headline}. Scanned with Shelfsight.")
        clipData = ClipData.newRawUri(null, uri) // lets the share sheet show a preview of the image
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share your shelf"))
}

/** Draws the 4:5 card (the shape Instagram and most feeds show uncropped) with plain Android graphics. */
object ShelfieCard {
    const val WIDTH = 1080
    const val HEIGHT = 1350

    // The app's library palette: bottle-green cloth, cream paper, brass.
    private const val INK_TOP = 0xFF24483A.toInt()
    private const val INK_BOTTOM = 0xFF12271D.toInt()
    private const val PAPER = 0xFFFBF7F0.toInt()
    private const val BRASS = 0xFFE8C47A.toInt()
    private const val PLANK = 0xFF8A6424.toInt()
    private val CLOTH = intArrayOf(0xFF7A2E2E.toInt(), 0xFF2E4A7A.toInt(), 0xFFB7862F.toInt(), 0xFF4D6B3C.toInt(), 0xFF5B3A6B.toInt())

    private const val PAD = 72f
    private const val MAX_COVERS = 8
    private const val COLUMNS = 4

    private val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    private val sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    fun render(stats: ShelfStats, covers: List<Pair<Book, Bitmap?>>): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), INK_TOP, INK_BOTTOM, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bg)

        val usable = WIDTH - 2 * PAD
        text(canvas, "MY SHELF", PAD, 132f, 30f, sansBold, BRASS, usable, letterSpacing = 0.25f)
        text(canvas, stats.bookCount, PAD, 262f, 120f, serifBold, PAPER, usable)
        val sub = listOfNotNull(stats.genreLine, "${stats.readPercent}% read").joinToString("  ·  ")
        text(canvas, sub, PAD, 334f, 46f, serif, withAlpha(PAPER, 0.88f), usable)

        drawShelves(canvas, covers.take(MAX_COVERS), top = 400f, bottom = 1060f)

        val columns = listOf(
            stats.authors.toString() to if (stats.authors == 1) "AUTHOR" else "AUTHORS",
            stats.read.toString() to "READ",
            stats.reading.toString() to "READING NOW",
        )
        val colWidth = usable / columns.size
        columns.forEachIndexed { i, (value, label) ->
            val x = PAD + i * colWidth
            text(canvas, value, x, 1146f, 60f, serifBold, PAPER, colWidth - 16f)
            text(canvas, label, x, 1188f, 24f, sansBold, BRASS, colWidth - 16f, letterSpacing = 0.15f)
        }
        stats.topAuthor?.let {
            text(canvas, "Most on the shelf: $it (${stats.topAuthorCount})", PAD, 1246f, 32f, serif, withAlpha(PAPER, 0.8f), usable)
        }
        text(canvas, "Shelfsight", PAD, 1300f, 30f, serifBold, BRASS, usable)
        val brandWidth = paint(30f, serifBold, BRASS).measureText("Shelfsight")
        text(canvas, "  ·  scan your shelf", PAD + brandWidth, 1300f, 28f, serif, withAlpha(PAPER, 0.6f), usable - brandWidth)
        return bitmap
    }

    /** Covers standing on brass-brown planks, up to four a row, each row centered. */
    private fun drawShelves(canvas: Canvas, covers: List<Pair<Book, Bitmap?>>, top: Float, bottom: Float) {
        if (covers.isEmpty()) return
        val rows = ceil(covers.size / COLUMNS.toFloat()).toInt()
        val gap = 24f
        val plank = 14f
        val rowGap = 44f
        val maxCoverH = (bottom - top - (rows - 1) * rowGap) / rows - plank
        val coverW = min((WIDTH - 2 * PAD - (COLUMNS - 1) * gap) / COLUMNS, maxCoverH * 2f / 3f)
        val coverH = coverW * 1.5f
        val blockH = rows * (coverH + plank) + (rows - 1) * rowGap
        var y = top + (bottom - top - blockH) / 2f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; setShadowLayer(18f, 0f, 10f, 0x99000000.toInt()) }
        val plankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLANK }

        covers.chunked(COLUMNS).forEach { row ->
            val rowW = row.size * coverW + (row.size - 1) * gap
            var x = (WIDTH - rowW) / 2f
            row.forEach { (book, image) ->
                val rect = RectF(x, y, x + coverW, y + coverH)
                canvas.drawRoundRect(rect, 8f, 8f, shadow)
                drawCover(canvas, rect, book, image)
                x += coverW + gap
            }
            val plankLeft = max(PAD, (WIDTH - rowW) / 2f - 36f)
            canvas.drawRoundRect(RectF(plankLeft, y + coverH, WIDTH - plankLeft, y + coverH + plank), 4f, 4f, plankPaint)
            y += coverH + plank + rowGap
        }
    }

    private fun drawCover(canvas: Canvas, rect: RectF, book: Book, image: Bitmap?) {
        val clip = Path().apply {
            // Book-shaped: a tighter spine edge on the left, like the covers in the app.
            addRoundRect(rect, floatArrayOf(4f, 4f, 12f, 12f, 12f, 12f, 4f, 4f), Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clip)
        if (image != null) {
            canvas.drawBitmap(image, centerCrop(image, rect), rect, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val cloth = CLOTH[Math.floorMod(book.title.hashCode(), CLOTH.size)]
            canvas.drawRect(rect, Paint().apply { color = cloth })
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = PAPER; textSize = rect.width() / 7f; typeface = serifBold }
            val width = (rect.width() - 24f).toInt()
            val layout = StaticLayout.Builder.obtain(book.title, 0, book.title.length, tp, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build()
            canvas.save()
            canvas.translate(rect.left + 12f, rect.top + 20f)
            layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    /** The part of the image that fills rect without stretching. */
    private fun centerCrop(image: Bitmap, rect: RectF): Rect {
        val target = rect.width() / rect.height()
        val source = image.width / image.height.toFloat()
        return if (source > target) {
            val w = (image.height * target).toInt()
            val left = (image.width - w) / 2
            Rect(left, 0, left + w, image.height)
        } else {
            val h = (image.width / target).toInt()
            val top = (image.height - h) / 2
            Rect(0, top, image.width, top + h)
        }
    }

    private fun paint(size: Float, face: Typeface, color: Int, letterSpacing: Float = 0f) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = face
            this.color = color
            this.letterSpacing = letterSpacing
        }

    /** One line of text, shortened with an ellipsis if it doesn't fit. */
    private fun text(canvas: Canvas, value: String, x: Float, baseline: Float, size: Float, face: Typeface, color: Int, maxWidth: Float, letterSpacing: Float = 0f) {
        val p = paint(size, face, color, letterSpacing)
        val fitted = TextUtils.ellipsize(value, p, maxWidth, TextUtils.TruncateAt.END).toString()
        canvas.drawText(fitted, x, baseline, p)
    }

    private fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
}
