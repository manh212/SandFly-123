/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 (the "License"); you may not
 * use this file except in compliance with the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.android.accessibility.utils.traversal;

import android.graphics.Rect;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.NodeOverrides;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Builds the same reading order {@link OrderedTraversalStrategy} builds, in one walk of the
 * window instead of several.
 *
 * <p>The standard build asks every node for its children two or three times over: once to
 * build the working tree, once more to work out each unfocusable node's useful bounds from its
 * children, and again inside the speaking checks. Each ask hands back a fresh copy of the
 * node's whole record, and at the size of a busy chat window those copies are most of what a
 * build costs. This builder asks each node for its children once, keeps the list, and works
 * everything else out from it: the useful bounds come from the same arithmetic {@link
 * NodeCachedBoundsCalculator} does, only bottom-up over the kept lists, and the reorder pass
 * reads each node's traversal-before/after once into a table instead of re-reading the
 * ancestors' at every move.
 *
 * <p>Everything that decides the order is the standard build's own: the focus and speaking
 * verdicts are {@link AccessibilityNodeInfoUtils}'s, the bounds clipping and the stripe
 * comparison are copied line for line, the moves are the controller's moves run over the same
 * {@link WorkingTree} objects, and the finished tree is handed to a real {@link
 * OrderedTraversalController}, so every search afterwards runs the standard code. The order is
 * only ever built this way with children of web-action nodes left out and no FAB promotion,
 * which is how the reading-order snapshot is built; anything else keeps the standard build.
 */
public final class LeanTraversalOrderBuilder {

  private static final String TAG = "LeanOrderBuilder";

  private static final Rect EMPTY_BOUNDS = new Rect();

  /** The speaking verdicts of the build, shared with the strategy handed out at the end. */
  private final Map<AccessibilityNodeInfoCompat, Boolean> speakingNodesCache = new HashMap<>();

  /** Each node's children, asked for once and kept for every later pass that needs them. */
  private final Map<AccessibilityNodeInfoCompat, List<AccessibilityNodeInfoCompat>> childrenCache =
      new HashMap<>();

  /** Each node's useful bounds, worked out at most once. */
  private final Map<AccessibilityNodeInfoCompat, Rect> boundsMemo = new HashMap<>();

  /** Nodes whose useful bounds are being worked out, so a loop in the tree stops the descent. */
  private final Set<AccessibilityNodeInfoCompat> boundsInProgress = new HashSet<>();

  /** Each node's traversal-before node, read once. A null value is a node that has none. */
  private final Map<AccessibilityNodeInfoCompat, AccessibilityNodeInfoCompat> traversalBeforeCache =
      new HashMap<>();

  /** Each node's traversal-after node, read once. A null value is a node that has none. */
  private final Map<AccessibilityNodeInfoCompat, AccessibilityNodeInfoCompat> traversalAfterCache =
      new HashMap<>();

  /** The working trees by their node, in the order the walk visited them. */
  private final Map<AccessibilityNodeInfoCompat, WorkingTree> nodeTreeMap = new LinkedHashMap<>();

  private final Rect tempRect = new Rect();
  private final Rect tempLeftBounds = new Rect();
  private final Rect tempRightBounds = new Rect();

  private @Nullable WorkingTree tree;
  private @Nullable AccessibilityNodeInfoCompat initialFocusNode;

  /** Builds the reading order of the window rooted at {@code root}. */
  public static OrderedTraversalStrategy build(@Nullable AccessibilityNodeInfoCompat root) {
    if (root == null) {
      return new OrderedTraversalStrategy(null);
    }
    return new LeanTraversalOrderBuilder().buildFrom(root);
  }

  private OrderedTraversalStrategy buildFrom(AccessibilityNodeInfoCompat root) {
    tree = createTree(root, null);
    reorderTree();
    OrderedTraversalController controller =
        new OrderedTraversalController(nodeTreeMap, tree, initialFocusNode, false);
    controller.setSpeakingNodesCache(speakingNodesCache);
    return new OrderedTraversalStrategy(controller, speakingNodesCache);
  }

  // ---------------------------------------------------------------------------------------------
  // The walk: mirrors OrderedTraversalController.createWorkingTree.

