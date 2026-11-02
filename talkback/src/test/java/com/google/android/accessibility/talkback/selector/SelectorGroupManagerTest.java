/*
 * Copyright 2026 SandFly contributors
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
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.google.android.accessibility.talkback.selector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.FormFactorUtils;
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
public class SelectorGroupManagerTest {
  private Context context;
  private SharedPreferences prefs;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    FormFactorUtils.initialize(context);
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
  }

  @Test
  public void saveGroupsRejectsAnItemAssignedToMoreThanOneGroup() {
    assertFalse(
        SelectorGroupManager.saveGroups(
            context,
            List.of(
                new SelectorGroupManager.Group(
                    "first", "First", true, List.of("one", "shared")),
                new SelectorGroupManager.Group(
                    "second", "Second", true, List.of("shared", "two")))));
  }

  @Test
  public void modeTwoKeepsSelectedGroupWhenStartingFromDefaultNavigation() {
    assertTrue(SelectorGroupManager.isSupported());
    SelectorGroupManager.ensureInitialized(context);
    String defaultNavigation = context.getString(
        SelectorController.Setting.GRANULARITY_DEFAULT.prefKeyResId);
    String readingGroup = SelectorGroupManager.getGroups(context).stream()
        .filter(group -> group.enabled && !group.items.contains(defaultNavigation))
        .findFirst().orElseThrow().id;
    prefs.edit()
        .putString(context.getString(SelectorGroupManager.CURRENT_GROUP_KEY_RES), readingGroup)
        .putString(context.getString(R.string.pref_current_selector_setting_key),
            context.getString(SelectorController.Setting.GRANULARITY_DEFAULT.prefValueResId))
        .putString(NavigationMode.PREF_KEY, "2")
        .commit();

    assertFalse(SelectorGroupManager.currentGroupItems(context).contains(defaultNavigation));
    SelectorGroupManager.ensureInitialized(context);
    assertEquals(readingGroup, SelectorGroupManager.currentGroupId(context));
  }

  @Test
  public void migrationKeepsDuplicateItemsInTheUserGroup() {
    assertTrue(SelectorGroupManager.isSupported());
    String item = context.getString(R.string.pref_selector_granularity_characters_key);
    String initializedKey = context.getString(R.string.pref_selector_navigation_groups_initialized_key);
    String groupsKey = context.getString(R.string.pref_selector_navigation_groups_key);
    prefs.edit()
        .putBoolean(initializedKey, true)
        .putString(
            groupsKey,
            "[{\"id\":\"reading\",\"name\":\"Reading\",\"enabled\":true,\"items\":[\""
                + item
                + "\",\"reading_only\"]},{\"id\":\"user\",\"name\":\"Mine\",\"enabled\":true,\"items\":[\""
                + item
                + "\",\"user_only\"]}]")
        .commit();

    SelectorGroupManager.ensureInitialized(context);

    List<SelectorGroupManager.Group> groups = SelectorGroupManager.getGroups(context);
    assertTrue(
        groups.stream()
            .filter(group -> group.id.equals(SelectorGroupManager.USER_GROUP_ID))
            .findFirst()
            .orElseThrow()
            .items.contains(item));
    assertFalse(
        groups.stream()
            .filter(group -> group.id.equals("reading"))
            .findFirst()
            .orElseThrow()
            .items.contains(item));
    assertEquals(
        1L,
        groups.stream().flatMap(group -> group.items.stream()).filter(item::equals).count());
  }
}
