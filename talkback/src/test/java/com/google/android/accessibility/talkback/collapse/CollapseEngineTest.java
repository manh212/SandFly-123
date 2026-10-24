/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.collapse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.collapse.CollapseScript.Field;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.FacebookCommentTree;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.YouTubeTree;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CollapseEngineTest {
  private static final Filter<AccessibilityNodeInfoCompat> CLICKABLE_FOCUSABLE =
      new Filter<AccessibilityNodeInfoCompat>() {
        @Override
        public boolean accept(AccessibilityNodeInfoCompat node) {
          return node.isClickable() && node.isFocusable();
        }
      };

  /** A minimal composite script whose double-tap redirects to the body field. */
  private static final class FakeCompositeScript extends CollapseScript {
    FakeCompositeScript() {
      super("com.example.fake", Pattern.COMPOSITE_ROOT, /* maxActions= */ 4);
    }

    @Override
    public Activation activation() {
      return Activation.DESCENDANT_FIELD;
    }

    @Override
    public String activationFieldId() {
      return "body";
    }

    @Override
    public String selectionFieldId() {
      return "body";
    }

    @Override
    protected List<Field> resolveFields(ItemScan scan) {
      AccessibilityNodeInfoCompat body = null;
      int longest = 0;
      for (AccessibilityNodeInfoCompat node : scan.clickables()) {
        CharSequence text = node.getText();
        if (text != null && text.length() > longest) {
          longest = text.length();
          body = node;
        }
      }
      return body == null ? null : List.of(new Field("body", body, body.getText()));
    }
  }

  private Context context;
  private SharedPreferences prefs;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
  }

  private void setCollapseEnabled(boolean enabled) {
    prefs
        .edit()
        .putBoolean(context.getString(R.string.pref_collapse_list_item_actions_key), enabled)
        .commit();
  }

  @Test
  public void switchOff_filterUnchangedAndNoRepresentative() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);

    assertSame(
        CLICKABLE_FOCUSABLE, CollapseEngine.composeNodeFilter(context, CLICKABLE_FOCUSABLE));
    assertNull(CollapseEngine.matchRepresentative(context, tree.primary));
    assertNull(CollapseEngine.composedLabel(context, tree.primary));
    assertSame(tree.channel, CollapseEngine.touchTarget(context, tree.channel));
  }

  @Test
  public void switchOn_filterKeepsOnlyRepresentativesInsideCollapsedItems() {
    setCollapseEnabled(true);
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree first = CollapseTestTrees.youTubeItem(list, 0);
    YouTubeTree second = CollapseTestTrees.youTubeItem(list, 700);
    AccessibilityNodeInfoCompat toolbarButton =
        CollapseTestTrees.node(null, "android.widget.Button");
    toolbarButton.setClickable(true);
    toolbarButton.setFocusable(true);
    toolbarButton.setPackageName(CollapseTestTrees.YOUTUBE_PACKAGE);

    Filter<AccessibilityNodeInfoCompat> filter =
        CollapseEngine.composeNodeFilter(context, CLICKABLE_FOCUSABLE);

    assertTrue(filter.accept(first.primary));
    assertTrue(filter.accept(second.primary));
    assertFalse(filter.accept(first.channel));
    assertFalse(filter.accept(first.menu));
    assertFalse(filter.accept(first.item));
    assertTrue("nodes outside any item keep the base filter", filter.accept(toolbarButton));
  }

  @Test
  public void packageGate_structureAloneIsNotEnough() {
    setCollapseEnabled(true);
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list("com.example.unknown");
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);

    assertNull(
        CollapseEngine.matchContaining(tree.channel, CollapseScripts.all()));
    Filter<AccessibilityNodeInfoCompat> filter =
        CollapseEngine.composeNodeFilter(context, CLICKABLE_FOCUSABLE);
    assertTrue(filter.accept(tree.channel));
  }

  @Test
  public void switchOn_touchTargetIsTheRepresentative() {
    setCollapseEnabled(true);
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);

    assertEquals(tree.root, CollapseEngine.touchTarget(context, tree.like));
    assertEquals(tree.root, CollapseEngine.touchTarget(context, tree.root));
  }

  @Test
  public void compositeScript_activationAndSelectionRedirectToTheBodyField() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list("com.example.fake");
    AccessibilityNodeInfoCompat root = CollapseTestTrees.item(list);
    root.setClickable(true);
    root.setFocusable(true);
    CollapseTestTrees.bounds(root, 0, 0, 1080, 400);
    AccessibilityNodeInfoCompat shortButton =
        CollapseTestTrees.node(root, "android.widget.Button");
    shortButton.setClickable(true);
    shortButton.setFocusable(true);
    shortButton.setText("Hi");
    AccessibilityNodeInfoCompat body = CollapseTestTrees.node(root, "android.widget.Button");
    body.setClickable(true);
    body.setFocusable(true);
    body.setText("A much longer body text");
    CollapseTestTrees.applyPackage(root, "com.example.fake");

    CollapsedItem item =
        CollapseEngine.match(new FakeCompositeScript(), root);

    assertEquals(body, item.activationTarget());
    assertEquals(body, item.selectionTarget());
    assertEquals("A much longer body text", item.composedLabel());
  }
}
