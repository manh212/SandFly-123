package com.google.android.accessibility.talkback.update

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import com.google.android.accessibility.talkback.R

class UpdateStoragePermissionActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != 1) return
    val url = intent.getStringExtra(UpdateReceiver.EXTRA_DOWNLOAD_URL)
    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && url != null) {
      Updater.install(applicationContext, url)
    } else {
      Toast.makeText(this, R.string.update_storage_permission, Toast.LENGTH_LONG).show()
    }
    finish()
  }
}
