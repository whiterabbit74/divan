package com.rdsinteractive.divan.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** One card of a «Каналы» or «Смотреть далее» row, as an app published it to the TV Provider. */
data class TvProgram(
    val id: Long,
    val packageName: String,
    val title: String,
    val subtitle: String?,
    val posterUri: String?,
    /** 16:9 unless the app said otherwise (posters are often 2:3). */
    val aspectRatio: Float,
    val intentUri: String?,
    /** 0..1 for a started movie or episode, null when unknown. */
    val progress: Float?,
)

data class TvChannel(
    val id: Long,
    val packageName: String,
    val name: String,
    val programs: List<TvProgram>,
) {
    /** Kept in settings by name: apps may recreate a channel with a new id, the name stays. */
    val key: String get() = "$packageName/$name"
}

/** Everything apps published, before the user's per-app switches are applied. */
data class TvRowsData(val channels: List<TvChannel>, val watchNext: List<TvProgram>) {
    /** Apps that published anything: the sources listed in settings. */
    val sources: Set<String> get() = (channels.map { it.packageName } + watchNext.map { it.packageName }).toSet()

    companion object {
        val Empty = TvRowsData(emptyList(), emptyList())
    }
}

/**
 * Reads the TV Provider the way a third-party home screen can. Without the privileged
 * ACCESS_ALL_EPG_DATA the provider returns only rows marked searchable, and only to a
 * caller holding READ_TV_LISTINGS; it also refuses any selection, so filtering is done here.
 */
class TvRowsRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    suspend fun load(): TvRowsData = withContext(Dispatchers.IO) {
        val programs = query(TvContract.PreviewPrograms.CONTENT_URI) { it.toProgram(watchNext = false) }
            .filter { it.second.title.isNotBlank() }
        val byChannel = programs.groupBy({ it.first }, { it.second })
        val channels = query(TvContract.Channels.CONTENT_URI) { cursor ->
            if (cursor.string(TvContract.Channels.COLUMN_TYPE) != TvContract.Channels.TYPE_PREVIEW) return@query null
            val id = cursor.long(TvContract.Channels._ID)
            TvChannel(
                id = id,
                packageName = cursor.string(TvContract.Channels.COLUMN_PACKAGE_NAME).orEmpty(),
                name = cursor.string(TvContract.Channels.COLUMN_DISPLAY_NAME).orEmpty(),
                programs = byChannel[id].orEmpty(),
            )
        }.filter { it.programs.isNotEmpty() }
        val watchNext = query(TvContract.WatchNextPrograms.CONTENT_URI) { cursor ->
            cursor.long(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS) to cursor.toProgram(watchNext = true).second
        }.filter { it.second.title.isNotBlank() }.sortedByDescending { it.first }.map { it.second }
        Log.i(TAG, "channels ${channels.size} (${channels.joinToString { "${it.packageName}/${it.name}:${it.programs.size}" }}), watch next ${watchNext.size}")
        TvRowsData(channels, watchNext)
    }

    fun intent(program: TvProgram): Intent? = program.intentUri?.let(::linkIntent)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Decoded posters, already scaled to the card; ~85 on this TV, about 15 MB, all fit. */
    private val posters = object : LruCache<String, ImageBitmap>(POSTER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
    }
    /**
     * The same posters as small JPEGs: after a restart they come from disk, not the network.
     * The folder name carries the decode size; a folder of an older size is dropped.
     */
    private val posterDir = File(context.cacheDir, "posters-$DECODE_HEIGHT_DP")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
        scope.launch {
            context.cacheDir.listFiles { file -> file.name.startsWith("posters") && file != posterDir }
                ?.forEach { it.deleteRecursively() }
            // Rows change daily and Android trims an app's cache only when space runs out: keep
            // the posters used last (DV-034).
            posterDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_DISK_POSTERS)?.forEach { it.delete() }
        }
    }
    private val loading = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    /** Posters the server does not have (YouTube answers 404 for removed videos): not asked again. */
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Posters that failed for a passing reason (busy server, no network): when to try again. */
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val network = Semaphore(NETWORK_SLOTS)
    private val density = context.resources.displayMetrics.density

    /** A poster already in memory, so a card that scrolls in draws it in its very first frame. */
    fun cachedPoster(uri: String): ImageBitmap? = posters.get(uri)

    /** Loads a poster once even if a card and the prefetch ask for it at the same time. */
    suspend fun poster(uri: String): ImageBitmap? {
        posters.get(uri)?.let { return it }
        if (!worthAsking(uri)) return null
        val job = loading.computeIfAbsent(uri) { scope.async { loadPoster(uri) } }
        job.invokeOnCompletion { loading.remove(uri, job) }
        return job.await()
    }

    /**
     * Fetches every poster of the shown rows in the background right after the rows load, so
     * nothing is downloaded, decoded or uploaded to the GPU while the user scrolls.
     */
    fun prefetch(uris: Collection<String>) {
        uris.filter { posters.get(it) == null && worthAsking(it) }.forEach { uri -> scope.launch { poster(uri) } }
    }

    private fun worthAsking(uri: String): Boolean =
        uri !in missing && (retryAt[uri] ?: 0L) <= System.currentTimeMillis()

    private suspend fun loadPoster(uri: String): ImageBitmap? {
        val file = File(posterDir, sha1(uri) + ".jpg")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })?.let {
                file.setLastModified(System.currentTimeMillis())
                return remember(uri, it)
            }
        }
        // One quick retry for a network failure (timeout right after start). «Not found» and a
        // file that is no picture are final; any other failure waits [RETRY_AFTER_MS] (DV-033).
        repeat(2) { attempt ->
            val result = network.withPermit { runCatching { fetchScaled(uri) }.onFailure { Log.w(TAG, "poster $uri: $it") } }
            val bitmap = result.getOrNull()
            val error = result.exceptionOrNull()
            if ((result.isSuccess && bitmap == null) || (error is HttpStatusException && error.isGone)) {
                missing += uri
                return null
            }
            if (error is HttpStatusException) {
                retryAt[uri] = System.currentTimeMillis() + RETRY_AFTER_MS
                return null
            }
            if (bitmap != null) {
                // Into a temporary file first: a TV switched off mid-write leaves no half poster (DV-034).
                runCatching {
                    posterDir.mkdirs()
                    val part = File(posterDir, file.name + ".part")
                    part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    if (!part.renameTo(file)) part.delete()
                }
                return remember(uri, bitmap)
            }
            if (attempt == 0) delay(1_500)
        }
        Log.w(TAG, "no poster: $uri")
        retryAt[uri] = System.currentTimeMillis() + RETRY_AFTER_MS
        return null
    }

    /** prepareToDraw: upload to the GPU now, in the background, not in the frame that shows it. */
    private fun remember(uri: String, bitmap: Bitmap): ImageBitmap {
        bitmap.prepareToDraw()
        return bitmap.asImageBitmap().also { posters.put(uri, it) }
    }

    /** Downloads once, then scales to the card's pixel size (landscape and poster cards differ). */
    private fun fetchScaled(uri: String): Bitmap? {
        val bytes = read(uri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val portrait = bounds.outHeight > bounds.outWidth
        val height = ((if (portrait) POSTER_DECODE_HEIGHT_DP else DECODE_HEIGHT_DP) * density).toInt()
        val width = bounds.outWidth * height / bounds.outHeight
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outHeight, height)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (decoded.height <= height) return decoded
        return Bitmap.createScaledBitmap(decoded, width, height, true).also { if (it !== decoded) decoded.recycle() }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The whole file; [HttpStatusException] on an HTTP error, so an error page is never decoded as a poster. */
    private fun read(uri: String): ByteArray? = when (Uri.parse(uri).scheme) {
        "http", "https" -> httpGet(uri, connectMs = 5_000, readMs = 10_000)
        else -> resolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
    }

    private fun <T> query(uri: Uri, map: (Cursor) -> T?): List<T> = try {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) map(cursor)?.let(::add) }
        }.orEmpty()
    } catch (error: SecurityException) {
        Log.w(TAG, "no access to $uri: ${error.message}")
        emptyList()
    } catch (error: Exception) {
        Log.w(TAG, "cannot read $uri", error)
        emptyList()
    }

    /** Preview and Watch Next programs share their columns; returns (channel id, program). */
    private fun Cursor.toProgram(watchNext: Boolean): Pair<Long, TvProgram> {
        val duration = long(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS)
        val position = long(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
        val ratio = int(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO)
        val channel = if (watchNext) 0L else long(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID)
        return channel to TvProgram(
            id = long(TvContract.PreviewPrograms._ID),
            packageName = string(TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME).orEmpty(),
            title = string(TvContract.PreviewPrograms.COLUMN_TITLE).orEmpty(),
            subtitle = string(TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE)
                ?: string(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION),
            posterUri = string(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI)
                ?: string(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI),
            aspectRatio = ASPECT_RATIOS[ratio] ?: 16f / 9f,
            intentUri = string(TvContract.PreviewPrograms.COLUMN_INTENT_URI),
            progress = if (duration > 0 && position > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else null,
        )
    }

    private fun Cursor.index(column: String) = getColumnIndex(column).takeIf { it >= 0 }
    private fun Cursor.string(column: String): String? = index(column)?.let { getString(it) }?.takeIf { it.isNotBlank() }
    private fun Cursor.long(column: String): Long = index(column)?.let { getLong(it) } ?: 0L
    private fun Cursor.int(column: String): Int = index(column)?.let { getInt(it) } ?: -1

    companion object {
        const val TAG = "DivanRows"
        /** Natural card heights; the carousel then stretches cards to fill the row. */
        const val CARD_HEIGHT_DP = 92
        const val POSTER_CARD_HEIGHT_DP = 140
        /**
         * Pictures are decoded at the largest card size the carousel draws (DV-031: on a 960 dp
         * screen 197×111 dp and 104×156 dp cards, ×1.08 in focus), so they are never stretched.
         */
        private const val DECODE_HEIGHT_DP = 120
        private const val POSTER_DECODE_HEIGHT_DP = 170
        private const val POSTER_CACHE_BYTES = 32 * 1024 * 1024
        /** About 30 KB each: ~9 MB on disk at most. */
        private const val MAX_DISK_POSTERS = 300
        private const val NETWORK_SLOTS = 3
        private const val RETRY_AFTER_MS = 10 * 60 * 1000L

        /** TvContract.PreviewPrograms.ASPECT_RATIO_* → width / height. */
        private val ASPECT_RATIOS = mapOf<Int, Float>(
            TvContract.PreviewPrograms.ASPECT_RATIO_16_9 to 16f / 9f,
            TvContract.PreviewPrograms.ASPECT_RATIO_3_2 to 3f / 2f,
            TvContract.PreviewPrograms.ASPECT_RATIO_4_3 to 4f / 3f,
            TvContract.PreviewPrograms.ASPECT_RATIO_1_1 to 1f,
            TvContract.PreviewPrograms.ASPECT_RATIO_2_3 to 2f / 3f,
            5 to 1f / 1.441f, // ASPECT_RATIO_MOVIE_POSTER, API 28 (the TV is on 31, the SDK constant is hidden)
        )
    }
}
