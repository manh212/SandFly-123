/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.focusmanagement;

import android.graphics.Rect;
import android.os.SystemClock;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.Role;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Finds content that is in the node tree but off screen, so a swipe can scroll just far enough to
 * show it. Scroll views lay out all of their content, so the next item exists before it is shown.
 * Lists and grids recycle their rows, and pagers hold whole pages off to the side, so those are
 * left to the usual page scroll. So are Compose scrolling columns, which animate to show an item,
 * so the search right after would still find it off screen.
 */
final class OffscreenContent {
  private static final int MAX_NODE_QUERIES = 48;
  private static final long SEARCH_BUDGET_MS = 24;

  private OffscreenContent() {}

  /**
   * Whether {@code scrollable} is a scroll view, which keeps all of its content in the tree and
   * scrolls to show an item before the action returns. AndroidX nested scroll views say they are
   * scroll views too.
   */
  static boolean keepsAllContent(AccessibilityNodeInfoCompat scrollable) {
    int role = Role.getRole(scrollable);
    return scrollable.isVisibleToUser()
        && scrollable.getCollectionInfo() == null
        && (role == Role.ROLE_SCROLL_VIEW || role == Role.ROLE_HORIZONTAL_SCROLL_VIEW);
  }

  static @Nullable AccessibilityNodeInfoCompat findNext(
      AccessibilityNodeInfoCompat scrollable, AccessibilityNodeInfoCompat pivot, boolean forward) {
    Search search = new Search(forward);
    Deque<AccessibilityNodeInfoCompat> path = new ArrayDeque<>();
    AccessibilityNodeInfoCompat node = pivot;
    while (node != null && !node.equals(scrollable) && search.canQuery()) {
      path.push(node);
      node = node.getParent();
    }
    if (!scrollable.equals(node) || path.isEmpty()) {
      return null;
    }

    AccessibilityNodeInfoCompat parent = scrollable;
    Deque<ParentPosition> positions = new ArrayDeque<>();
    while (!path.isEmpty()) {
      AccessibilityNodeInfoCompat child = path.pop();
      int index = search.indexOf(parent, child);
      if (index < 0) {
        return null;
      }
      positions.push(new ParentPosition(parent, index));
      if (!path.isEmpty() && hidesChildren(child)) {
        break;
      }
      parent = child;
    }

    while (!positions.isEmpty() && search.hasTime() && search.queriesRemaining >= 0) {
      ParentPosition position = positions.pop();
      AccessibilityNodeInfoCompat next = search.findInChildren(
          position.parent(), position.index() + search.step);
      if (next != null) {
        return search.hasTime() ? next : null;
      }
    }
    return null;
  }

  private record ParentPosition(AccessibilityNodeInfoCompat parent, int index) {}

  private static final class Search {
    private final long deadline = SystemClock.uptimeMillis() + SEARCH_BUDGET_MS;
    private final int step;
    private int queriesRemaining = MAX_NODE_QUERIES;

    Search(boolean forward) {
      step = forward ? 1 : -1;
    }

    boolean hasTime() {
      return SystemClock.uptimeMillis() < deadline;
    }

    boolean canQuery() {
      return queriesRemaining-- > 0 && hasTime();
    }

    int indexOf(AccessibilityNodeInfoCompat parent, AccessibilityNodeInfoCompat child) {
      int count = parent.getChildCount();
      for (int index = step > 0 ? 0 : count - 1;
          index >= 0 && index < count && canQuery();
          index += step) {
        if (child.equals(parent.getChild(index))) {
          return index;
        }
      }
      return -1;
    }

    @Nullable AccessibilityNodeInfoCompat findInChildren(
        AccessibilityNodeInfoCompat parent, int start) {
      int count = parent.getChildCount();
      for (int index = start; index >= 0 && index < count && canQuery(); index += step) {
        AccessibilityNodeInfoCompat child = parent.getChild(index);
        if (child == null || !hasTime()) {
          continue;
        }
        if (step > 0 && isCandidate(child)) {
          return child;
        }
        if (!hidesChildren(child)) {
          AccessibilityNodeInfoCompat nested =
              findInChildren(child, step > 0 ? 0 : child.getChildCount() - 1);
          if (nested != null) {
            return nested;
          }
        }
        if (step < 0 && queriesRemaining >= 0 && hasTime() && isCandidate(child)) {
          return child;
        }
      }
      return null;
    }
  }

  /**
   * Whether the children of a node inside the scroll view can be off screen without being scrolled
   * away: in a collapsed container, or in a nested container that scrolls on its own.
   */
  @SuppressWarnings("deprecation") // getBoundsInParent is the only unclipped size a node reports.
  private static boolean hidesChildren(AccessibilityNodeInfoCompat node) {
    Rect size = new Rect();
    node.getBoundsInParent(size);
    return size.width() <= 0
        || size.height() <= 0
        || node.isScrollable()
        || node.getCollectionInfo() != null
        || Role.getRole(node) == Role.ROLE_PAGER;
  }

  /** A node to visit, and whether it is hidden inside a container. */
  private record Visit(AccessibilityNodeInfoCompat node, boolean hidden) {}

  /**
   * Whether {@code node} is off screen or cut off at the edge, has a size, and has something to say
   * or do. A node cut off at the edge has not been read yet, and would go past the other edge.
   */
  @SuppressWarnings("deprecation") // getBoundsInParent is the only unclipped size a node reports.
  private static boolean isCandidate(AccessibilityNodeInfoCompat node) {
    Rect size = new Rect();
    node.getBoundsInParent(size);
    if (size.width() <= 0 || size.height() <= 0) {
      return false;
    }
    if (node.isVisibleToUser()) {
      Rect shown = new Rect();
      node.getBoundsInScreen(shown);
      if (shown.width() >= size.width() && shown.height() >= size.height()) {
        return false;
      }
    }
    return saysOrDoes(node);
  }

  private static boolean saysOrDoes(AccessibilityNodeInfoCompat node) {
    return !TextUtils.isEmpty(node.getText())
        || !TextUtils.isEmpty(node.getContentDescription())
        || node.isFocusable()
        || node.isClickable()
        || node.isScreenReaderFocusable();
  }
}