  private @Nullable WorkingTree createTree(
      AccessibilityNodeInfoCompat node, @Nullable WorkingTree parent) {
    if (nodeTreeMap.containsKey(node)) {
      LogUtils.w(TAG, "creating node tree with looped nodes - break the loop edge");
      return null;
    }

    WorkingTree workingTree = new WorkingTree(node, parent);
    nodeTreeMap.put(node, workingTree);

    // When we reach a node that supports web navigation, we traverse using the web navigation
    // actions, so we should not try to determine the ordering of its descendants.
    if (WebInterfaceUtils.supportsWebActions(node)) {
      return workingTree;
    }

    for (AccessibilityNodeInfoCompat child : reorderedChildren(node)) {
      WorkingTree childTree = createTree(child, workingTree);
      if (childTree != null) {
        workingTree.addChild(childTree);
      }
    }
    return workingTree;
  }

  /** A node's children, asked for once however many passes need them. */
  private List<AccessibilityNodeInfoCompat> childrenOf(AccessibilityNodeInfoCompat node) {
    List<AccessibilityNodeInfoCompat> children = childrenCache.get(node);
    if (children == null) {
      children = new ArrayList<>();
      int count = node.getChildCount();
      for (int i = 0; i < count; i++) {
        AccessibilityNodeInfoCompat child = node.getChild(i);
        if (child != null) {
          children.add(child);
        }
      }
      childrenCache.put(node, children);
    }
    return children;
  }

  // ---------------------------------------------------------------------------------------------
  // The sibling reorder: mirrors ReorderedChildrenIterator, over the kept child list.

  private List<AccessibilityNodeInfoCompat> reorderedChildren(AccessibilityNodeInfoCompat node) {
    List<AccessibilityNodeInfoCompat> nodes = new ArrayList<>(childrenOf(node));
    if (WebInterfaceUtils.isWebContainer(node) || nodes.size() <= 1) {
      return nodes;
    }
    boolean reorderingNeeded = false;
    for (AccessibilityNodeInfoCompat child : nodes) {
      if (usesChildrenBounds(child)) {
        reorderingNeeded = true;
        break;
      }
    }
    if (!reorderingNeeded) {
      return nodes;
    }

    AccessibilityNodeInfoCompat[] nodeArray =
        nodes.toArray(new AccessibilityNodeInfoCompat[nodes.size()]);
    int currentIndex = nodeArray.length - 2;
    while (currentIndex >= 0) {
      AccessibilityNodeInfoCompat currentNode = nodeArray[currentIndex];
      if (usesChildrenBounds(currentNode)) {
        moveNodeIfNecessary(nodeArray, currentIndex);
      }
      currentIndex--;
    }
    return new ArrayList<>(Arrays.asList(nodeArray));
  }

  private void moveNodeIfNecessary(AccessibilityNodeInfoCompat[] nodeArray, int index) {
    int size = nodeArray.length;
    int nextIndex = index + 1;
    AccessibilityNodeInfoCompat currentNode = nodeArray[index];
    while (nextIndex < size && needSwapNodeOrder(currentNode, nodeArray[nextIndex])) {
      nodeArray[nextIndex - 1] = nodeArray[nextIndex];
      nodeArray[nextIndex] = currentNode;
      nextIndex++;
    }
  }

  private boolean needSwapNodeOrder(
      AccessibilityNodeInfoCompat leftNode, AccessibilityNodeInfoCompat rightNode) {
    Rect leftBounds = getBounds(leftNode);
    Rect rightBounds = getBounds(rightNode);

    // The same double comparison as the standard iterator: swap only if the adjusted bounds
    // order the pair one way and the nodes' own bounds order them the other.
    if (compare(leftBounds, rightBounds) > 0) {
      leftNode.getBoundsInScreen(tempLeftBounds);
      rightNode.getBoundsInScreen(tempRightBounds);
      return compare(tempLeftBounds, tempRightBounds) < 0;
    }

    return false;
  }

  /**
   * The stripe comparison of the standard iterator, unchanged; it never returns 0. Layout is
   * always ordered left-to-right here, as it is there.
   */
  private static int compare(@Nullable Rect leftBounds, @Nullable Rect rightBounds) {
    if (leftBounds == null || rightBounds == null) {
      return -1;
    }

    // First is above second.
    if (leftBounds.bottom - rightBounds.top <= 0) {
      return -1;
    }
    // First is below second.
    if (leftBounds.top - rightBounds.bottom >= 0) {
      return 1;
    }

    // We are ordering left-to-right, top-to-bottom.
    final int leftDifference = leftBounds.left - rightBounds.left;
    if (leftDifference != 0) {
      return leftDifference;
    }
    // We are ordering left-to-right, top-to-bottom.
    final int topDifference = leftBounds.top - rightBounds.top;
    if (topDifference != 0) {
      return topDifference;
    }
    // Break tie by height.
    final int heightDifference = leftBounds.height() - rightBounds.height();
    if (heightDifference != 0) {
      return -heightDifference;
    }
    // Break tie by width.
    final int widthDifference = leftBounds.width() - rightBounds.width();
    if (widthDifference != 0) {
      return -widthDifference;
    }
    // Break tie somehow.
    return -1;
  }

