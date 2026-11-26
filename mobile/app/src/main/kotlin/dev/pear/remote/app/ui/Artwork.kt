package dev.pear.remote.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Album art over https only (the URL was validated by the codec). Small
 * in-memory cache, 5 MiB cap per image, no disk cache, no extra library.
 */
private object ArtCache {
    val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5_000
                c.readTimeout = 5_000
                c.instanceFollowRedirects = false
                try {
                    if (c.responseCode != 200) return@withContext null
                    val out = ByteArrayOutputStream()
                    c.inputStream.use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > 5 * 1024 * 1024) return@withContext null
                        }
                    }
                    val bytes = out.toByteArray()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { cache.put(url, it) }
                } finally {
                    c.disconnect()
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

@Composable
fun Artwork(url: String?, contentDescription: String?, modifier: Modifier = Modifier) {
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) { bmp = url?.let { ArtCache.load(it) } }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MusicNote, contentDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
