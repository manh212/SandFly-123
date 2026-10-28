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
import android.text.Editable;
import android.text.TextWatcher;
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

  private final Context context;
  private final SpeechControllerImpl speaker;

  private AlertDialog dialog;
  private ArrayAdapter<String> adapter;
  private String query = "";

  public ClipboardHistoryDialog(Context context, SpeechControllerImpl speaker) {
    this.context = context;
    this.speaker = speaker;
  }

  public void show(EventId eventId, @Nullable AccessibilityNodeInfoCompat pasteTarget) {
    // A clip copied while the change listener could not read it may be readable now, with this
    // dialog's window in front; recording it here keeps the list from missing it.
    ClipboardHistoryCapture.captureCurrent(context);
    if (ClipboardHistory.getEntries().isEmpty()) {
      speak(context.getString(R.string.clipboard_history_empty), eventId);
      return;
    }

    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 16, context.getResources().getDisplayMetrics());
    layout.setPadding(padding, padding, padding, padding);

    EditText search = new EditText(context);
    search.setHint(R.string.clipboard_history_search_hint);
    search.setSingleLine(true);
    layout.addView(search, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    ListView list = new ListView(context);
    layout.addView(list, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    Button clearAll = new Button(context);
    clearAll.setText(R.string.clipboard_history_clear_all);
    layout.addView(clearAll, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
      if (pasteTarget == null) {
        speak(context.getString(R.string.clipboard_history_no_target), eventId);
        return;
      }
      if (!pasteTarget.refresh() || !pasteTarget.isEditable()) {
        speak(context.getString(R.string.clipboard_history_paste_failed), eventId);
        return;
      }
      ClipboardUtils.copyToClipboardWithoutRecording(context, entry);
      boolean pasted = PerformActionUtils.performAction(
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
      ClipboardHistory.remove(entry);
      if (ClipboardHistory.getEntries().isEmpty()) {
        speak(context.getString(R.string.clipboard_history_deleted), eventId);
        dialog.dismiss();
      } else {
        refreshList();
        speak(context.getString(R.string.clipboard_history_deleted), eventId);
      }
      return true;
    });

    clearAll.setOnClickListener(v -> {
      AlertDialog confirm =
          new AlertDialog.Builder(context)
              .setMessage(R.string.clipboard_history_clear_confirm)
              .setPositiveButton(R.string.clipboard_history_clear, (clicked, which) -> {
                ClipboardHistory.clear();
                dialog.dismiss();
                speak(context.getString(R.string.clipboard_history_cleared), eventId);
              })
              .setNegativeButton(android.R.string.cancel, null)
              .create();
      confirm.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
      confirm.show();
    });

    dialog =
        new AlertDialog.Builder(context)
            .setTitle(R.string.title_clipboard_history)
            .setView(layout)
            .create();
    dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
    dialog.show();
  }

  private void refreshList() {
    if (adapter == null) {
      return;
    }
    adapter.clear();
    adapter.addAll(filterEntries(ClipboardHistory.getEntries(), query));
    adapter.notifyDataSetChanged();
  }

  private void speak(String text, EventId eventId) {
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
