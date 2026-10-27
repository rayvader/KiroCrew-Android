package io.github.rayvader.kirocrew

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.IntentCompat
import io.github.rayvader.kirocrew.core.ShareRules
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One file another app shared, copied into the app's cache. */
class SharedFile(val file: File, val name: String, val type: String, val size: Long)

/** What another app shared, waiting to go into the chat box. [token] ties the page's calls to it. */
class SharedItem(val text: String, val files: List<SharedFile>) {
    val token: String = UUID.randomUUID().toString()
    val claimed = AtomicBoolean(false)
}

/** Reads "Share to Kiro Crew" from other apps. */
object ShareInbox {
    sealed interface Result {
        data class Ready(val item: SharedItem) : Result
        data object Empty : Result
        data object TooBig : Result
        data object Unreadable : Result
    }

    private const val CHUNK = 512 * 1024

    fun isShare(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE

    /** Copies the shared files into the cache. Slow: call it off the main thread. */
    fun read(ctx: Context, intent: Intent): Result {
        val text = ShareRules.draft(
            intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        )
        val uris = streams(intent)
        if (uris.size > ShareRules.MAX_FILES) return Result.TooBig
        val allowed = uris.filter { ShareRules.allowedUri(it.scheme, it.authority, ctx.packageName) }
        if (text.isEmpty() && allowed.isEmpty()) return if (uris.isEmpty()) Result.Empty else Result.Unreadable

        val dir = clear(ctx)
        val files = mutableListOf<SharedFile>()
        var total = 0L
        try {
            for ((i, uri) in allowed.withIndex()) {
                val name = ShareRules.fileName(displayName(ctx, uri), "shared-${i + 1}")
                val type = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                val out = File(dir, i.toString())
                val input = ctx.contentResolver.openInputStream(uri) ?: return Result.Unreadable
                input.use { inp ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > ShareRules.MAX_BYTES) {
                                clear(ctx)
                                return Result.TooBig
                            }
                            o.write(buf, 0, n)
                        }
                    }
                }
                files.add(SharedFile(out, name, type, out.length()))
            }
        } catch (e: IOException) {
            clear(ctx)
            return Result.Unreadable
        } catch (e: SecurityException) {
            clear(ctx)
            return Result.Unreadable
        }
        return Result.Ready(SharedItem(text, files))
    }

    /** One piece of a shared file, as base64, starting at [offset]. Empty at the end. */
    fun chunk(file: SharedFile, offset: Long): String {
        if (offset < 0 || offset >= file.size) return ""
        RandomAccessFile(file.file, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(minOf(CHUNK.toLong(), file.size - offset).toInt())
            raf.readFully(buf)
            return Base64.encodeToString(buf, Base64.NO_WRAP)
        }
    }

    /** Deletes the copies of earlier shares. Returns the empty folder. */
    fun clear(ctx: Context): File {
        val dir = File(ctx.cacheDir, "shared")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private fun streams(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out.addAll(it) }
        } else {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out.add(it) }
        }
        return out.toList()
    }

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (e: RuntimeException) {
        null
    } ?: uri.lastPathSegment
}
