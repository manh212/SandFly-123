/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.google.android.accessibility.talkback.labeling;

import android.text.TextUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.Role;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A label key for buttons that have no view resource ID. The database expects "package:id/name",
 * so an ID-less button uses a deterministic key derived from its position in its window tree.
 * Keys are scoped by application and screen; moving a control in an app may change its key.
 */
public final class LabelNodeId {
  private LabelNodeId() {}

  public static @Nullable String resourceName(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null) {
      return null;
    }
    if (!TextUtils.isEmpty(node.getViewIdResourceName())) {
      return node.getViewIdResourceName();
    }
    int role = Role.getRole(node);
    if (role != Role.ROLE_BUTTON && role != Role.ROLE_IMAGE_BUTTON) {
      return null;
    }
    String packageName = node.getPackageName() == null ? "" : node.getPackageName().toString();
    if (packageName.isEmpty() || !packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
      return null;
    }
    StringBuilder identity = new StringBuilder(packageName);
    if (node.getWindow() != null && node.getWindow().getTitle() != null) {
      identity.append("|window:").append(node.getWindow().getTitle());
    }
    List<String> path = new ArrayList<>();
    AccessibilityNodeInfoCompat child = node;
    for (int depth = 0; depth < 32; depth++) {
      AccessibilityNodeInfoCompat parent = child.getParent();
      int position = -1;
      if (parent != null) {
        for (int index = 0; index < parent.getChildCount(); index++) {
          if (child.equals(parent.getChild(index))) {
            position = index;
            break;
          }
        }
      }
      path.add(String.valueOf(child.getClassName()) + ":" + position
          + ":" + (child.getViewIdResourceName() == null ? "" : child.getViewIdResourceName()));
      if (parent == null) {
        break;
      }
      child = parent;
    }
    for (int index = path.size() - 1; index >= 0; index--) {
      identity.append('/').append(path.get(index));
    }
    return packageName + ":id/sandfly_unlabeled_" + shortHash(identity.toString());
  }

  /** Returns true if a button or any of its descendants already provides readable text. */
  public static boolean hasSpokenLabel(@Nullable AccessibilityNodeInfoCompat node) {
    return hasSpokenLabel(node, 0);
  }

  private static boolean hasSpokenLabel(@Nullable AccessibilityNodeInfoCompat node, int depth) {
    if (node == null) {
      return false;
    }
    if (!TextUtils.isEmpty(AccessibilityNodeInfoUtils.getNodeText(node))
        || !TextUtils.isEmpty(node.getHintText())) {
      return true;
    }
    if (depth >= 8) {
      return false;
    }
    for (int index = 0; index < node.getChildCount(); index++) {
      if (hasSpokenLabel(node.getChild(index), depth + 1)) {
        return true;
      }
    }
    return false;
  }

  private static String shortHash(String input) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(input.getBytes(StandardCharsets.UTF_8));
      char[] digits = "0123456789abcdef".toCharArray();
      StringBuilder output = new StringBuilder();
      for (int i = 0; i < 12; i++) {
        int value = digest[i] & 0xff;
        output.append(digits[value >>> 4]).append(digits[value & 0x0f]);
      }
      return output.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
