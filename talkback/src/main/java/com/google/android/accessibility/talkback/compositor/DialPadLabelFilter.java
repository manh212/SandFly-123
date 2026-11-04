package com.google.android.accessibility.talkback.compositor;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.Locale;

public final class DialPadLabelFilter {
  private DialPadLabelFilter() {}

  public static CharSequence filter(
      AccessibilityNodeInfoCompat node, CharSequence label) {
    if (node == null || !node.isClickable() || node.getPackageName() == null) {
      return label;
    }
    return filter(node.getPackageName().toString(), label);
  }

  static CharSequence filter(String packageName, CharSequence label) {
    if (label == null
        || !(packageName.equals("com.google.android.dialer")
            || packageName.equals("com.android.dialer")
            || packageName.equals("com.samsung.android.dialer")
            || packageName.equals("com.android.contacts")
            || packageName.endsWith(".dialer"))) {
      return label;
    }
    String compact = label.toString().trim().replaceAll("[\\s,.;:()\\-]+", "");
    if (compact.length() < 2) return label;
    char digit = compact.charAt(0);
    String letters =
        switch (digit) {
          case '2' -> "ABC";
          case '3' -> "DEF";
          case '4' -> "GHI";
          case '5' -> "JKL";
          case '6' -> "MNO";
          case '7' -> "PQRS";
          case '8' -> "TUV";
          case '9' -> "WXYZ";
          default -> "";
        };
    if (!letters.isEmpty()
        && compact.substring(1).toUpperCase(Locale.ROOT).equals(letters)) {
      return String.valueOf(digit);
    }
    return label;
  }
}
