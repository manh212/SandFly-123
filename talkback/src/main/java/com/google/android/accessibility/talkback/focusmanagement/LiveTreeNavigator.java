/*
 * Copyright 2026 The SandFly Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.focusmanagement;

import android.graphics.Rect;
import android.os.SystemClock;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Finds the next or previous stop of a directional swipe on the live accessibility tree,
 * following the pattern the Jieshuo analysis documented: start from the focused node, walk
 * the tree as it stands in pre-order — each container's children in geometric reading
 * order — and stop the moment the target is met. No order is kept between swipes, so the
 * tree walked can never be a stale copy of the window. Inside a list-like collection the
 * walk flattens to the items, as that pattern does: the rest of the current item, then
 * whole items in the swipe's direction.
 *
 * <p>This class only finds. The caller focuses what it returns through the same machinery
 * it uses for its own finds, so everything after the find — the speaking path, prepared
 * announcements included — is identical on both paths, and the comparison the switch
 * offers is a comparison of the find alone.
 *
 * <p>It answers only when it is sure, and hands the swipe back (returns {@code null})
 * whenever it is not. Web content is not its business: a web pivot, a web subtree, and a
 * target inside one all go back to the web navigation the caller runs instead. The end
 * of the content is not its business either: when no further stop exists on the live
 * tree it hands back, and the caller's own scroll and edge handling answers the swipe
 * exactly as it does with this engine switched off. A pivot that cannot be located on
 * the live tree, and a target that died while it was being walked to, also go back.
 * Nothing here scrolls, wraps, or announces.
 */
public class LiveTreeNavigator {

  /** Children whose tops sit within this distance of a row's first top share that row. */
  private static final int ROW_TOLERANCE_PX = 15;

  /** What one search produced: a target, or the reason the swipe was handed back. */
  private static class Result {
    @Nullable AccessibilityNodeInfoCompat target;
    int fall = Performance.PATTERN_FALL_END_OF_CONTENT;
  }

  /** A collection container on the pivot's ancestor chain, and its child the pivot is under. */
  private static class CollectionFrame {
    AccessibilityNodeInfoCompat collection;
    AccessibilityNodeInfoCompat item;
  }

  /**
   * Returns the node a swipe should land on, or {@code null} when this engine hands the
   * swipe back to the original navigation.
   *
   * @param pivot the node the original navigation starts from for this swipe
   * @param forward the swipe's logical direction, after any RTL conversion
   * @param rtl whether the screen layout is right-to-left, for ordering within a row
   * @param filter which nodes count as stops — the caller's own notion, unchanged
   */
  public @Nullable AccessibilityNodeInfoCompat findTarget(
      AccessibilityNodeInfoCompat pivot,
      boolean forward,
      boolean rtl,
      Filter<AccessibilityNodeInfoCompat> filter) {
    long findStart = SystemClock.uptimeMillis();
    Result result = search(pivot, forward, rtl, filter);
    if (result.target == null) {
      Performance.getInstance().onPatternEngineFall(result.fall);
      return null;
    }
    if (!result.target.refresh()) {
      // The tree moved under the walk: the target is gone, and the original navigation
      // is the one to answer from the tree as it now stands.
      result.target.recycle();
      Performance.getInstance().onPatternEngineFall(Performance.PATTERN_FALL_TARGET_GONE);
      return null;
    }
    Performance.getInstance().onPatternEngineAnswer(SystemClock.uptimeMillis() - findStart);
    return result.target;
  }

  private Result search(
      AccessibilityNodeInfoCompat pivot,
      boolean forward,
      boolean rtl,
      Filter<AccessibilityNodeInfoCompat> filter) {
    Result result = new Result();
    if (isWebPivot(pivot)) {
      result.fall = Performance.PATTERN_FALL_WEB;
      return result;
    }
    CollectionFrame frame = collectionFrameOf(pivot);
    if (frame != null) {
      try {
        if (searchCollection(frame, pivot, forward, rtl, filter, result)) {
          return result;
        }
        // The collection holds the pivot, but the walk could not place it there; let
        // the general walk below try, rather than handing back on a bookkeeping doubt.
        result.target = null;
        result.fall = Performance.PATTERN_FALL_END_OF_CONTENT;
      } finally {
        frame.collection.recycle();
        frame.item.recycle();
      }
    }
    AccessibilityNodeInfoCompat root = AccessibilityNodeInfoUtils.getRoot(pivot);
    if (root == null) {
      result.fall = Performance.PATTERN_FALL_PIVOT_NOT_IN_TREE;
      return result;
    }
    Walker walker = new Walker(pivot, forward, rtl, filter, /* passedPivot= */ false);
    walker.walk(root);
    result.target = walker.target;
    if (result.target == null) {
      result.fall =
          walker.sawPivot
              ? Performance.PATTERN_FALL_END_OF_CONTENT
              : Performance.PATTERN_FALL_PIVOT_NOT_IN_TREE;
    }
    return result;
  }

