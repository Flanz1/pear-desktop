package dev.pear.remote.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pear.remote.app.PearRemoteApp
import dev.pear.remote.protocol.Limits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Neutral placeholder where a cover would go: shown until a cover has
 * loaded, and when there is none.
 */
@Composable
fun ArtPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.4f).padding(4.dp),
        )
    }
}

/**
 * A cover from a thumbnail ref (SPEC 10.6). The bytes come from the paired
 * desktop through catalog.thumb, never from the internet: the app's only
 * network peer is the desktop, and the desktop's thumbnailUrl stays ignored.
 *
 * Loading starts when this is composed (lazy lists compose only rows on or
 * near the screen) and is cancelled when it leaves, so only visible covers
 * are requested. Decoded covers are kept in a small in-memory LRU.
 */
@Composable
fun Cover(thumb: String?, modifier: Modifier = Modifier) {
    if (thumb == null) {
        ArtPlaceholder(modifier)
        return
    }
    val thumbnails = (LocalContext.current.applicationContext as PearRemoteApp).controller.thumbnails
    var bitmap by remember(thumb) { mutableStateOf(CoverCache.get(thumb)) }
    if (bitmap == null) {
        LaunchedEffect(thumb) {
            val bytes = thumbnails.load(thumb) ?: return@LaunchedEffect
            val decoded = withContext(Dispatchers.Default) { decodeCover(bytes) }
            if (decoded == null) {
                thumbnails.reportBad(thumb)
            } else {
                val image = decoded.asImageBitmap()
                CoverCache.put(thumb, image)
                bitmap = image
            }
        }
    }
    val b = bitmap
    if (b == null) {
        ArtPlaceholder(modifier)
    } else {
        Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

/** Decoded covers by thumbnail ref. At most ~25 MB (160 x 160 x 4 bytes each). */
private object CoverCache {
    private val lru = LruCache<String, ImageBitmap>(250)
    fun get(ref: String): ImageBitmap? = lru.get(ref)
    fun put(ref: String, b: ImageBitmap) {
        lru.put(ref, b)
    }
}

/**
 * Decodes thumbnail bytes defensively: at most 24 KiB, and the image's
 * declared size is checked before any pixels are allocated (at most 160 x
 * 160, SPEC 10.6). Anything else counts as a missing cover.
 */
fun decodeCover(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty() || bytes.size > Limits.THUMB_MAX_BYTES) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val max = Limits.THUMB_MAX_PIXELS
        if (bounds.outWidth !in 1..max || bounds.outHeight !in 1..max) return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options())
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}
