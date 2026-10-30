package com.google.android.accessibility.talkback.gesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CustomMenusTest {
  private Context context;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().remove(CustomMenus.PREF_KEY).commit();
  }

  @Test public void multipleMenusCanBeAssignedIndependentlyToGestures() {
    String first = CustomMenus.add(context, "Menu một");
    String second = CustomMenus.add(context, "Menu hai");
    assertNotNull(first);
    assertNotNull(second);
    assertNotEquals(first, second);
    assertEquals(2, CustomMenus.menus(context).size());
    assertTrue(CustomMenus.isMenuAction(CustomMenus.actionFor(first)));
    assertEquals("Menu một", CustomMenus.label(context, CustomMenus.actionFor(first)));
    assertEquals("Menu hai", CustomMenus.label(context, CustomMenus.actionFor(second)));
  }

  @Test public void itemsCanBeAddedMovedRemovedAndKeptAfterRename() {
    String id = CustomMenus.add(context, "Đầu tiên");
    assertNotNull(id);
    assertTrue(CustomMenus.addItem(context, id, "Đọc", "read"));
    assertTrue(CustomMenus.addItem(context, id, "Sao chép", "copy"));
    assertFalse(CustomMenus.addItem(context, id, "Menu", CustomMenus.actionFor(id)));
    CustomMenus.moveItem(context, id, 1, -1);
    assertEquals("copy", CustomMenus.find(context, id).items.get(0).value);
    assertTrue(CustomMenus.rename(context, id, "Đã đổi tên"));
    assertEquals(2, CustomMenus.find(context, id).items.size());
    CustomMenus.removeItem(context, id, 1);
    assertEquals(1, CustomMenus.find(context, id).items.size());
    assertEquals("Đã đổi tên", CustomMenus.label(context, CustomMenus.actionFor(id)));
  }

  @Test public void deletingOneMenuDoesNotDeleteAnother() {
    String first = CustomMenus.add(context, "Một");
    String second = CustomMenus.add(context, "Hai");
    CustomMenus.delete(context, first);
    assertNull(CustomMenus.find(context, first));
    assertNotNull(CustomMenus.find(context, second));
    assertEquals(1, CustomMenus.menus(context).size());
  }

  @Test public void blankAndDuplicateNamesAreRejected() {
    String first = CustomMenus.add(context, "Một");
    assertNotNull(first);
    assertNull(CustomMenus.add(context, " "));
    assertNull(CustomMenus.add(context, "một"));
    assertFalse(CustomMenus.rename(context, first, ""));
  }
}
