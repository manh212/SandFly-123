package com.google.android.accessibility.talkback.taskassistant

import android.os.Bundle
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.preference.base.TalkBackPreferenceFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

class TaskAssistantFragment : TalkBackPreferenceFragment(R.xml.task_assistant_preferences) {
  override fun getTitle(): CharSequence = getText(R.string.task_title)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    findPreference<Preference>("task_assistant_ai")?.setOnPreferenceClickListener {
      val context = requireContext()
      val picker = TaskAiPicker(context)
      AlertDialog.Builder(context).setTitle(R.string.task_ai)
        .setView(ScrollView(context).apply { addView(picker) })
        .setPositiveButton(R.string.task_close, null).create().apply {
          setOnDismissListener { refresh() }
          show()
        }
      true
    }
    findPreference<Preference>("task_assistant_open")?.setOnPreferenceClickListener {
      TalkBackService.getInstance()?.let { TaskAssistant.show(it) }
      true
    }
  }

  override fun onResume() { super.onResume(); refresh() }

  private fun refresh() {
    context?.let { context ->
      val prefs = SharedPreferencesUtils.getSharedPreferences(context)
      findPreference<Preference>("task_assistant_ai")?.summary = TaskAiCatalog.selected(context)?.label
        ?: if (TaskAiCatalog.isLocal(prefs)) {
          "${TaskAiCatalog.localModel(prefs).name} · ${context.getString(R.string.task_not_downloaded)}"
        } else context.getString(R.string.task_ai_missing)
    }
  }
}
