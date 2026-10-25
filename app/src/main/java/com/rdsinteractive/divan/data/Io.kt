package com.rdsinteractive.divan.data

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Pictures Divan picks up from its folders (wallpapers, icons). */
val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

fun listImages(dir: String): List<File> =
    File(dir).listFiles { file -> file.extension.lowercase() in IMAGE_EXTENSIONS }?.sortedBy { it.name }.orEmpty()

/** Largest power-of-two subsample that keeps [size] px at or above [target] px. */
fun sampleSize(size: Int, target: Int): Int {
    var sample = 1
    while (size / (sample * 2) >= target) sample *= 2
    return sample
}

/** A picture file decoded at no less than [targetWidth] px wide (up to twice that), or null. */
fun decodeSampled(path: String, targetWidth: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, targetWidth)
        inPreferredConfig = config
    }
    return BitmapFactory.decodeFile(path, options)
}

/** A server answer other than 200: an error page is never read as data. */
class HttpStatusException(val code: Int) : IOException("HTTP $code") {
    /** The server says the thing is gone for good, not that it is busy. */
    val isGone: Boolean get() = code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE
}

/** The body of a GET. Blocking; throws [HttpStatusException] on any status but 200. */
fun httpGet(url: String, connectMs: Int, readMs: Int = connectMs): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = connectMs
        connection.readTimeout = readMs
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            Log.w("DivanHttp", "$url: HTTP ${connection.responseCode}")
            throw HttpStatusException(connection.responseCode)
        }
        connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}

/**
 * A link from a rule or a TV row. `parseUri` turns `intent:…` and `android-app://package/…` into
 * the intent they describe (component, extras) and any plain link into a VIEW; a link it cannot
 * parse is still viewed (DV-029: `android-app:` from TV rows must not become a VIEW).
 */
fun linkIntent(uri: String): Intent =
    runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
        ?: Intent(Intent.ACTION_VIEW, Uri.parse(uri))
