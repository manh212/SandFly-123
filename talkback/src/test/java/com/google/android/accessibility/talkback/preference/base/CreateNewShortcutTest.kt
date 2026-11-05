package com.google.android.accessibility.talkback.preference.base

import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateNewShortcutTest {
  @Test fun userItemWithSameNameAsCreateButtonIsNeverTreatedAsCreateAction() {
    val context = RuntimeEnvironment.getApplication()
    val screen = PreferenceManager(context).createPreferenceScreen(context)
    val userItem = Preference(context).apply {
      title = "Tạo menu"
      isPersistent = false
      setOnPreferenceClickListener { true }
    }
    val create = CreateNewShortcut.mark(Preference(context).apply {
      title = "Tạo menu"
      isPersistent = false
      setOnPreferenceClickListener { true }
    })
    screen.addPreference(userItem)
    screen.addPreference(create)
    assertEquals(listOf(create), CreateNewShortcut.actions(screen))
  }

  @Test fun onlyVisibleEnabledActionsInNestedGroupsAreOffered() {
    val context = RuntimeEnvironment.getApplication()
    val screen = PreferenceManager(context).createPreferenceScreen(context)
    val group = PreferenceCategory(context)
    screen.addPreference(group)
    val actions = (0..2).map { index ->
      CreateNewShortcut.mark(Preference(context).apply {
        title = "Thêm $index"
        isPersistent = false
        setOnPreferenceClickListener { true }
      }).also { group.addPreference(it) }
    }
    actions[1].isEnabled = false
    actions[2].isVisible = false
    assertEquals(listOf(actions[0]), CreateNewShortcut.actions(screen))
  }
}
