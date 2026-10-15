package io.github.rayvader.kirocrew

import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import io.github.rayvader.kirocrew.core.DataUrl
import io.github.rayvader.kirocrew.core.FileNames
import java.io.File
import java.io.IOException

class DownloadHelper(private val activity: Activity) {

    /** Normal http(s) download: the system downloader, carrying the login cookie. */
    fun enqueue(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val name = FileNames.sanitize(URLUtil.guessFileName(url, contentDisposition, mimeType))
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            } else {
                request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, name)
            }
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            toast(activity.getString(R.string.downloading, name))
        } catch (e: Exception) {
            toast(activity.getString(R.string.download_failed, e.javaClass.simpleName))
        }
    }

    /** Saves a data: URL. Safe to call from the JavaScript bridge thread. */
    fun saveDataUrl(dataUrl: String, fileName: String) {
        val parsed = DataUrl.parse(dataUrl)
        if (parsed == null) {
            toast(activity.getString(R.string.download_failed, "bad data"))
            return
        }
        val name = FileNames.sanitize(fileName)
        try {
            save(parsed.bytes, name, parsed.mimeType)
            toast(activity.getString(R.string.saved_to_downloads, name))
        } catch (e: Exception) {
            toast(activity.getString(R.string.download_failed, e.javaClass.simpleName))
        }
    }

    private fun save(bytes: ByteArray, name: String, mime: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = activity.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("insert failed")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("open failed")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException("no storage")
            File(dir, name).writeBytes(bytes)
        }
    }

    private fun toast(text: String) {
        activity.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
    }
}
