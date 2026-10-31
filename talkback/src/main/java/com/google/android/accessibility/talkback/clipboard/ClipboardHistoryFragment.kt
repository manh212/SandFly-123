/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.clipboard

import android.content.Context
import android.content.Intent
import android.view.ViewTreeObserver
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceViewHolder
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.NotificationCodeFragment
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity
import com.google.android.accessibility.talkback.shizuku.ShizukuClipboard
import com.google.android.accessibility.talkback.utils.ClipboardUtils
import com.google.android.accessibility.utils.preference.PreferencesActivity.FRAGMENT_NAME

/** The texts copied, newest first: each can be copied again or removed. */
class ClipboardHistoryFragment : NotificationCodeFragment(R.string.title_clipboard_history) {

  private val selected = LinkedHashSet<String>()
  private var multiSelection = false
  private var deleteSelected: Preference? = null
  private val onChange = Runnable {
    if (isResumed) {
      selected.retainAll(ClipboardHistory.getEntries().toSet())
      rebuild()
    }
  }

  private fun beginSelection(text: String) {
    selected.clear()
    selected.add(text)
    multiSelection = true
    rebuild()
  }

  private fun exitSelection() {
    multiSelection = false
    selected.clear()
    deleteSelected = null
    rebuild()
  }

  private fun deleteSelection() {
    if (selected.isEmpty()) return
    val count = selected.size
    dialog()
      .setMessage(getString(R.string.clipboard_history_confirm_delete_selected, count))
      .setPositiveButton(R.string.clipboard_history_delete_selected) { _, _ ->
        ClipboardHistory.removeAll(selected.toList())
        exitSelection()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun updateDeleteButton() {
    deleteSelected?.apply {
      title = getString(R.string.clipboard_history_delete_selected_count, selected.size)
      isEnabled = selected.isNotEmpty()
    }
  }

  // The clipboard can be read once the screen has focus, which it doesn't have yet on resume.
  // While Shizuku adds each copy, the history has the clipboard's text already.
  private val onFocus =
    ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
      if (hasFocus && !ShizukuClipboard.isWatching()) {
        context?.let { ClipboardHistoryCapture.captureCurrent(it) }
      }
    }

  override fun onResume() {
    super.onResume()
    val context = requireContext()
    ClipboardHistory.init(context.filesDir)
    ClipboardHistory.restoreLegacyHistory(context)
    ClipboardHistory.addListener(onChange)
    view?.viewTreeObserver?.addOnWindowFocusChangeListener(onFocus)
    if (view?.hasWindowFocus() == true && !ShizukuClipboard.isWatching()) {
      ClipboardHistoryCapture.captureCurrent(requireContext())
    }
  }

  override fun onPause() {
    ClipboardHistory.removeListener(onChange)
    view?.viewTreeObserver?.removeOnWindowFocusChangeListener(onFocus)
    super.onPause()
  }

  override fun build(screen: PreferenceGroup) {
    val context = requireContext()
    val entries = ClipboardHistory.getEntries()
    if (entries.isEmpty()) {
      screen.addPreference(
        Preference(context).apply {
          setTitle(R.string.clipboard_history_empty)
          isPersistent = false
          isSelectable = false
          isIconSpaceReserved = false
        }
      )
      return
    }
    if (multiSelection) {
      deleteSelected = screen.action(
        getString(R.string.clipboard_history_delete_selected_count, selected.size)
      ) { deleteSelection() }
      updateDeleteButton()
      screen.action(getString(R.string.clipboard_history_cancel_selection)) { exitSelection() }
    } else {
      deleteSelected = null
    }

    entries.forEach { text ->
      val title = if (text.length > TITLE_LENGTH) text.take(TITLE_LENGTH) + "…" else text
      if (multiSelection) {
        screen.addPreference(
          CheckBoxPreference(context).apply {
            this.title = title
            isPersistent = false
            isIconSpaceReserved = false
            isChecked = selected.contains(text)
            setOnPreferenceChangeListener { _, value ->
              if (value as Boolean) selected.add(text) else selected.remove(text)
              updateDeleteButton()
              true
            }
          }
        )
      } else {
        screen.addPreference(
          object : Preference(context) {
            override fun onBindViewHolder(holder: PreferenceViewHolder) {
              super.onBindViewHolder(holder)
              holder.itemView.setOnLongClickListener {
                choose(title, listOf(getString(R.string.clipboard_history_select_multiple))) {
                  beginSelection(text)
                }
                true
              }
            }
          }.apply {
            this.title = title
            isPersistent = false
            isIconSpaceReserved = false
            layoutResource = R.layout.listitem_2texts
            setOnPreferenceClickListener {
              choose(
                title,
                listOf(
                  getString(R.string.clipboard_history_copy),
                  getString(R.string.notification_remove),
                  getString(R.string.clipboard_history_select_multiple),
                )
              ) { choice ->
                when (choice) {
                  0 -> ClipboardUtils.copyToClipboard(context, text)
                  1 -> ClipboardHistory.remove(text)
                  2 -> beginSelection(text)
                }
              }
              true
            }
          }
        )
      }
    }
    if (!multiSelection) {
      screen.action(getString(R.string.clipboard_history_clear)) { ClipboardHistory.clear() }
    }
  }

  companion object {
    private const val TITLE_LENGTH = 500

    /** Opens the clipboard history from the service. */
    @JvmStatic
    fun open(context: Context): Boolean {
      context.startActivity(
        Intent(context, TalkBackPreferencesActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
          .putExtra(FRAGMENT_NAME, ClipboardHistoryFragment::class.java.name)
      )
      return true
    }
  }
}
