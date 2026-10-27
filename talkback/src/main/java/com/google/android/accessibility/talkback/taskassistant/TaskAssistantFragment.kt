package com.google.android.accessibility.talkback.taskassistant

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.preference.base.TalkBackPreferenceFragment

class TaskAssistantFragment : TalkBackPreferenceFragment(R.xml.task_assistant_preferences) {
  override fun getTitle(): CharSequence = getText(R.string.task_title)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    findPreference<Preference>("task_assistant_ai")?.setOnPreferenceClickListener {
      val context = requireContext()
      val choices = TaskAiCatalog.choices(context)
      if (choices.isEmpty()) {
        AlertDialog.Builder(context).setTitle(R.string.task_ai).setMessage(R.string.task_ai_missing)
          .setPositiveButton(R.string.task_close, null).show()
        return@setOnPreferenceClickListener true
      }
      AlertDialog.Builder(context).setTitle(R.string.task_ai)
        .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), choices.indexOf(TaskAiCatalog.selected(context))) { dialog, index ->
          TaskAiCatalog.select(context, choices[index])
          refresh()
          dialog.dismiss()
        }.setNegativeButton(R.string.task_close, null).show()
      true
    }
    findPreference<Preference>("task_assistant_open")?.setOnPreferenceClickListener {
      TalkBackService.getInstance()?.let { TaskAssistant.show(it) }
      true
    }
  }

  override fun onResume() { super.onResume(); refresh() }

  private fun refresh() {
    context?.let {
      findPreference<Preference>("task_assistant_ai")?.summary = TaskAiCatalog.selected(it)?.label ?: it.getString(R.string.task_ai_missing)
    }
  }
}
