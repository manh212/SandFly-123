package com.google.android.accessibility.talkback.screenrecognition;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RecognitionLayout {

  private RecognitionLayout() {}

  static List<RecognizedItem> splitLine(
      String text, Rect bounds, List<RecognizedItem> words, List<Rect> controls) {
    List<RecognizedItem> result = new ArrayList<>();
    if (words.isEmpty()) {
      if (!text.trim().isEmpty() && !bounds.isEmpty()) {
        result.add(new RecognizedItem(text.trim(), bounds, false));
      }
      return result;
    }
    Rect group = null;
    Rect previous = null;
    int previousControl = -1;
    StringBuilder label = new StringBuilder();
    for (RecognizedItem word : words) {
      if (word.text.trim().isEmpty() || word.bounds.isEmpty()) {
        continue;
      }
      int control = controlAt(word.bounds, controls);
      boolean separate =
          previous != null
              && (word.bounds.left - previous.right
                      > Math.max(previous.height(), word.bounds.height())
                  || (control != previousControl && (control >= 0 || previousControl >= 0)));
      if (separate && group != null) {
        result.add(new RecognizedItem(label.toString(), group, false));
        group = null;
        label.setLength(0);
      }
      if (group == null) {
        group = new Rect(word.bounds);
      } else {
        group.union(word.bounds);
        label.append(' ');
      }
      label.append(word.text.trim());
      previous = word.bounds;
      previousControl = control;
    }
    if (group != null) {
      result.add(new RecognizedItem(label.toString(), group, false));
    }
    return result;
  }

  private static int controlAt(Rect word, List<Rect> controls) {
    int best = -1;
    long area = Long.MAX_VALUE;
    for (int i = 0; i < controls.size(); i++) {
      Rect control = controls.get(i);
      long candidateArea = (long) control.width() * control.height();
      if (control.contains(word.centerX(), word.centerY()) && candidateArea < area) {
        best = i;
        area = candidateArea;
      }
    }
    return best;
  }

  static void sort(List<RecognizedItem> items) {
    items.sort(Comparator.comparingInt((RecognizedItem item) -> item.bounds.top)
        .thenComparingInt(item -> item.bounds.left));
    List<RecognizedItem> ordered = new ArrayList<>();
    List<RecognizedItem> remaining = new ArrayList<>(items);
    while (!remaining.isEmpty()) {
      RecognizedItem anchor = remaining.remove(0);
      List<RecognizedItem> row = new ArrayList<>();
      row.add(anchor);
      for (int i = 0; i < remaining.size();) {
        RecognizedItem candidate = remaining.get(i);
        int overlap = Math.min(anchor.bounds.bottom, candidate.bounds.bottom)
            - Math.max(anchor.bounds.top, candidate.bounds.top);
        if (overlap > 0
            && overlap * 2 >= Math.min(anchor.bounds.height(), candidate.bounds.height())) {
          row.add(remaining.remove(i));
        } else {
          i++;
        }
      }
      row.sort(Comparator.comparingInt(item -> item.bounds.left));
      ordered.addAll(row);
    }
    items.clear();
    items.addAll(ordered);
  }

  static int itemAt(List<RecognizedItem> items, int x, int y) {
    int best = -1;
    long area = Long.MAX_VALUE;
    for (int i = 0; i < items.size(); i++) {
      Rect bounds = items.get(i).bounds;
      long candidateArea = (long) bounds.width() * bounds.height();
      if (bounds.contains(x, y) && candidateArea < area) {
        best = i;
        area = candidateArea;
      }
    }
    return best;
  }
}
