package com.google.android.accessibility.talkback.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.TimerPermissionChecks.Status
import com.google.android.accessibility.talkback.permission.PermissionUtils
import com.google.android.accessibility.talkback.shizuku.ShizukuGrants
import com.google.android.accessibility.talkback.speakerphone.SpeakerphoneSettings
import com.google.android.accessibility.talkback.utils.ClipboardUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object CallPermissions {
  fun contactsStatus(context: Context): Status = status(context, Manifest.permission.READ_CONTACTS)
  fun logStatus(context: Context): Status = status(context, Manifest.permission.READ_CALL_LOG)
  fun managementStatus(context: Context): Status =
    if (SpeakerphoneSettings.canControlCalls(context)) Status.GRANTED else Status.MISSING

  private fun status(context: Context, permission: String): Status =
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
      Status.GRANTED
    } else Status.MISSING

  fun legacyStatus(context: Context): Status =
    if (CallLimitController.canUseLegacy(context)) Status.GRANTED else Status.MISSING

  fun requestLegacy(context: Context) {
    val missing = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
      .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isNotEmpty()) PermissionUtils.requestPermissions(context, *missing.toTypedArray())
  }

  fun requestContacts(context: Context) {
    PermissionUtils.requestPermissions(context, Manifest.permission.READ_CONTACTS)
  }

  fun requestLog(context: Context) {
    PermissionUtils.requestPermissions(context, Manifest.permission.READ_CALL_LOG)
  }

  fun requestManagement(context: Context, onDone: () -> Unit) {
    if (ShizukuGrants.isReady()) {
      ShizukuGrants.request(context, ShizukuGrants.Grant.MANAGE_ONGOING_CALLS) { onDone() }
      return
    }
    val command = SpeakerphoneSettings.grantCommand(context)
    MaterialAlertDialogBuilder(context)
      .setTitle(R.string.call_permission_manage_title)
      .setMessage(context.getString(R.string.call_permission_manage_command, command))
      .setPositiveButton(R.string.action_copy_command) { _, _ ->
        ClipboardUtils.copyToClipboard(context, command)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .setOnDismissListener { onDone() }
      .show()
  }
}