  // ---------------------------------------------------------------------------------------------
  // Useful bounds: mirrors NodeCachedBoundsCalculator, over the kept child lists.

  private @Nullable Rect getBounds(AccessibilityNodeInfoCompat node) {
    Rect bounds = usefulBounds(node);
    if (bounds.equals(EMPTY_BOUNDS)) {
      return null;
    }
    return bounds;
  }

  private Rect usefulBounds(AccessibilityNodeInfoCompat node) {
    Rect bounds = boundsMemo.get(node);
    if (bounds != null) {
      return bounds;
    }
    if (!boundsInProgress.add(node)) {
      LogUtils.w(TAG, "node tree loop detected while calculating node bounds");
      return EMPTY_BOUNDS;
    }
    bounds = fetchBound(node);
    boundsInProgress.remove(node);
    boundsMemo.put(node, bounds);
    return bounds;
  }

  private Rect fetchBound(AccessibilityNodeInfoCompat node) {
    if (!AccessibilityNodeInfoUtils.isVisible(node)) {
      return EMPTY_BOUNDS;
    }

    if (AccessibilityNodeInfoUtils.shouldFocusNode(node, speakingNodesCache)) {
      Rect bounds = new Rect();
      node.getBoundsInScreen(bounds);
      return bounds;
    }

    int minTop = Integer.MAX_VALUE;
    int minLeft = Integer.MAX_VALUE;
    int maxBottom = Integer.MIN_VALUE;
    int maxRight = Integer.MIN_VALUE;
    boolean hasChildBounds = false;
    for (AccessibilityNodeInfoCompat child : childrenOf(node)) {
      Rect bounds = usefulBounds(child);
      if (!bounds.equals(EMPTY_BOUNDS)) {
        hasChildBounds = true;
        if (bounds.top < minTop) {
          minTop = bounds.top;
        }
        if (bounds.left < minLeft) {
          minLeft = bounds.left;
        }
        if (bounds.right > maxRight) {
          maxRight = bounds.right;
        }
        if (bounds.bottom > maxBottom) {
          maxBottom = bounds.bottom;
        }
      }
    }

    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (hasChildBounds) {
      bounds.top = Math.max(minTop, bounds.top);
      bounds.left = Math.max(minLeft, bounds.left);
      bounds.right = Math.min(maxRight, bounds.right);
      bounds.bottom = Math.min(maxBottom, bounds.bottom);
    }
    return bounds;
  }

  private boolean usesChildrenBounds(AccessibilityNodeInfoCompat node) {
    Rect bounds = getBounds(node);
    if (bounds == null) {
      return false;
    }
    node.getBoundsInScreen(tempRect);
    return !tempRect.equals(bounds);
  }

  // ---------------------------------------------------------------------------------------------
  // The reorder pass: mirrors OrderedTraversalController.reorderTree and its moves, with each
  // node's traversal-before/after read once instead of once per move that climbs past it.

  private void reorderTree() {
    Map<WorkingTree, ScriptedMove> scriptedMoves = findScriptedMoves();
    for (WorkingTree subtree : nodeTreeMap.values()) {
      AccessibilityNodeInfoCompat node = subtree.getNode();
      if (AccessibilityNodeInfoUtils.hasRequestInitialAccessibilityFocus(node)) {
        initialFocusNode = node;
      }
      ScriptedMove scriptedMove = scriptedMoves.get(subtree);
      if (scriptedMove != null) {
        if (scriptedMove.before) {
          moveNodeBefore(subtree, scriptedMove.target);
        } else {
          moveNodeAfter(subtree, scriptedMove.target);
        }
        continue;
      }
      AccessibilityNodeInfoCompat beforeNode = traversalBefore(node);
      if (beforeNode != null) {
        moveNodeBefore(subtree, nodeTreeMap.get(beforeNode));
      } else {
        AccessibilityNodeInfoCompat afterNode = traversalAfter(node);
        if (afterNode != null) {
          moveNodeAfter(subtree, nodeTreeMap.get(afterNode));
        }
      }
    }
  }

