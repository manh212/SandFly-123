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
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.android.accessibility.utils.traversal;

import static org.junit.Assert.assertEquals;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * The lean builder is only worth having if its reading order is the standard builder's order,
 * exactly, on every tree either of them will ever be handed. Each case below describes a tree
 * once, materializes it twice, builds with both builders, and compares everything a swipe can
 * observe: the dumped order, a full walk forwards and a full walk backwards through the focus
 * search, the initial focus, and which nodes the order contains at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LeanTraversalOrderBuilderTest {

  /** One node of a described tree. The same spec is materialized twice, once per builder. */
  private static final class Spec {
    final String id;
    final Rect bounds;
    boolean visible = true;
    boolean focusable = true;
    boolean clickable = true;
    boolean webActions = false;
    String className = "android.widget.Button";
    String beforeId;
    String afterId;
    final List<Spec> children = new ArrayList<>();

    Spec(String id, Rect bounds) {
      this.id = id;
      this.bounds = bounds;
    }

    Spec add(Spec child) {
      children.add(child);
      return this;
    }

    Spec container() {
      focusable = false;
      clickable = false;
      className = "android.widget.FrameLayout";
      return this;
    }
  }

  /** A materialized tree: its root info and every info by spec id. */
  private static final class Materialized {
    final AccessibilityNodeInfo root;
    final Map<String, AccessibilityNodeInfo> byId;

    Materialized(AccessibilityNodeInfo root, Map<String, AccessibilityNodeInfo> byId) {
      this.root = root;
      this.byId = byId;
    }
  }

  private static Materialized materialize(Spec spec) {
    Map<String, AccessibilityNodeInfo> byId = new LinkedHashMap<>();
    AccessibilityNodeInfo root = buildNode(spec, null, byId);
    wireTraversalLinks(spec, byId);
    return new Materialized(root, byId);
  }

  private static AccessibilityNodeInfo buildNode(
      Spec spec, AccessibilityNodeInfo parent, Map<String, AccessibilityNodeInfo> byId) {
    AccessibilityNodeInfo existing = byId.get(spec.id);
    if (existing != null) {
      // The same node appearing under two parents: later occurrences are cut by both builders.
      if (parent != null) {
        Shadows.shadowOf(parent).addChild(existing);
      }
      return existing;
    }
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setClassName(spec.className);
    node.setText(spec.id);
    node.setVisibleToUser(spec.visible);
    node.setFocusable(spec.focusable);
    node.setClickable(spec.clickable);
    node.setEnabled(true);
    node.setBoundsInScreen(spec.bounds);
    if (spec.webActions) {
      node.addAction(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT);
      node.addAction(AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT);
    }
    byId.put(spec.id, node);
    if (parent != null) {
      Shadows.shadowOf(parent).addChild(node);
    }
    for (Spec child : spec.children) {
      buildNode(child, node, byId);
    }
    return node;
  }

  private static void wireTraversalLinks(Spec spec, Map<String, AccessibilityNodeInfo> byId) {
    AccessibilityNodeInfo node = byId.get(spec.id);
    if (spec.beforeId != null) {
      AccessibilityNodeInfo target = byId.get(spec.beforeId);
      if (target == null) {
        target = AccessibilityNodeInfo.obtain();
        target.setText(spec.beforeId);
      }
      Shadows.shadowOf(node).setTraversalBefore(target);
    }
    if (spec.afterId != null) {
      AccessibilityNodeInfo target = byId.get(spec.afterId);
      if (target == null) {
        target = AccessibilityNodeInfo.obtain();
        target.setText(spec.afterId);
      }
      Shadows.shadowOf(node).setTraversalAfter(target);
    }
    for (Spec child : spec.children) {
      wireTraversalLinks(child, byId);
    }
  }

  private static List<String> idsOf(List<AccessibilityNodeInfoCompat> nodes) {
    List<String> ids = new ArrayList<>();
    for (AccessibilityNodeInfoCompat node : nodes) {
      ids.add(String.valueOf(node.getText()));
    }
    return ids;
  }

  private static List<String> walk(
      OrderedTraversalStrategy strategy, AccessibilityNodeInfo root, int direction) {
    AccessibilityNodeInfoCompat rootCompat = AccessibilityNodeInfoCompat.wrap(root);
    List<String> ids = new ArrayList<>();
    AccessibilityNodeInfoCompat node = strategy.focusFirst(rootCompat, direction);
    int guard = 0;
    while (node != null && guard++ < 500) {
      ids.add(String.valueOf(node.getText()));
      node = strategy.findFocus(node, direction);
    }
    return ids;
  }

  /** Builds the described tree with both builders and compares everything observable. */
  private static void assertEquivalent(String caseName, Spec spec) {
    Materialized standardTree = materialize(spec);
    Materialized leanTree = materialize(spec);
    OrderedTraversalStrategy standard =
        new OrderedTraversalStrategy(AccessibilityNodeInfoCompat.wrap(standardTree.root));
    OrderedTraversalStrategy lean =
        LeanTraversalOrderBuilder.build(AccessibilityNodeInfoCompat.wrap(leanTree.root));

    assertEquals(
        caseName + ": dumped order", idsOf(standard.dumpTree()), idsOf(lean.dumpTree()));
    assertEquals(
        caseName + ": forward walk",
        walk(standard, standardTree.root, TraversalStrategy.SEARCH_FOCUS_FORWARD),
        walk(lean, leanTree.root, TraversalStrategy.SEARCH_FOCUS_FORWARD));
    assertEquals(
        caseName + ": backward walk",
        walk(standard, standardTree.root, TraversalStrategy.SEARCH_FOCUS_BACKWARD),
        walk(lean, leanTree.root, TraversalStrategy.SEARCH_FOCUS_BACKWARD));

    AccessibilityNodeInfoCompat standardInitial =
        standard.focusInitial(AccessibilityNodeInfoCompat.wrap(standardTree.root));
    AccessibilityNodeInfoCompat leanInitial =
        lean.focusInitial(AccessibilityNodeInfoCompat.wrap(leanTree.root));
    assertEquals(
        caseName + ": initial focus",
        standardInitial == null ? null : String.valueOf(standardInitial.getText()),
        leanInitial == null ? null : String.valueOf(leanInitial.getText()));

    for (String id : standardTree.byId.keySet()) {
      assertEquals(
          caseName + ": contains " + id,
          standard.containsNode(
              AccessibilityNodeInfoCompat.wrap(standardTree.byId.get(id))),
          lean.containsNode(AccessibilityNodeInfoCompat.wrap(leanTree.byId.get(id))));
    }

    // The focus verdict of every node, asked of each strategy through the speaking cache that
    // strategy hands out -- the cache a later search will be answered from. The builds record
    // into those caches lazily, so the verdicts are asked for directly here rather than read
    // back from whatever the builds happened to record.
    for (String id : standardTree.byId.keySet()) {
      boolean standardVerdict =
          AccessibilityNodeInfoUtils.shouldFocusNode(
              AccessibilityNodeInfoCompat.wrap(standardTree.byId.get(id)),
              standard.getSpeakingNodesCache());
      boolean leanVerdict =
          AccessibilityNodeInfoUtils.shouldFocusNode(
              AccessibilityNodeInfoCompat.wrap(leanTree.byId.get(id)),
              lean.getSpeakingNodesCache());
      assertEquals(caseName + ": focus verdict of " + id, standardVerdict, leanVerdict);
    }
  }

  // -------------------------------------------------------------------------------------------
  // Hand-built fixtures, shaped like the trees real apps hand the service.

  @Test
  public void chatLikeRows_withAContainerWhoseUsefulBoundsReorderIt() {
    Spec root = new Spec("root", new Rect(0, 0, 600, 1200)).container();
    // Row two is added before row one, and its own bounds claim it sits below row one while
    // its message bubble actually sits above row one's: the useful-bounds reorder must swap
    // the rows in both builders.
    Spec rowTwo = new Spec("rowTwo", new Rect(0, 500, 600, 700)).container();
    rowTwo.add(new Spec("avatarTwo", new Rect(0, 100, 100, 200)));
    rowTwo.add(new Spec("bubbleTwo", new Rect(100, 100, 600, 200)));
    Spec rowOne = new Spec("rowOne", new Rect(0, 300, 600, 500)).container();
    rowOne.add(new Spec("avatarOne", new Rect(0, 300, 100, 400)));
    rowOne.add(new Spec("bubbleOne", new Rect(100, 300, 600, 400)));
    Spec composer = new Spec("composer", new Rect(0, 1100, 600, 1200));
    root.add(rowTwo).add(rowOne).add(composer);

    assertEquivalent("chat rows", root);
  }

  @Test
  public void settingsLikeRows_addedOutOfOrder() {
    Spec root = new Spec("root", new Rect(0, 0, 600, 1200)).container();
    int[] scrambled = {3, 1, 4, 0, 2};
    for (int row : scrambled) {
      Spec rowSpec =
          new Spec("row" + row, new Rect(0, row * 200, 600, row * 200 + 200)).container();
      rowSpec.add(new Spec("title" + row, new Rect(0, row * 200, 400, row * 200 + 200)));
      rowSpec.add(new Spec("switch" + row, new Rect(400, row * 200, 600, row * 200 + 200)));
      root.add(rowSpec);
    }

    assertEquivalent("settings rows", root);
  }

  @Test
  public void webContainer_childrenAreNotOrdered() {
    Spec root = new Spec("root", new Rect(0, 0, 600, 1200)).container();
    Spec web = new Spec("web", new Rect(0, 0, 600, 900));
    web.webActions = true;
    web.className = "android.webkit.WebView";
    web.add(new Spec("webChildB", new Rect(300, 0, 600, 100)));
    web.add(new Spec("webChildA", new Rect(0, 0, 300, 100)));
    root.add(web).add(new Spec("below", new Rect(0, 900, 600, 1200)));

    assertEquivalent("web container", root);
  }

  @Test
  public void traversalLinks_acrossSubtrees_andLoops() {
    Spec root = new Spec("root", new Rect(0, 0, 600, 1200)).container();
    Spec left = new Spec("left", new Rect(0, 0, 300, 1200)).container();
    Spec a = new Spec("a", new Rect(0, 0, 300, 100));
    Spec b = new Spec("b", new Rect(0, 100, 300, 200));
    left.add(a).add(b);
    Spec right = new Spec("right", new Rect(300, 0, 600, 1200)).container();
    Spec c = new Spec("c", new Rect(300, 0, 600, 100));
    Spec d = new Spec("d", new Rect(300, 100, 600, 200));
    right.add(c).add(d);
    root.add(left).add(right);
    // A chain climbing across the subtrees: c before b, b before a.
    c.beforeId = "b";
    b.beforeId = "a";
    // A loop back to an ancestor, an after pointing outside the tree, and a node with both
    // links set (before wins): each must land the same way in both builders.
    d.beforeId = "root";
    a.afterId = "outside";
    d.afterId = "a";

    assertEquivalent("traversal links", root);
  }

  @Test
  public void invisibleNodes_andInvisibleContainers() {
    Spec root = new Spec("root", new Rect(0, 0, 600, 1200)).container();
    Spec hiddenBox = new Spec("hiddenBox", new Rect(0, 0, 600, 200)).container();
    hiddenBox.visible = false;
    hiddenBox.add(new Spec("hiddenChild", new Rect(0, 0, 600, 100)));
    Spec ghost = new Spec("ghost", new Rect(0, 200, 600, 300));
    ghost.visible = false;
    root.add(hiddenBox).add(ghost).add(new Spec("solid", new Rect(0, 300, 600, 400)));

    assertEquivalent("invisible nodes", root);
  }

  @Test
  public void theSameNodeUnderTwoParents() {
    Spec root = new Spec("root", new Rect(0, 0, 600, 1200)).container();
    Spec shared = new Spec("shared", new Rect(0, 100, 600, 200));
    Spec first = new Spec("first", new Rect(0, 0, 600, 300)).container();
    first.add(new Spec("firstLeaf", new Rect(0, 0, 600, 100))).add(shared);
    Spec second = new Spec("second", new Rect(0, 300, 600, 600)).container();
    second.add(shared).add(new Spec("secondLeaf", new Rect(0, 300, 600, 400)));
    root.add(first).add(second);

    assertEquivalent("shared node", root);
  }

  @Test
  public void aDeepChain_andAWideScrambledLevel() {
    Spec root = new Spec("root", new Rect(0, 0, 600, 4000)).container();
    Spec cursor = root;
    for (int i = 0; i < 40; i++) {
      Spec next = new Spec("link" + i, new Rect(0, i * 90, 600, i * 90 + 90)).container();
      cursor.add(next);
      cursor = next;
    }
    cursor.add(new Spec("deepLeaf", new Rect(0, 3600, 600, 3690)));

    Spec wide = new Spec("wide", new Rect(0, 0, 1200, 300)).container();
    for (int i = 0; i < 30; i++) {
      int position = (i * 7) % 30;
      Spec wrapper =
          new Spec("wrap" + i, new Rect(position * 40, 0, position * 40 + 40, 300)).container();
      wrapper.add(new Spec("cell" + i, new Rect(position * 40, 0, position * 40 + 40, 300)));
      wide.add(wrapper);
    }
    root.add(wide);

    assertEquivalent("deep and wide", root);
  }

  // -------------------------------------------------------------------------------------------
  // Generated trees: one seeded generator, many shapes, absolute agreement required.

  @Test
  public void generatedTrees_alwaysAgree() {
    for (int seed = 1; seed <= 60; seed++) {
      assertEquivalent("generated seed " + seed, generate(seed));
    }
  }

  private static Spec generate(int seed) {
    Random random = new Random(seed * 7919L);
    int[] nextId = {0};
    Spec root = generateNode(random, nextId, 0, new Rect(0, 0, 1200, 2400));
    root.container();
    if (root.children.isEmpty()) {
      for (int i = 0; i < 3; i++) {
        root.add(new Spec("n" + nextId[0]++, new Rect(0, i * 100, 1200, i * 100 + 100)));
      }
    }
    List<Spec> all = new ArrayList<>();
    collect(root, all);
    for (Spec spec : all) {
      double roll = random.nextDouble();
      if (roll < 0.16) {
        spec.beforeId = all.get(random.nextInt(all.size())).id;
      } else if (roll < 0.24) {
        spec.afterId = all.get(random.nextInt(all.size())).id;
      }
      if (spec.id.equals(spec.beforeId)) {
        spec.beforeId = null;
      }
      if (spec.id.equals(spec.afterId)) {
        spec.afterId = null;
      }
    }
    return root;
  }

  private static void collect(Spec spec, List<Spec> out) {
    out.add(spec);
    for (Spec child : spec.children) {
      collect(child, out);
    }
  }

  private static Spec generateNode(Random random, int[] nextId, int depth, Rect area) {
    Spec spec = new Spec("n" + nextId[0]++, area);
    if (random.nextDouble() < 0.12) {
      spec.visible = false;
    }
    if (depth >= 4 || random.nextDouble() < 0.35) {
      spec.focusable = random.nextDouble() < 0.85;
      spec.clickable = spec.focusable;
      return spec;
    }
    spec.container();
    if (random.nextDouble() < 0.06) {
      spec.webActions = true;
      spec.className = "android.webkit.WebView";
    }
    int children = 1 + random.nextInt(4);
    for (int i = 0; i < children; i++) {
      int left = area.left + random.nextInt(Math.max(1, area.width() / 2));
      int top = area.top + random.nextInt(Math.max(1, area.height() / 2));
      int right = left + 40 + random.nextInt(Math.max(41, area.width() / 2));
      int bottom = top + 40 + random.nextInt(Math.max(41, area.height() / 2));
      spec.add(generateNode(random, nextId, depth + 1, new Rect(left, top, right, bottom)));
    }
    return spec;
  }
}
