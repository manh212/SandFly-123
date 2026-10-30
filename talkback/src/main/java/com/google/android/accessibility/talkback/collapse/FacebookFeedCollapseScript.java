package com.google.android.accessibility.talkback.collapse;

import android.text.TextUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.Role;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

final class FacebookFeedCollapseScript extends CollapseScript {
  static final String TRAY_SIGNATURE = "Xem thêm lựa chọn cho bài viết này";
  private static final String OPTIONS_PREFIX = "Lựa chọn khác cho bài viết của ";
  private static final String FIELD_BODY = "body";
  private static final TrayCatalog TRAY =
      new TrayCatalog(
          new TrayCatalog.Entry[] {
            new TrayCatalog.Entry(
                "Thích", "Like", true, false, new String[] {"Thích", "Bỏ thích"}),
            new TrayCatalog.Entry("Bày tỏ cảm xúc", "React", false, false),
            new TrayCatalog.Entry("Bình luận", "Comment", false, false),
            new TrayCatalog.Entry("Chia sẻ", "Share", false, false),
            new TrayCatalog.Entry(TRAY_SIGNATURE, "More post options", false, false),
            new TrayCatalog.Entry(
                "Xem phần mô tả chi tiết về hình ảnh", "View image description", false, false),
          },
          TRAY_SIGNATURE);

  FacebookFeedCollapseScript() {
    super(FacebookCollapseScript.PACKAGE_NAME, Pattern.COMPOSITE_TRAY, 12);
  }

  @Override
  public TrayCatalog trayCatalog() {
    return TRAY;
  }

  @Override
  public String selectionFieldId() {
    return FIELD_BODY;
  }

  static boolean isFeedItem(ItemScan scan) {
    for (AccessibilityNodeInfoCompat node : scan.clickables()) {
      String label = ItemScan.spokenLabel(node).toString();
      if (Role.getRole(node) == Role.ROLE_BUTTON
          && (label.startsWith(OPTIONS_PREFIX)
              || (label.startsWith("Nút Chia sẻ.") && label.contains("bài viết")))) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected boolean isTrayCounterpart(AccessibilityNodeInfoCompat node) {
    String label = ItemScan.spokenLabel(node).toString();
    return label.startsWith(OPTIONS_PREFIX)
        || label.startsWith("Nút Thích.")
        || label.startsWith("Nút Chia sẻ.")
        || label.equals("Thích")
        || label.equals("Bỏ thích")
        || label.equals("Bình luận")
        || label.equals("Chia sẻ");
  }

  @Override
  protected @Nullable List<Field> resolveFields(ItemScan scan) {
    AccessibilityNodeInfoCompat parent = scan.itemRoot().getParent();
    if (parent == null || !"android:id/list".equals(parent.getViewIdResourceName())) {
      return null;
    }
    AccessibilityNodeInfoCompat options = null;
    String authorName = null;
    for (AccessibilityNodeInfoCompat node : scan.clickables()) {
      String label = ItemScan.spokenLabel(node).toString();
      if (Role.getRole(node) == Role.ROLE_BUTTON && label.startsWith(OPTIONS_PREFIX)) {
        if (options != null) {
          return null;
        }
        options = node;
        authorName = label.substring(OPTIONS_PREFIX.length()).trim();
      }
    }
    AccessibilityNodeInfoCompat header = options == null ? null : options.getParent();
    if (header == null || TextUtils.isEmpty(authorName)) {
      return null;
    }
    AccessibilityNodeInfoCompat author = null;
    AccessibilityNodeInfoCompat time = null;
    for (int i = 0; i < header.getChildCount(); i++) {
      AccessibilityNodeInfoCompat node = header.getChild(i);
      if (node == null || !node.isVisibleToUser() || !node.isFocusable()) {
        continue;
      }
      CharSequence label = ItemScan.spokenLabel(node);
      if (TextUtils.equals(authorName, label)) {
        if (author != null) {
          return null;
        }
        author = node;
      } else if (!node.isClickable() && !TextUtils.isEmpty(label)) {
        if (time != null) {
          return null;
        }
        time = node;
      }
    }
    AccessibilityNodeInfoCompat body = null;
    for (AccessibilityNodeInfoCompat node : scan.clickables()) {
      if (!node.isLongClickable()
          || TextUtils.isEmpty(node.getText())
          || AccessibilityNodeInfoUtils.hasAncestor(node, header)
          || Role.getRole(node) == Role.ROLE_IMAGE
          || Role.getRole(node) == Role.ROLE_IMAGE_BUTTON) {
        continue;
      }
      if (body != null) {
        return null;
      }
      body = node;
    }
    if (author == null || time == null || body == null) {
      return null;
    }
    return List.of(
        new Field("author", author, ItemScan.spokenLabel(author)),
        new Field("time", time, ItemScan.spokenLabel(time)),
        new Field(FIELD_BODY, body, ItemScan.spokenLabel(body)));
  }
}
