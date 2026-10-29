package com.google.android.accessibility.talkback.update

import android.Manifest
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.io.File
import java.io.IOException

internal object UpdateDownloads {
  private const val SAVE_DOWNLOADS = "pref_update_save_downloads"

  fun isEnabled(context: Context): Boolean =
    SharedPreferencesUtils.getSharedPreferences(context).getBoolean(SAVE_DOWNLOADS, false)

  fun setEnabled(context: Context, enabled: Boolean) {
    SharedPreferencesUtils.getSharedPreferences(context).edit().putBoolean(SAVE_DOWNLOADS, enabled).apply()
  }

  fun saveIfRequested(context: Context, apk: File, requested: Boolean): Uri? =
    if (requested) save(context, apk) else null

  private const val MIME_TYPE = "application/vnd.android.package-archive"

  fun needsStoragePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

  fun save(context: Context, apk: File): Uri {
    val name = "meridian-${System.currentTimeMillis()}.apk"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val resolver = context.contentResolver
      val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
      }
      val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Cannot create the update in Downloads")
      try {
        val output = resolver.openOutputStream(uri) ?: throw IOException("Cannot open the update in Downloads")
        output.use { target -> apk.inputStream().use { it.copyTo(target) } }
        val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        if (resolver.update(uri, published, null, null) != 1) throw IOException("Cannot publish the update in Downloads")
        return uri
      } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
      }
    }
    if (needsStoragePermission(context)) throw IOException("Storage permission is required")
    val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create Downloads")
    var destination = File(directory, name)
    var suffix = 1
    while (!destination.createNewFile()) destination = File(directory, "${name.removeSuffix(".apk")}-${suffix++}.apk")
    try {
      apk.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
      context.getSystemService(DownloadManager::class.java)?.addCompletedDownload(
        destination.name, destination.name, false, MIME_TYPE, destination.absolutePath, destination.length(), true)
      return Uri.fromFile(destination)
    } catch (e: Exception) {
      destination.delete()
      throw e
    }
  }
}