  /**
   * Searches within the collection the pivot sits in, and nowhere else: the rest of the
   * pivot's own item, then whole items in the swipe's direction. Running out of items is
   * the collection's edge, which is the caller's to answer (it scrolls there, as today).
   *
   * @return whether the search engaged — it does not when the pivot cannot be placed in
   *     the collection after all, in which case the caller falls back to a general walk
   */
  private boolean searchCollection(
      CollectionFrame frame,
      AccessibilityNodeInfoCompat pivot,
      boolean forward,
      boolean rtl,
      Filter<AccessibilityNodeInfoCompat> filter,
      Result result) {
    // The items are always listed in reading order; the step below walks them in the
    // swipe's direction. (Listing them reversed for a backward swipe as well would turn
    // the step around twice, and the items before the pivot would never be walked.)
    List<AccessibilityNodeInfoCompat> items = orderedChildren(frame.collection, true, rtl);
    try {
      int pivotItemIndex = -1;
      for (int i = 0; i < items.size(); i++) {
        if (items.get(i).equals(frame.item)) {
          pivotItemIndex = i;
          break;
        }
      }
      if (pivotItemIndex < 0) {
        return false;
      }
      Walker ownItem = new Walker(pivot, forward, rtl, filter, /* passedPivot= */ false);
      ownItem.walk(AccessibilityNodeInfoCompat.obtain(frame.item));
      if (ownItem.target != null) {
        result.target = ownItem.target;
        return true;
      }
      if (!ownItem.sawPivot) {
        return false;
      }
      int step = forward ? 1 : -1;
      for (int i = pivotItemIndex + step; i >= 0 && i < items.size(); i += step) {
        Walker wholeItem = new Walker(pivot, forward, rtl, filter, /* passedPivot= */ true);
        wholeItem.walk(AccessibilityNodeInfoCompat.obtain(items.get(i)));
        if (wholeItem.target != null) {
          result.target = wholeItem.target;
          return true;
        }
      }
      result.fall = Performance.PATTERN_FALL_END_OF_CONTENT;
      return true;
    } finally {
      for (AccessibilityNodeInfoCompat item : items) {
        item.recycle();
      }
    }
  }

  /**
   * Finds the nearest list-like collection on the pivot's ancestor chain, with the child
   * of it the pivot sits under. Returns {@code null} when the pivot is in no collection,
   * or is the collection itself.
   */
  private static @Nullable CollectionFrame collectionFrameOf(
      AccessibilityNodeInfoCompat pivot) {
    AccessibilityNodeInfoCompat child = AccessibilityNodeInfoCompat.obtain(pivot);
    AccessibilityNodeInfoCompat current = child.getParent();
    while (current != null) {
      if (isCollectionContainer(current)) {
        CollectionFrame frame = new CollectionFrame();
        frame.collection = current;
        frame.item = child;
        return frame;
      }
      child.recycle();
      child = current;
      current = current.getParent();
    }
    child.recycle();
    return null;
  }

  private static boolean isCollectionContainer(AccessibilityNodeInfoCompat node) {
    if (node.getCollectionInfo() != null) {
      return true;
    }
    CharSequence className = node.getClassName();
    if (className == null) {
      return false;
    }
    String name = className.toString();
    // "AbsListView" ends with "ListView"; RecyclerView keeps its name in both libraries.
    return name.endsWith("ListView") || name.endsWith("GridView") || name.endsWith("RecyclerView");
  }

  /** Whether the pivot is web content, or sits inside a web view. */
  private static boolean isWebPivot(AccessibilityNodeInfoCompat pivot) {
    if (WebInterfaceUtils.supportsWebActions(pivot)) {
      return true;
    }
    AccessibilityNodeInfoCompat current = AccessibilityNodeInfoCompat.obtain(pivot);
    while (current != null) {
      boolean webView = Role.getRole(current) == Role.ROLE_WEB_VIEW;
      AccessibilityNodeInfoCompat parent = webView ? null : current.getParent();
      current.recycle();
      if (webView) {
        return true;
      }
      current = parent;
    }
    return false;
  }

