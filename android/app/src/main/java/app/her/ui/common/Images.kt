package app.her.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Reads a picked photo, downscales it and re-encodes it as JPEG (also drops EXIF location data). */
suspend fun readScaledJpeg(context: Context, uri: Uri, maxDim: Int = 2048): ByteArray = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) sample *= 2
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("Couldn't read that photo")
    val scale = minOf(1f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height))
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 86, out)
        out.toByteArray()
    }
}

/** A private photo from the server. Never written to the disk cache. */
@Composable
fun PrivateImage(url: String, authHeader: String?, cacheKey: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val request = ImageRequest.Builder(context)
        .data(url)
        .apply { authHeader?.let { addHeader("Authorization", it) } }
        .memoryCacheKey(cacheKey)
        .diskCachePolicy(CachePolicy.DISABLED)
        .crossfade(true)
        .build()
    AsyncImage(model = request, contentDescription = null, modifier = modifier, contentScale = contentScale)
}
