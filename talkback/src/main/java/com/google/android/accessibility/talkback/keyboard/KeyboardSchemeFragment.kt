package com.google.android.accessibility.talkback.keyboard

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.commands.CommandBindings
import com.google.android.accessibility.talkback.commands.Commands
import com.google.android.accessibility.talkback.preference.PreferencesActivityUtils
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.ServiceKeyEventListener

class KeyboardSchemeFragment : TalkbackBaseFragment() {
  private var capture: AlertDialog? = null
  private var captureManager: KeyComboManager? = null
  private val schemeValue: String?
    get() = arguments?.getString(ARG_SCHEME)

  override fun getTitle(): CharSequence =
    context?.let { CustomKeymaps.of(it, schemeValue)?.name } ?: getText(R.string.title_pref_manage_keyboard_shortcuts)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    preferenceManager.setStorageDeviceProtected()
    preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
  }

  override fun onResume() {
    super.onResume()
    fill()
  }

  override fun onPause() {
    capture?.dismiss()
    super.onPause()
  }

  private fun manager(): KeyComboManager? =
    TalkBackService.getInstance()?.keyComboManager?.takeIf { it.keymap == schemeValue }

  private fun fill() {
    val context = requireContext()
    val screen = preferenceScreen
    screen.removeAll()
    val manager = manager() ?: CommandBindings.keyComboManager(context)
    if (manager.keymap != schemeValue) return
    val model = manager.keyComboModel
    model.keyComboMap.entries
      .filter { it.value.keyComboCode != KeyComboModel.KEY_COMBO_CODE_UNASSIGNED.toLong() }
      .sortedBy { manager.getKeyComboStringRepresentation(it.value) }
      .forEach { (key, combo) ->
        screen.addPreference(Preference(context).apply {
          this.key = "scheme_binding_$key"
          isPersistent = false
          isIconSpaceReserved = false
          title = bindingName(manager, key, combo)
          setOnPreferenceClickListener {
            AlertDialog.Builder(context)
              .setTitle(title)
              .setItems(arrayOf(getString(R.string.scheme_change_action), getString(R.string.scheme_remove_shortcut))) { _, which ->
                if (which == 0) chooseAction { save(combo, it, key) }
                else {
                  model.clearKeyCombo(key)
                  fill()
                }
              }
              .setNegativeButton(android.R.string.cancel, null)
              .show()
            true
          }
        })
      }
    screen.addPreference(Preference(context).apply {
      key = "scheme_add_shortcut"
      title = getString(R.string.scheme_add_shortcut)
      isPersistent = false
      isIconSpaceReserved = false
      isEnabled = TalkBackService.isServiceActive() && this@KeyboardSchemeFragment.manager() != null
      setOnPreferenceClickListener {
        record()
        true
      }
    })
  }

  private fun bindingName(manager: KeyComboManager, key: String, combo: KeyCombo): String {
    val keys = manager.getKeyComboStringRepresentation(combo)
    val command = Commands.labelOfKeyboardKey(requireContext(), key) ?: key
    return getString(R.string.scheme_binding, keys, command)
  }

  private fun record() {
    val manager = manager() ?: return
    var capsHeld = false
    var completed = false
    val recognizer = SchemeKeyRecognizer(object : SchemeKeyRecognizer.Callbacks {
      override fun isEditingText(): Boolean = false
      override fun canHandle(combo: KeyCombo): Boolean = manager.keyComboModel.isEligibleKeyCombo(combo)
      override fun perform(combo: KeyCombo) {
        if (completed) return
        completed = true
        capture?.dismiss()
        view?.post { if (isResumed) chooseAction { save(combo, it) } }
      }
    })
    val listener = object : ServiceKeyEventListener {
      override fun processWhenServiceSuspended(): Boolean = false
      override fun onKeyEvent(event: KeyEvent, eventId: EventId?): Boolean {
        if (!KeyboardCapsBridge.isPhysical(event)) return false
        if (event.keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
          capsHeld = event.action != KeyEvent.ACTION_UP
        }
        recognizer.onKeyEvent(event, capsHeld)
        return true
      }
    }
    val dialog = AlertDialog.Builder(requireContext())
      .setTitle(R.string.scheme_capture_title)
      .setMessage(R.string.scheme_capture_instruction)
      .setNegativeButton(android.R.string.cancel, null)
      .create()
    capture = dialog
    captureManager = manager
    dialog.setOnDismissListener {
      captureManager?.setKeyEventDelegate(null)
      captureManager = null
      capture = null
      recognizer.reset()
    }
    dialog.show()
    manager.setKeyEventDelegate(listener)
  }

  private fun chooseAction(then: (String) -> Unit) {
    val context = requireContext()
    val categories = Commands.categories(context)
    AlertDialog.Builder(context)
      .setTitle(R.string.scheme_choose_action)
      .setItems(categories.map { getString(it.titleRes) }.toTypedArray()) { _, group ->
        val category = categories[group]
        AlertDialog.Builder(context)
          .setTitle(category.titleRes)
          .setItems(category.commands.map { it.label }.toTypedArray()) { _, action -> then(category.commands[action].value) }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun save(combo: KeyCombo, command: String, previousKey: String? = null) {
    val manager = manager() ?: return
    val model = manager.keyComboModel
    val key = Commands.shortcutKey(command)
    val collision = model.getKeyForKeyCombo(combo)
    val commit = {
      if (previousKey != null && previousKey != key) model.clearKeyCombo(previousKey)
      if (collision != null && collision != key) model.clearKeyCombo(collision)
      model.saveKeyCombo(key, combo)
      fill()
    }
    if (collision != null && collision != key && collision != previousKey) {
      AlertDialog.Builder(requireContext())
        .setMessage(getString(R.string.scheme_replace_shortcut, bindingName(manager, collision, combo)))
        .setPositiveButton(R.string.command_replace) { _, _ -> commit() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    } else {
      commit()
    }
  }

  companion object {
    const val ARG_SCHEME = "scheme"
  }
}
