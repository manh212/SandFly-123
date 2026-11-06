package com.google.android.accessibility.talkback.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.google.android.accessibility.talkback.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ReadingGroupShortcutTest {
  @Test
  public void groupShortcutsCanBeAssignedButHaveNoDefaultKeys() {
    Context context = RuntimeEnvironment.getApplication();
    NewKeyComboModel model = new NewKeyComboModel(context);
    String previous = context.getString(R.string.keycombo_shortcut_reading_group_previous);
    String next = context.getString(R.string.keycombo_shortcut_reading_group_next);
    assertTrue(model.getKeyComboMap().containsKey(previous));
    assertTrue(model.getKeyComboMap().containsKey(next));
    assertEquals(KeyComboModel.KEY_COMBO_CODE_UNASSIGNED, model.getDefaultKeyCombo(previous).getKeyComboCode());
    assertEquals(KeyComboModel.KEY_COMBO_CODE_UNASSIGNED, model.getDefaultKeyCombo(next).getKeyComboCode());
  }
}