  private @Nullable AccessibilityNodeInfoCompat traversalBefore(
      AccessibilityNodeInfoCompat node) {
    if (!traversalBeforeCache.containsKey(node)) {
      traversalBeforeCache.put(node, node.getTraversalBefore());
    }
    return traversalBeforeCache.get(node);
  }

  private @Nullable AccessibilityNodeInfoCompat traversalAfter(AccessibilityNodeInfoCompat node) {
    if (!traversalAfterCache.containsKey(node)) {
      traversalAfterCache.put(node, node.getTraversalAfter());
    }
    return traversalAfterCache.get(node);
  }

  private Map<WorkingTree, ScriptedMove> findScriptedMoves() {
    Map<WorkingTree, ScriptedMove> moves = new HashMap<>();
    for (WorkingTree subtree : nodeTreeMap.values()) {
      NodeOverrides.Order order = NodeOverrides.order(subtree.getNode());
      if (order == null) {
        continue;
      }
      WorkingTree searched = subtree;
      for (WorkingTree scope = subtree.getParent(); scope != null; scope = scope.getParent()) {
        WorkingTree target = scope.findDescendant(order, searched);
        if (target != null) {
          moves.put(subtree, new ScriptedMove(target, order.isBefore()));
          break;
        }
        searched = scope;
      }
    }
    return moves;
  }

  private static final class ScriptedMove {
    final WorkingTree target;
    final boolean before;

    ScriptedMove(WorkingTree target, boolean before) {
      this.target = target;
      this.before = before;
    }
  }

  /** Moves movingTree before targetTree. */
  private void moveNodeBefore(@Nullable WorkingTree movingTree, @Nullable WorkingTree targetTree) {
    if (movingTree == null || targetTree == null) {
      return;
    }

    if (movingTree.hasDescendant(targetTree)) {
      // no operation if move child before parent
      return;
    }

    // Find subtree to move.
    WorkingTree movingTreeRoot = getParentsThatAreMovedBeforeOrSameNode(movingTree);

    // Find destination for movingTreeRoot.
    WorkingTree parent = targetTree.getParent();
    if (movingTreeRoot.hasDescendant(parent)) {
      return; // Moving movingTreeRoot under its own descendant would create a loop.
    }

    // Unlink moving subtree from tree.
    detachSubtreeFromItsParent(movingTreeRoot);

    // swap target node with moving node on targets node parent children list
    if (parent != null) {
      parent.swapChild(targetTree, movingTreeRoot);
    }

    movingTreeRoot.setParent(parent);

    // add target node as last child of moving node
    movingTree.addChild(targetTree);
    targetTree.setParent(movingTree);
  }

  /**
   * Checks if the parent of {@code movingTree} was itself moved onto this place by its own
   * before property pointing at the same node; in that case the parent moves with it.
   */
  private WorkingTree getParentsThatAreMovedBeforeOrSameNode(WorkingTree movingTree) {
    WorkingTree parent = movingTree.getParent();
    if (parent == null) {
      return movingTree;
    }

    AccessibilityNodeInfoCompat parentNodeBefore = traversalBefore(parent.getNode());
    if (parentNodeBefore == null) {
      return movingTree;
    }

    if (parentNodeBefore.equals(movingTree.getNode())) {
      return getParentsThatAreMovedBeforeOrSameNode(parent);
    }

    return movingTree;
  }

  private void detachSubtreeFromItsParent(WorkingTree subtree) {
    WorkingTree movingTreeParent = subtree.getParent();
    if (movingTreeParent != null) {
      movingTreeParent.removeChild(subtree);
    }
    subtree.setParent(null);
  }

  private void moveNodeAfter(@Nullable WorkingTree movingTree, @Nullable WorkingTree targetTree) {
    if (movingTree == null || targetTree == null) {
      return;
    }

    if (movingTree.hasDescendant(targetTree)) {
      return; // Moving movingTree under its own descendant would create a loop.
    }
    movingTree = getParentsThatAreMovedBeforeOrSameNode(movingTree);
    if (movingTree.hasDescendant(targetTree)) {
      return; // Moving movingTree under its own descendant would create a loop.
    }
    detachSubtreeFromItsParent(movingTree);
    targetTree.addChild(movingTree);
    movingTree.setParent(targetTree);
  }
}
