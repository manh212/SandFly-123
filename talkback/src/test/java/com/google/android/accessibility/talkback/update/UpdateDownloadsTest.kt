package com.google.android.accessibility.talkback.update

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateDownloadsTest {
  private val context get() = RuntimeEnvironment.getApplication()

  @Before fun clear() { SharedPreferencesUtils.getSharedPreferences(context).edit().clear().commit() }

  @Test fun savingIsOffByDefaultAndDoesNotTouchSharedStorage() {
    assertFalse(UpdateDownloads.isEnabled(context))
    assertNull(UpdateDownloads.saveIfRequested(context, File("does-not-exist.apk"), false))
    UpdateDownloads.setEnabled(context, true)
    assertTrue(UpdateDownloads.isEnabled(context))
    UpdateDownloads.setEnabled(context, false)
    assertFalse(UpdateDownloads.isEnabled(context))
  }

  @Test fun scopedStoragePublishesAnApkInDownloadsAndKeepsItAfterCacheCleanup() {
    val provider = DownloadsProvider()
    provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
    ShadowContentResolver.registerProviderInternal("media", provider)
    val apk = File(context.cacheDir, "download-test.apk").apply { writeText("apk content") }
    assertNotNull(UpdateDownloads.saveIfRequested(context, apk, true))
    assertEquals(Environment.DIRECTORY_DOWNLOADS, provider.inserted!!.getAsString(MediaStore.Downloads.RELATIVE_PATH))
    assertEquals("application/vnd.android.package-archive", provider.inserted!!.getAsString(MediaStore.Downloads.MIME_TYPE))
    assertEquals(1, provider.inserted!!.getAsInteger(MediaStore.Downloads.IS_PENDING).toInt())
    assertEquals(0, provider.updated!!.getAsInteger(MediaStore.Downloads.IS_PENDING).toInt())
    apk.delete()
    assertEquals("apk content", provider.file.readText())
  }

  @Test fun failedStorageWriteRemovesTheIncompleteDownload() {
    val provider = DownloadsProvider().apply { failOpen = true }
    provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
    ShadowContentResolver.registerProviderInternal("media", provider)
    val apk = File(context.cacheDir, "failed-test.apk").apply { writeText("apk content") }
    try {
      UpdateDownloads.saveIfRequested(context, apk, true)
      fail("An unwritable destination must fail")
    } catch (expected: IOException) {
      assertTrue(provider.deleted)
      assertNull(provider.updated)
      assertTrue(apk.exists())
    }
  }

  @Test @Config(sdk = [28]) fun legacyStorageRequiresPermissionOnlyWhenSavingIsRequested() {
    shadowOf(context).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    assertTrue(UpdateDownloads.needsStoragePermission(context))
    assertNull(UpdateDownloads.saveIfRequested(context, File("unused.apk"), false))
    shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    assertFalse(UpdateDownloads.needsStoragePermission(context))
    val apk = File(context.cacheDir, "legacy-test.apk").apply { writeText("legacy apk") }
    val uri = UpdateDownloads.saveIfRequested(context, apk, true)!!
    val saved = File(uri.path!!)
    assertEquals(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), saved.parentFile)
    apk.delete()
    assertEquals("legacy apk", saved.readText())
  }

  class DownloadsProvider : ContentProvider() {
    lateinit var file: File
    var inserted: ContentValues? = null
    var updated: ContentValues? = null
    var deleted = false
    var failOpen = false
    override fun onCreate(): Boolean {
      file = File(context!!.filesDir, "public-download.apk")
      return true
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri {
      inserted = ContentValues(values)
      return Uri.parse("content://media/external/downloads/1")
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
      updated = ContentValues(values)
      return 1
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
      if (failOpen) throw java.io.FileNotFoundException("No space")
      return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deleted = true; file.delete(); return 1 }
    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  }
}