  /** Whether a node is web content this engine neither stops on nor walks into. */
  private static boolean isWebBarrier(AccessibilityNodeInfoCompat node) {
    return WebInterfaceUtils.supportsWebActions(node)
        || Role.getRole(node) == Role.ROLE_WEB_VIEW;
  }

  /**
   * The children of {@code node} in geometric reading order: rows from the top, each row
   * in reading direction, children whose tops sit within {@link #ROW_TOLERANCE_PX} of the
   * row's first top sharing that row. For a backward walk the finished order is reversed.
   * The returned copies belong to the caller.
   */
  private static List<AccessibilityNodeInfoCompat> orderedChildren(
      AccessibilityNodeInfoCompat node, boolean forward, boolean rtl) {
    List<AccessibilityNodeInfoCompat> children = new ArrayList<>();
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null) {
        children.add(child);
      }
    }
    children.sort(
        Comparator.comparingInt(LiveTreeNavigator::top).thenComparingInt(LiveTreeNavigator::left));
    List<AccessibilityNodeInfoCompat> ordered = new ArrayList<>(children.size());
    int i = 0;
    while (i < children.size()) {
      int rowTop = top(children.get(i));
      List<AccessibilityNodeInfoCompat> row = new ArrayList<>();
      while (i < children.size() && top(children.get(i)) - rowTop <= ROW_TOLERANCE_PX) {
        row.add(children.get(i));
        i++;
      }
      row.sort(
          (a, b) ->
              rtl
                  ? Integer.compare(left(b), left(a))
                  : Integer.compare(left(a), left(b)));
      ordered.addAll(row);
    }
    if (!forward) {
      Collections.reverse(ordered);
    }
    return ordered;
  }

  private static int top(AccessibilityNodeInfoCompat node) {
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    return bounds.top;
  }

  private static int left(AccessibilityNodeInfoCompat node) {
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    return bounds.left;
  }

  /**
   * One early-exit walk over a subtree. Forward, it enumerates pre-order; backward, it
   * enumerates the exact reverse of pre-order (children in reverse order before the node
   * itself), so one state machine serves both: nothing counts until the pivot has been
   * passed, and the first node after it that the filter accepts ends the walk.
   */
  private static class Walker {
    private final AccessibilityNodeInfoCompat pivot;
    private final boolean forward;
    private final boolean rtl;
    private final Filter<AccessibilityNodeInfoCompat> filter;
    private final boolean pivotFocused;
    private boolean passedPivot;
    private boolean sawPivot;
    private @Nullable AccessibilityNodeInfoCompat target;

    Walker(
        AccessibilityNodeInfoCompat pivot,
        boolean forward,
        boolean rtl,
        Filter<AccessibilityNodeInfoCompat> filter,
        boolean passedPivot) {
      this.pivot = pivot;
      this.forward = forward;
      this.rtl = rtl;
      this.filter = filter;
      this.pivotFocused = pivot.isAccessibilityFocused();
      this.passedPivot = passedPivot;
    }

    /** Walks the subtree under {@code node}, taking ownership of that copy. */
    void walk(AccessibilityNodeInfoCompat node) {
      try {
        if (target != null) {
          return;
        }
        if (forward) {
          visit(node);
          if (target == null) {
            descend(node);
          }
        } else {
          descend(node);
          if (target == null) {
            visit(node);
          }
        }
      } finally {
        if (node != target) {
          node.recycle();
        }
      }
    }

    private void descend(AccessibilityNodeInfoCompat node) {
      if (isWebBarrier(node)) {
        return;
      }
      List<AccessibilityNodeInfoCompat> children = orderedChildren(node, forward, rtl);
      for (int i = 0; i < children.size(); i++) {
        if (target != null) {
          children.get(i).recycle();
        } else {
          walk(children.get(i));
        }
      }
    }

    private void visit(AccessibilityNodeInfoCompat node) {
      if (isPivot(node)) {
        passedPivot = true;
        sawPivot = true;
        return;
      }
      if (!passedPivot || target != null || isWebBarrier(node)) {
        return;
      }
      if (filter.accept(node)) {
        target = node;
      }
    }

    private boolean isPivot(AccessibilityNodeInfoCompat node) {
      // The pivot arrives as another copy of the node; equality is the test, with the
      // live tree's own focus flag as a second signal for the same node.
      return node.equals(pivot) || (pivotFocused && node.isAccessibilityFocused());
    }
  }
}
