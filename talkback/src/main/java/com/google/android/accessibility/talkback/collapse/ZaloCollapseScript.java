package com.google.android.accessibility.talkback.collapse;

import android.text.TextUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;

final class ZaloCollapseScript extends CollapseScript {
  static final String PACKAGE_NAME = "com.zing.zalo";
  static final String CONVERSATION_LIST_ID = PACKAGE_NAME + ":id/recycler_view_msgList";
  static final String MENU_LIST_ID = PACKAGE_NAME + ":id/rv_popover_options";
  static final String MENU_LABEL_ID = PACKAGE_NAME + ":id/tv_title";

  private static final MenuCatalog MENU_CATALOG =
      new MenuCatalog(
          new MenuCatalog.Item[] {
            new MenuCatalog.Item(
                R.string.title_collapse_zalo_read,
                new String[] {"Đánh dấu đã đọc", "Đánh dấu chưa đọc"},
                new String[] {"đã đọc", "chưa đọc"}),
            new MenuCatalog.Item(
                R.string.title_collapse_zalo_pin,
                new String[] {"Ghim", "Bỏ ghim"},
                new String[] {"ghim"}),
            new MenuCatalog.Item(
                R.string.title_collapse_zalo_notifications,
                new String[] {"Tắt thông báo", "Bật thông báo"},
                new String[] {"thông báo"},
                false),
            new MenuCatalog.Item(
                R.string.title_collapse_zalo_priority,
                new String[] {"Chuyển sang mục Khác", "Chuyển sang mục Ưu tiên"},
                new String[] {"chuyển sang mục"}),
            new MenuCatalog.Item(R.string.title_collapse_zalo_hide, null, null, false),
            new MenuCatalog.Item(R.string.title_collapse_zalo_delete, null, null, false),
            new MenuCatalog.Item(R.string.title_collapse_zalo_select, null, null, false),
          },
          MENU_LABEL_ID,
          0,
          MENU_LIST_ID,
          true);

  ZaloCollapseScript() {
    super(PACKAGE_NAME, Pattern.ROOT_MENU, 7);
  }

  @Override
  public MenuCatalog menuCatalog() {
    return MENU_CATALOG;
  }

  @Override
  public boolean isMenuItemRoot(AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat parent = node.getParent();
    return parent != null
        && TextUtils.equals(PACKAGE_NAME, parent.getPackageName())
        && CONVERSATION_LIST_ID.equals(parent.getViewIdResourceName())
        && TextUtils.equals("android.widget.FrameLayout", node.getClassName())
        && node.isVisibleToUser()
        && node.isFocusable()
        && node.isClickable()
        && node.isLongClickable()
        && node.getChildCount() == 0
        && !TextUtils.isEmpty(ItemScan.spokenLabel(node));
  }
}
