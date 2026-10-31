/*
 * Copyright (C) 2026 The SandFly Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.clipboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.CheckedTextView;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.Performance;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for the search filtering in {@link ClipboardHistoryDialog}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ClipboardHistoryDialogTest {

  @Test
  public void filter_emptyQueryReturnsAllInOrder() {
    List<String> entries = Arrays.asList("two", "one");

    assertEquals(entries, ClipboardHistoryDialog.filterEntries(entries, ""));
    assertEquals(entries, ClipboardHistoryDialog.filterEntries(entries, null));
  }

  @Test
  public void filter_ignoresDiacriticsAndCase() {
    List<String> entries = Arrays.asList("mật khẩu", "hello");

    assertEquals(
        Arrays.asList("mật khẩu"),
        ClipboardHistoryDialog.filterEntries(entries, "mat khau"));
    assertEquals(
        Arrays.asList("mật khẩu"),
        ClipboardHistoryDialog.filterEntries(entries, "MẬT"));
  }

  @Test
  public void filter_noMatchReturnsEmpty() {
    List<String> entries = Arrays.asList("mật khẩu", "hello");

    assertEquals(0, ClipboardHistoryDialog.filterEntries(entries, "xyz").size());
  }

  @Test
  public void emptyClipboardStillHasCancelNextToClearAll() {
    android.content.Context context = RuntimeEnvironment.getApplication();
    ((android.content.ClipboardManager) context.getSystemService(android.content.Context.CLIPBOARD_SERVICE))
        .setPrimaryClip(android.content.ClipData.newPlainText("", ""));
    ClipboardHistory.clear();
    new ClipboardHistoryDialog(context, null).show(Performance.EVENT_ID_UNTRACKED, null);
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    Button cancel = findButton(dialog.getWindow().getDecorView(),
        context.getString(R.string.text_collection_cancel));
    Button clear = findButton(dialog.getWindow().getDecorView(),
        context.getString(R.string.clipboard_history_clear_all));
    assertNotNull(cancel);
    assertNotNull(clear);
    assertEquals(clear.getParent(), cancel.getParent());
    cancel.performClick();
    assertFalse(dialog.isShowing());
  }

  @Test
  public void emptyFavoritesCanOpenAddAndCancelBothDialogs() {
    android.content.Context context = RuntimeEnvironment.getApplication();
    new ClipboardHistoryDialog(context, null, true).show(Performance.EVENT_ID_UNTRACKED, null);
    AlertDialog parent = ShadowAlertDialog.getLatestAlertDialog();
    Button add = findButton(parent.getWindow().getDecorView(),
        context.getString(R.string.favorites_add));
    assertNotNull(add);
    add.performClick();
    AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
    editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    shadowOf(Looper.getMainLooper()).idle();
    assertFalse(editor.isShowing());
    Button cancel = findButton(parent.getWindow().getDecorView(),
        context.getString(R.string.text_collection_cancel));
    assertNotNull(cancel);
    cancel.performClick();
    assertFalse(parent.isShowing());
  }

  @Test
  public void historySelectionPastesAfterDialogClosesEvenWithoutInitialTarget() {
    android.content.Context context = RuntimeEnvironment.getApplication();
    android.content.ClipboardManager clipboard =
        (android.content.ClipboardManager)
            context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""));
    ClipboardHistory.clear();
    ClipboardHistory.record("paste this");
    boolean[] pasted = {false};
    new ClipboardHistoryDialog(context, null)
        .show(
            Performance.EVENT_ID_UNTRACKED,
            null,
            (eventId, originalTarget) -> {
              assertEquals(null, originalTarget);
              pasted[0] = true;
              return true;
            });
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    ListView list = findList(dialog.getWindow().getDecorView());
    assertNotNull(list);
    list.getOnItemClickListener().onItemClick(list, null, 0, 0);
    assertFalse(dialog.isShowing());
    assertFalse(pasted[0]);
    shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS);
    assertTrue(pasted[0]);
    assertEquals("paste this", clipboard.getPrimaryClip().getItemAt(0).coerceToText(context));
    assertEquals(Arrays.asList("paste this"), ClipboardHistory.getEntries());
  }

  @Test
  public void longPressOffersMultiSelectWithoutDeletingText() {
    android.content.Context context = RuntimeEnvironment.getApplication();
    ((android.content.ClipboardManager) context.getSystemService(android.content.Context.CLIPBOARD_SERVICE))
        .setPrimaryClip(android.content.ClipData.newPlainText("", ""));
    ClipboardHistory.clear();
    ClipboardHistory.record("keep this");
    new ClipboardHistoryDialog(context, null).show(Performance.EVENT_ID_UNTRACKED, null);
    AlertDialog parent = ShadowAlertDialog.getLatestAlertDialog();
    ListView list = findList(parent.getWindow().getDecorView());
    assertNotNull(list);
    list.getOnItemLongClickListener().onItemLongClick(list, null, 0, 0);
    AlertDialog actions = ShadowAlertDialog.getLatestAlertDialog();
    assertEquals(3, actions.getListView().getAdapter().getCount());
    assertEquals(Arrays.asList("keep this"), ClipboardHistory.getEntries());
    actions.dismiss();
    parent.dismiss();
  }

  @Test
  public void multipleSelectionRemovesOnlyChosenClipboardEntries() {
    android.content.Context context = RuntimeEnvironment.getApplication();
    ((android.content.ClipboardManager) context.getSystemService(android.content.Context.CLIPBOARD_SERVICE))
        .setPrimaryClip(android.content.ClipData.newPlainText("", ""));
    ClipboardHistory.clear();
    for (int i = 1; i <= 5; i++) {
      ClipboardHistory.record("entry " + i);
    }
    new ClipboardHistoryDialog(context, null).show(Performance.EVENT_ID_UNTRACKED, null);
    AlertDialog parent = ShadowAlertDialog.getLatestAlertDialog();
    ListView list = findList(parent.getWindow().getDecorView());
    assertNotNull(list);
    list.getOnItemLongClickListener().onItemLongClick(list, null, 0, 0);
    AlertDialog actions = ShadowAlertDialog.getLatestAlertDialog();
    actions.getListView().performItemClick(null, 2, 2);
    assertTrue(parent.isShowing());
    assertTrue(list.getAdapter().getView(0, null, list) instanceof CheckedTextView);
    list.getOnItemClickListener().onItemClick(list, null, 2, 2);
    list.getOnItemClickListener().onItemClick(list, null, 4, 4);
    Button delete = findButton(parent.getWindow().getDecorView(),
        context.getString(R.string.clipboard_history_delete_selected_count, 3));
    assertNotNull(delete);
    delete.performClick();
    AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
    confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    assertEquals(Arrays.asList("entry 4", "entry 2"), ClipboardHistory.getEntries());
    parent.dismiss();
  }

  private static Button findButton(View view, String text) {
    if (view instanceof Button button && text.contentEquals(button.getText())) {
      return button;
    }
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        Button found = findButton(group.getChildAt(i), text);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static ListView findList(View view) {
    if (view instanceof ListView list) {
      return list;
    }
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        ListView found = findList(group.getChildAt(i));
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  @Test
  public void normalize_stripsDiacriticsAndLowercases() {
    assertEquals("mat khau", ClipboardHistoryDialog.normalizeForSearch("Mật Khẩu"));
    assertEquals("", ClipboardHistoryDialog.normalizeForSearch(null));
  }
}
