/*
 * Copyright (C) 2026 The SandFly Authors
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

package com.google.android.accessibility.talkback.clipboard;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.utils.ClipboardUtils;
import com.google.android.accessibility.utils.PerformActionUtils;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.output.FeedbackItem;
import com.google.android.accessibility.utils.output.SpeechController;
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions;
import com.google.android.accessibility.utils.output.SpeechControllerImpl;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.Nullable;

public class ClipboardHistoryDialog {

  @FunctionalInterface
  public interface PasteAction {
    boolean paste(EventId eventId, @Nullable AccessibilityNodeInfoCompat originalTarget);
  }

  private final Context context;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final SpeechControllerImpl speaker;

  private AlertDialog dialog;
  private ArrayAdapter<String> adapter;
  private String query = "";
  private final boolean favorites;
  private Button clearAll;
  private TextView emptyText;
  private AlertDialog childDialog;

  public ClipboardHistoryDialog(Context context, SpeechControllerImpl speaker) {
    this(context, speaker, false);
  }

  public ClipboardHistoryDialog(
      Context context, SpeechControllerImpl speaker, boolean favorites) {
    this.context = context;
    this.speaker = speaker;
    this.favorites = favorites;
  }

  public void show(EventId eventId, @Nullable AccessibilityNodeInfoCompat pasteTarget) {
    show(eventId, pasteTarget, null);
  }

  public void show(
      EventId eventId,
      @Nullable AccessibilityNodeInfoCompat pasteTarget,
      @Nullable PasteAction pasteAction) {
    if (dialog != null && dialog.isShowing()) {
      dialog.dismiss();
    }
    query = "";
    if (!favorites) {
      ClipboardHistoryCapture.captureCurrent(context);
    }

    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 16, context.getResources().getDisplayMetrics());
    layout.setPadding(padding, padding, padding, padding);

    if (favorites) {
      Button add = new Button(context);
      add.setText(R.string.favorites_add);
      add.setOnClickListener(view -> showAddDialog(eventId));
      layout.addView(add, new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    EditText search = new EditText(context);
    search.setHint(favorites ? R.string.favorites_search : R.string.clipboard_history_search_hint);
    search.setSingleLine(true);
    layout.addView(search, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    ListView list = new ListView(context);
    layout.addView(list, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, padding * 15, 1f));
    emptyText = new TextView(context);
    emptyText.setPadding(padding, padding, padding, padding);
    layout.addView(emptyText);
    list.setEmptyView(emptyText);

    LinearLayout buttons = new LinearLayout(context);
    buttons.setOrientation(LinearLayout.HORIZONTAL);
    layout.addView(buttons);
    clearAll = new Button(context);
    clearAll.setText(R.string.clipboard_history_clear_all);
    buttons.addView(clearAll, new LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    Button cancel = new Button(context);
    cancel.setText(R.string.text_collection_cancel);
    cancel.setOnClickListener(view -> dialog.dismiss());
    buttons.addView(cancel, new LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    adapter = new ArrayAdapter<String>(context, 0, new ArrayList<>()) {
      @Override
      public View getView(int position, View convertView, ViewGroup parent) {
        TextView row;
        if (convertView instanceof TextView) {
          row = (TextView) convertView;
        } else {
          row = new TextView(context);
          row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
          row.setPadding(padding, padding, padding, padding);
        }
        row.setText(getItem(position));
        return row;
      }
    };
    list.setAdapter(adapter);
    refreshList();

    search.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {}

      @Override
      public void afterTextChanged(Editable s) {
        query = s.toString();
        refreshList();
      }
    });

    list.setOnItemClickListener((parent, view, position, id) -> {
      String entry = adapter.getItem(position);
      if (entry == null) {
        return;
      }
      if (favorites && pasteTarget == null && pasteAction == null) {
        if (ClipboardUtils.copyToClipboard(context, entry)) {
          speak(context.getString(R.string.favorites_copied), eventId);
          dialog.dismiss();
        }
        return;
      }
      if (pasteAction == null && pasteTarget == null) {
        speak(context.getString(R.string.clipboard_history_no_target), eventId);
        return;
      }
      if (pasteAction == null && (!pasteTarget.refresh() || !pasteTarget.isEditable())) {
        speak(context.getString(R.string.clipboard_history_paste_failed), eventId);
        return;
      }
      if (!ClipboardUtils.copyToClipboardWithoutRecording(context, entry)) {
        speak(context.getString(R.string.clipboard_history_paste_failed), eventId);
        return;
      }
      if (pasteAction != null) {
        dialog.dismiss();
        handler.postDelayed(
            () -> {
              boolean pasted = pasteAction.paste(eventId, pasteTarget);
              speak(
                  context.getString(
                      pasted
                          ? R.string.clipboard_history_pasted
                          : R.string.clipboard_history_paste_failed),
                  eventId);
            },
            100);
        return;
      }
      boolean pasted =
          PerformActionUtils.performAction(
              pasteTarget, AccessibilityNodeInfoCompat.ACTION_PASTE, eventId);
      if (pasted) {
        speak(context.getString(R.string.clipboard_history_pasted), eventId);
        dialog.dismiss();
      } else {
        speak(context.getString(R.string.clipboard_history_paste_failed), eventId);
      }
    });

    list.setOnItemLongClickListener((parent, view, position, id) -> {
      String entry = adapter.getItem(position);
      if (entry == null) {
        return true;
      }
      CharSequence[] actions = favorites
          ? new CharSequence[] {context.getString(R.string.text_collection_delete)}
          : new CharSequence[] {
              context.getString(R.string.text_collection_delete),
              context.getString(R.string.favorites_add_from_clipboard)};
      childDialog = new AlertDialog.Builder(context)
          .setItems(actions, (clicked, which) -> {
            if (which == 0) {
              boolean removed = true;
              if (favorites) {
                removed = FavoriteTexts.remove(entry);
              } else {
                ClipboardHistory.remove(entry);
              }
              refreshList();
              speak(context.getString(removed
                  ? R.string.clipboard_history_deleted : R.string.favorites_save_failed), eventId);
            } else {
              boolean saved = FavoriteTexts.add(entry);
              speak(context.getString(saved
                  ? R.string.favorites_saved : R.string.favorites_save_failed), eventId);
            }
          })
          .setNegativeButton(R.string.text_collection_cancel, null)
          .create();
      showOverlay(childDialog);
      return true;
    });

    clearAll.setOnClickListener(v -> {
      AlertDialog confirm =
          new AlertDialog.Builder(context)
              .setMessage(favorites ? R.string.favorites_clear_confirm : R.string.clipboard_history_clear_confirm)
              .setPositiveButton(R.string.clipboard_history_clear, (clicked, which) -> {
                boolean cleared = true;
                if (favorites) {
                  cleared = FavoriteTexts.clear();
                } else {
                  ClipboardHistory.clear();
                }
                refreshList();
                speak(context.getString(cleared
                    ? R.string.clipboard_history_cleared : R.string.favorites_save_failed), eventId);
              })
              .setNegativeButton(android.R.string.cancel, null)
              .create();
      childDialog = confirm;
      showOverlay(confirm);
    });

    dialog =
        new AlertDialog.Builder(context)
            .setTitle(favorites ? R.string.title_favorites : R.string.title_clipboard_history)
            .setView(layout)
            .create();
    dialog.setOnDismissListener(dismissed -> {
      if (childDialog != null) {
        childDialog.dismiss();
        childDialog = null;
      }
    });
    dialog.getWindow().setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    showOverlay(dialog);
  }

  private static void showOverlay(AlertDialog overlay) {
    overlay.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
    overlay.show();
  }

  private void showAddDialog(EventId eventId) {
    EditText input = new EditText(context);
    input.setHint(R.string.favorites_text);
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    input.setMinLines(3);
    childDialog = new AlertDialog.Builder(context)
        .setTitle(R.string.favorites_add)
        .setView(input)
        .setPositiveButton(R.string.favorites_save, null)
        .setNegativeButton(R.string.text_collection_cancel, null)
        .create();
    AlertDialog editor = childDialog;
    input.requestFocus();
    editor.getWindow().setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    showOverlay(editor);
    editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
      if (input.getText().toString().trim().isEmpty()) {
        input.setError(context.getString(R.string.favorites_text_required));
        return;
      }
      if (FavoriteTexts.add(input.getText())) {
        refreshList();
        editor.dismiss();
        speak(context.getString(R.string.favorites_saved), eventId);
      } else {
        speak(context.getString(R.string.favorites_save_failed), eventId);
      }
    });
  }

  private void refreshList() {
    if (adapter == null) {
      return;
    }
    adapter.clear();
    List<String> entries = favorites ? FavoriteTexts.getEntries() : ClipboardHistory.getEntries();
    adapter.addAll(filterEntries(entries, query));
    clearAll.setEnabled(!entries.isEmpty());
    emptyText.setText(!query.isEmpty() ? R.string.text_collection_no_results
        : favorites ? R.string.favorites_empty : R.string.clipboard_history_empty);
    adapter.notifyDataSetChanged();
  }

  private void speak(String text, EventId eventId) {
    if (speaker == null) {
      return;
    }
    speaker.speak(
        text,
        eventId,
        SpeakOptions.create()
            .setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
            .setFlags(FeedbackItem.FLAG_NO_HISTORY | FeedbackItem.FLAG_FORCE_FEEDBACK_ALL));
  }

  static List<String> filterEntries(List<String> entries, String query) {
    String normalizedQuery = normalizeForSearch(query);
    if (normalizedQuery.isEmpty()) {
      return new ArrayList<>(entries);
    }
    List<String> filtered = new ArrayList<>();
    for (String entry : entries) {
      if (normalizeForSearch(entry).contains(normalizedQuery)) {
        filtered.add(entry);
      }
    }
    return filtered;
  }

  static String normalizeForSearch(String text) {
    if (text == null) {
      return "";
    }
    return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .replace('đ', 'd');
  }
}
