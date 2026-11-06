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
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.selector;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.text.TextUtils;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.gesture.GestureShortcutMapping;
import com.google.android.accessibility.talkback.preference.ItemOrder;
import com.google.android.accessibility.talkback.scripting.ScriptReadingControl;
import com.google.android.accessibility.talkback.scripting.Scripts;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

public final class SelectorGroupManager {
  public static final String USER_GROUP_ID = "user";
  public static final int GROUPS_KEY_RES = R.string.pref_selector_navigation_groups_key;
  public static final int CURRENT_GROUP_KEY_RES = R.string.pref_selector_navigation_current_group_key;

  private static final int INITIALIZED_KEY_RES = R.string.pref_selector_navigation_groups_initialized_key;
  private static final int LAST_ITEMS_KEY_RES = R.string.pref_selector_navigation_last_items_key;
  private static final String GROUP_READING = "reading";
  private static final String GROUP_WEB = "web";
  private static final String GROUP_SPECIAL = "special";
  private static final String SCRIPT_ITEM_PREFIX = "script:";
  private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";

  public static final class Group {
    public final String id;
    public String name;
    public boolean enabled;
    public final ArrayList<String> items;

    public Group(String id, String name, boolean enabled, List<String> items) {
      this.id = id;
      this.name = name;
      this.enabled = enabled;
      this.items = new ArrayList<>(items);
    }

    public Group copy() {
      return new Group(id, name, enabled, items);
    }
  }

  public static final class ItemInfo {
    public final String id;
    public final CharSequence title;
    public final CharSequence summary;

    ItemInfo(String id, CharSequence title, CharSequence summary) {
      this.id = id;
      this.title = title;
      this.summary = summary;
    }
  }

  private SelectorGroupManager() {}

  public static boolean isSupported() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        && FeatureSupport.isMultiFingerGestureSupported()
        && !FormFactorUtils.isAndroidWear()
        && !FormFactorUtils.isAndroidXr();
  }

  public static synchronized void ensureInitialized(Context context) {
    if (!isSupported()) {
      return;
    }
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    if (!prefs.getBoolean(context.getString(INITIALIZED_KEY_RES), false)) {
      List<Group> groups = defaultGroups(context, prefs);
      String currentItem = selectedItemId(context, prefs);
      String currentGroup = groupContaining(groups, currentItem);
      if (currentGroup == null) {
        currentGroup = firstEnabledId(groups);
      }
      prefs.edit()
          .putString(context.getString(GROUPS_KEY_RES), serialize(groups))
          .putString(context.getString(CURRENT_GROUP_KEY_RES), currentGroup)
          .putBoolean(context.getString(INITIALIZED_KEY_RES), true)
          .apply();
      if (!TextUtils.isEmpty(currentItem)) {
        rememberItem(context, currentGroup, currentItem);
      }
      resetThreeFingerGestures(context, prefs);
    } else {
      List<Group> groups = getGroups(context);
      if (groups.isEmpty()) {
        groups = defaultGroups(context, prefs);
        prefs.edit().putString(context.getString(GROUPS_KEY_RES), serialize(groups)).apply();
      }
      appendNewScriptControls(context, prefs);
      appendGestureProfileControl(context, prefs);
      groups = getGroups(context);
      String currentGroup = prefs.getString(context.getString(CURRENT_GROUP_KEY_RES), "");
      Group selectedGroup = findGroup(groups, currentGroup);
      String selectedItem = selectedItemId(context, prefs);
      if (selectedGroup == null
          || !selectedGroup.enabled
          || (!selectedGroup.items.contains(selectedItem)
              && !(NavigationMode.isModeTwo(context)
                  && TextUtils.equals(
                      selectedItem,
                      itemId(context, SelectorController.Setting.GRANULARITY_DEFAULT))))) {
        currentGroup = null;
        for (Group group : groups) {
          if (group.enabled && group.items.contains(selectedItem)) {
            currentGroup = group.id;
            break;
          }
        }
        if (currentGroup == null) {
          currentGroup = firstEnabledId(groups);
        }
        prefs.edit().putString(context.getString(CURRENT_GROUP_KEY_RES), currentGroup).apply();
      }
      Group activeGroup = findGroup(groups, currentGroup);
      if (activeGroup != null && activeGroup.items.contains(selectedItem)) {
        rememberItem(context, currentGroup, selectedItem);
      }
    }
  }

  public static List<Group> getGroups(Context context) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    String stored = prefs.getString(context.getString(GROUPS_KEY_RES), null);
    if (TextUtils.isEmpty(stored)) {
      return Collections.emptyList();
    }
    try {
      JSONArray array = new JSONArray(stored);
      ArrayList<Group> groups = new ArrayList<>();
      for (int i = 0; i < array.length(); i++) {
        JSONObject object = array.optJSONObject(i);
        if (object == null) {
          continue;
        }
        String id = object.optString("id", "");
        String name = object.optString("name", "").trim();
        JSONArray itemArray = object.optJSONArray("items");
        ArrayList<String> items = new ArrayList<>();
        if (itemArray != null) {
          for (int itemIndex = 0; itemIndex < itemArray.length(); itemIndex++) {
            String item = itemArray.optString(itemIndex, "");
            if (!TextUtils.isEmpty(item) && !items.contains(item)) {
              items.add(item);
            }
          }
        }
        if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(name) && !items.isEmpty()) {
          groups.add(new Group(id, name, object.optBoolean("enabled", true), items));
        }
      }
      return groups;
    } catch (JSONException e) {
      return Collections.emptyList();
    }
  }

  public static boolean saveGroups(Context context, List<Group> groups) {
    if (groups == null || groups.isEmpty()) {
      return false;
    }
    ArrayList<Group> valid = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    int enabledCount = 0;
    for (Group group : groups) {
      String name = group.name == null ? "" : group.name.trim();
      LinkedHashSet<String> distinctItems = new LinkedHashSet<>(group.items);
      if (TextUtils.isEmpty(group.id) || TextUtils.isEmpty(name) || distinctItems.isEmpty()) {
        return false;
      }
      if (!ids.add(group.id)) {
        return false;
      }
      if (group.enabled) {
        enabledCount++;
      }
      valid.add(new Group(group.id, name, group.enabled, new ArrayList<>(distinctItems)));
    }
    if (enabledCount == 0) {
      return false;
    }
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    String currentGroupId = prefs.getString(context.getString(CURRENT_GROUP_KEY_RES), "");
    if (findGroup(valid, currentGroupId) == null || !findGroup(valid, currentGroupId).enabled) {
      currentGroupId = firstEnabledId(valid);
    }
    prefs.edit()
        .putString(context.getString(GROUPS_KEY_RES), serialize(valid))
        .putString(context.getString(CURRENT_GROUP_KEY_RES), currentGroupId)
        .apply();
    return true;
  }

  public static String currentGroupId(Context context) {
    return SharedPreferencesUtils.getSharedPreferences(context)
        .getString(context.getString(CURRENT_GROUP_KEY_RES), "");
  }

  public static Group currentGroup(Context context) {
    return findGroup(getGroups(context), currentGroupId(context));
  }

  public static String itemId(Context context, SelectorController.Setting setting) {
    return context.getString(setting.prefKeyResId);
  }

  public static SelectorController.Setting settingForItem(Context context, String itemId) {
    for (SelectorController.Setting setting : SelectorController.SELECTOR_SETTINGS) {
      if (setting != SelectorController.Setting.VERBOSITY
          && TextUtils.equals(itemId(context, setting), itemId)) {
        return setting;
      }
    }
    return null;
  }

  public static List<String> currentGroupItems(Context context) {
    Group group = currentGroup(context);
    return group == null ? Collections.emptyList() : new ArrayList<>(group.items);
  }

  public static boolean currentGroupContains(Context context, String itemId) {
    Group group = currentGroup(context);
    return group != null && group.items.contains(itemId);
  }

  public static String rememberedItem(Context context, String groupId) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    String stored = prefs.getString(context.getString(LAST_ITEMS_KEY_RES), "{}");
    try {
      return new JSONObject(stored).optString(groupId, "");
    } catch (JSONException e) {
      return "";
    }
  }

  public static void rememberItem(Context context, String groupId, String itemId) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    JSONObject object;
    try {
      object = new JSONObject(prefs.getString(context.getString(LAST_ITEMS_KEY_RES), "{}"));
    } catch (JSONException e) {
      object = new JSONObject();
    }
    try {
      object.put(groupId, itemId);
      prefs.edit().putString(context.getString(LAST_ITEMS_KEY_RES), object.toString()).apply();
    } catch (JSONException ignored) {
    }
  }

  public static List<ItemInfo> availableItems(Context context) {
    Map<String, ItemInfo> catalog = readPreferenceCatalog(context);
    ArrayList<ItemInfo> items = new ArrayList<>();
    for (SelectorController.Setting setting : SelectorController.SELECTOR_SETTINGS) {
      if (setting == SelectorController.Setting.VERBOSITY) {
        continue;
      }
      String id = itemId(context, setting);
      ItemInfo catalogItem = catalog.get(id);
      CharSequence title = catalogItem == null ? settingFallbackTitle(context, setting) : catalogItem.title;
      CharSequence summary = catalogItem == null ? null : catalogItem.summary;
      items.add(new ItemInfo(id, title, summary));
    }
    for (ScriptReadingControl control : Scripts.readingControls()) {
      items.add(new ItemInfo(SCRIPT_ITEM_PREFIX + control.getKey(), control.getTitle(), null));
    }
    return items;
  }

  public static boolean isScriptItem(String itemId) {
    return itemId != null && itemId.startsWith(SCRIPT_ITEM_PREFIX);
  }

  public static String scriptKey(String itemId) {
    return isScriptItem(itemId) ? itemId.substring(SCRIPT_ITEM_PREFIX.length()) : "";
  }

  public static String groupName(Context context, String groupId) {
    Group group = findGroup(getGroups(context), groupId);
    return group == null ? "" : group.name;
  }

  public static String enabledGroupContaining(Context context, String itemId) {
    String currentId = currentGroupId(context);
    for (Group group : getGroups(context)) {
      if (group.enabled && group.items.contains(itemId) && group.id.equals(currentId)) {
        return group.id;
      }
    }
    for (Group group : getGroups(context)) {
      if (group.enabled && group.items.contains(itemId)) {
        return group.id;
      }
    }
    return null;
  }

  public static void setCurrentGroup(Context context, String groupId) {
    SharedPreferencesUtils.getSharedPreferences(context)
        .edit()
        .putString(context.getString(CURRENT_GROUP_KEY_RES), groupId)
        .apply();
  }

  public static void resetGroups(Context context) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    List<Group> groups = defaultGroups(context, prefs);
    String currentItem = selectedItemId(context, prefs);
    String currentGroup = groupContaining(groups, currentItem);
    if (currentGroup == null) {
      currentGroup = firstEnabledId(groups);
    }
    prefs.edit()
        .putString(context.getString(GROUPS_KEY_RES), serialize(groups))
        .putString(context.getString(CURRENT_GROUP_KEY_RES), currentGroup)
        .remove(context.getString(LAST_ITEMS_KEY_RES))
        .apply();
    if (!TextUtils.isEmpty(currentItem)) {
      rememberItem(context, currentGroup, currentItem);
    }
  }

  public static boolean canDisableGroup(Context context, String groupId) {
    int enabled = 0;
    for (Group group : getGroups(context)) {
      if (group.enabled) {
        enabled++;
      }
    }
    Group group = findGroup(getGroups(context), groupId);
    return group != null && (enabled > 1 || !group.enabled);
  }

  public static boolean removeGroup(Context context, String groupId) {
    ArrayList<Group> groups = mutableGroups(context);
    if (groups.size() <= 1) {
      return false;
    }
    groups.removeIf(group -> group.id.equals(groupId));
    return saveGroups(context, groups);
  }

  public static boolean moveGroup(Context context, String groupId, int offset) {
    ArrayList<Group> groups = mutableGroups(context);
    int from = indexOfGroup(groups, groupId);
    int to = from + offset;
    if (from < 0 || to < 0 || to >= groups.size()) {
      return false;
    }
    Collections.swap(groups, from, to);
    return saveGroups(context, groups);
  }

  public static boolean addGroup(Context context, String name, List<String> itemIds) {
    String normalizedName = name == null ? "" : name.trim();
    if (TextUtils.isEmpty(normalizedName) || itemIds == null || itemIds.isEmpty()) {
      return false;
    }
    ArrayList<Group> groups = mutableGroups(context);
    Set<String> selected = new LinkedHashSet<>(itemIds);
    groups.add(new Group(UUID.randomUUID().toString(), normalizedName, true, new ArrayList<>(selected)));
    return saveGroups(context, groups);
  }

  public static boolean addItem(Context context, String groupId, String itemId) {
    ArrayList<Group> groups = mutableGroups(context);
    Group target = findGroup(groups, groupId);
    if (target == null || target.items.contains(itemId)) {
      return false;
    }
    target.items.add(itemId);
    return saveGroups(context, groups);
  }

  public static boolean removeItem(Context context, String groupId, String itemId) {
    ArrayList<Group> groups = mutableGroups(context);
    Group group = findGroup(groups, groupId);
    if (group == null || !group.items.contains(itemId) || group.items.size() <= 1) {
      return false;
    }
    group.items.remove(itemId);
    return saveGroups(context, groups);
  }

  public static boolean moveItem(Context context, String groupId, String itemId, int offset) {
    ArrayList<Group> groups = mutableGroups(context);
    Group group = findGroup(groups, groupId);
    if (group == null) {
      return false;
    }
    int from = group.items.indexOf(itemId);
    int to = from + offset;
    if (from < 0 || to < 0 || to >= group.items.size()) {
      return false;
    }
    Collections.swap(group.items, from, to);
    return saveGroups(context, groups);
  }

  private static List<Group> defaultGroups(Context context, SharedPreferences prefs) {
    ArrayList<String> reading = new ArrayList<>();
    ArrayList<String> web = new ArrayList<>();
    ArrayList<String> special = new ArrayList<>();
    ArrayList<String> userItems = new ArrayList<>();
    for (SelectorController.Setting setting : orderedSettings(context, prefs)) {
      if (setting == SelectorController.Setting.VERBOSITY) {
        continue;
      }
      String id = itemId(context, setting);
      if (isWebSetting(setting)) {
        web.add(id);
      } else if (isSpecialSetting(setting)) {
        special.add(id);
      } else {
        reading.add(id);
      }
      if (prefs.getBoolean(id, context.getResources().getBoolean(setting.defaultValueResId))) {
        userItems.add(id);
      }
    }
    for (ScriptReadingControl control : Scripts.readingControls()) {
      userItems.add(SCRIPT_ITEM_PREFIX + control.getKey());
    }
    Set<String> userItemsSet = new HashSet<>(userItems);
    reading.removeIf(userItemsSet::contains);
    web.removeIf(userItemsSet::contains);
    special.removeIf(userItemsSet::contains);
    ArrayList<Group> groups = new ArrayList<>();
    groups.add(new Group(GROUP_READING, context.getString(R.string.selector_group_reading), true, reading));
    groups.add(new Group(GROUP_WEB, context.getString(R.string.selector_group_web), true, web));
    groups.add(new Group(GROUP_SPECIAL, context.getString(R.string.selector_group_special), true, special));
    if (!userItems.isEmpty()) {
      groups.add(
          new Group(USER_GROUP_ID, context.getString(R.string.selector_group_user), true, userItems));
    }
    groups.removeIf(group -> group.items.isEmpty());
    return groups;
  }

  private static List<SelectorController.Setting> orderedSettings(
      Context context, SharedPreferences prefs) {
    List<String> orderedKeys =
        ItemOrder.ordered(
            prefs,
            ItemOrder.READING_CONTROLS_KEY,
            SelectorController.defaultOrderKeys(context));
    ArrayList<SelectorController.Setting> ordered = new ArrayList<>();
    for (String key : orderedKeys) {
      SelectorController.Setting setting = settingForItem(context, key);
      if (setting != null && !ordered.contains(setting)) {
        ordered.add(setting);
      }
    }
    return ordered;
  }

  private static boolean isWebSetting(SelectorController.Setting setting) {
    return switch (setting) {
      case GRANULARITY_WEB_LANDMARKS,
          GRANULARITY_WEB_BUTTONS,
          GRANULARITY_WEB_CHECKBOXES,
          GRANULARITY_WEB_EDITFIELDS,
          GRANULARITY_WEB_FOCUSABLES,
          GRANULARITY_WEB_H1,
          GRANULARITY_WEB_H2,
          GRANULARITY_WEB_H3,
          GRANULARITY_WEB_H4,
          GRANULARITY_WEB_H5,
          GRANULARITY_WEB_H6,
          GRANULARITY_WEB_GRAPHICS,
          GRANULARITY_WEB_LISTS,
          GRANULARITY_WEB_LISTITEMS,
          GRANULARITY_WEB_TABLES,
          GRANULARITY_WEB_COMBOBOXES,
          GRANULARITY_WEB_VISITED_LINKS,
          GRANULARITY_WEB_UNVISITED_LINKS,
          GRANULARITY_WEB_RADIOS -> true;
      default -> false;
    };
  }

  private static boolean isSpecialSetting(SelectorController.Setting setting) {
    return switch (setting) {
      case HIDE_SCREEN,
          AUDIO_FOCUS,
          SCROLLING_SEQUENTIAL,
          CHANGE_ACCESSIBILITY_VOLUME,
          SOUND_MODE,
          AUTO_TRANSLATION,
          CHANGE_BRIGHTNESS,
          CHANGE_LIFT_TO_ACTIVATE,
          WRAP_AROUND,
          LIVE_RECOGNITION,
          ADJUSTABLE_WIDGET,
          CONTROL_TELLING_TIME -> true;
      default -> false;
    };
  }

  private static String selectedItemId(Context context, SharedPreferences prefs) {
    String value = prefs.getString(
        context.getString(R.string.pref_current_selector_setting_key),
        context.getString(R.string.pref_selector_setting_default));
    SelectorController.Setting setting = SelectorController.Setting.getSettingFromPrefValue(context, value);
    if (setting == SelectorController.Setting.VERBOSITY) {
      setting = SelectorController.Setting.GRANULARITY_CHARACTERS;
    }
    return setting == null ? "" : itemId(context, setting);
  }

  private static String groupContaining(List<Group> groups, String itemId) {
    for (Group group : groups) {
      if (group.items.contains(itemId)) {
        return group.id;
      }
    }
    return null;
  }

  private static void resetThreeFingerGestures(Context context, SharedPreferences prefs) {
    int[] keys = {
      R.string.pref_shortcut_3finger_swipe_up_key,
      R.string.pref_shortcut_3finger_swipe_down_key,
      R.string.pref_shortcut_3finger_swipe_left_key,
      R.string.pref_shortcut_3finger_swipe_right_key
    };
    int[] defaults = {
      R.string.shortcut_value_select_previous_setting,
      R.string.shortcut_value_select_next_setting,
      R.string.shortcut_value_select_previous_group,
      R.string.shortcut_value_select_next_group
    };
    SharedPreferences.Editor editor = prefs.edit();
    for (int gestureSet = 0; gestureSet < 2; gestureSet++) {
      for (int index = 0; index < keys.length; index++) {
        String key = context.getString(keys[index]);
        editor.putString(
            GestureShortcutMapping.getPrefKeyWithGestureSet(key, gestureSet),
            context.getString(defaults[index]));
      }
    }
    editor.apply();
  }

  private static void appendSmartContextControl(Context context) {
    ArrayList<Group> groups = mutableGroups(context);
    String id = itemId(context, SelectorController.Setting.SMART_CONTEXT_GESTURES);
    if (containsItem(groups, id)) return;
    Group target = findGroup(groups, GROUP_READING);
    if (target == null) {
      target = new Group(GROUP_READING, context.getString(R.string.selector_group_reading), true, new ArrayList<>());
      groups.add(target);
    }
    target.items.add(id);
    saveGroups(context, groups);
  }

  public static void appendGestureProfileControl(Context context, SharedPreferences prefs) {
    if (!isSupported() || !com.google.android.accessibility.talkback.gesture.GestureProfiles.hasProfiles()
        || prefs.getBoolean("pref_gesture_profile_control_added", false)) return;
    prefs.edit().putBoolean("pref_gesture_profile_control_added", true).apply();
    ArrayList<Group> groups = mutableGroups(context);
    String id = itemId(context, SelectorController.Setting.GESTURE_PROFILE);
    if (containsItem(groups, id)) return;
    Group target = findGroup(groups, GROUP_SPECIAL);
    if (target == null) {
      target = new Group(GROUP_SPECIAL, context.getString(R.string.selector_group_special), true, new ArrayList<>());
      groups.add(target);
    }
    target.items.add(id);
    saveGroups(context, groups);
  }

  private static void appendNewScriptControls(Context context, SharedPreferences prefs) {
    ArrayList<Group> groups = mutableGroups(context);
    Group userGroup = findGroup(groups, USER_GROUP_ID);
    boolean changed = false;
    for (ScriptReadingControl control : Scripts.readingControls()) {
      String id = SCRIPT_ITEM_PREFIX + control.getKey();
      if (!containsItem(groups, id)) {
        if (userGroup == null) {
          userGroup =
              new Group(
                  USER_GROUP_ID,
                  context.getString(R.string.selector_group_user),
                  true,
                  new ArrayList<>());
          groups.add(userGroup);
        }
        userGroup.items.add(id);
        changed = true;
      }
    }
    if (changed) {
      saveGroups(context, groups);
    }
  }

  private static Map<String, ItemInfo> readPreferenceCatalog(Context context) {
    HashMap<String, ItemInfo> catalog = new HashMap<>();
    XmlResourceParser parser = context.getResources().getXml(R.xml.selector_menu_preferences);
    try {
      int eventType = parser.getEventType();
      while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG) {
          int keyRes = parser.getAttributeResourceValue(ANDROID_NAMESPACE, "key", 0);
          int titleRes = parser.getAttributeResourceValue(ANDROID_NAMESPACE, "title", 0);
          int summaryRes = parser.getAttributeResourceValue(ANDROID_NAMESPACE, "summary", 0);
          if (keyRes != 0 && titleRes != 0) {
            String key = context.getString(keyRes);
            CharSequence title = context.getText(titleRes);
            CharSequence summary = summaryRes == 0 ? null : context.getText(summaryRes);
            catalog.put(key, new ItemInfo(key, title, summary));
          }
        }
        eventType = parser.next();
      }
    } catch (Exception ignored) {
    } finally {
      parser.close();
    }
    return catalog;
  }

  private static CharSequence settingFallbackTitle(Context context, SelectorController.Setting setting) {
    return switch (setting) {
      case ADJUSTABLE_WIDGET -> context.getString(R.string.selector_group_adjustable_widget);
      case CONTROL_TELLING_TIME -> context.getString(R.string.selector_group_telling_time);
      default -> context.getString(setting.prefValueResId);
    };
  }

  private static ArrayList<Group> mutableGroups(Context context) {
    ArrayList<Group> groups = new ArrayList<>();
    for (Group group : getGroups(context)) {
      groups.add(group.copy());
    }
    return groups;
  }

  private static boolean containsItem(List<Group> groups, String itemId) {
    for (Group group : groups) {
      if (group.items.contains(itemId)) {
        return true;
      }
    }
    return false;
  }

  private static String firstEnabledId(List<Group> groups) {
    for (Group group : groups) {
      if (group.enabled) {
        return group.id;
      }
    }
    return groups.get(0).id;
  }

  private static Group findGroup(List<Group> groups, String id) {
    for (Group group : groups) {
      if (group.id.equals(id)) {
        return group;
      }
    }
    return null;
  }

  private static int indexOfGroup(List<Group> groups, String id) {
    for (int index = 0; index < groups.size(); index++) {
      if (groups.get(index).id.equals(id)) {
        return index;
      }
    }
    return -1;
  }

  private static String serialize(List<Group> groups) {
    JSONArray array = new JSONArray();
    for (Group group : groups) {
      JSONObject object = new JSONObject();
      try {
        object.put("id", group.id);
        object.put("name", group.name.trim());
        object.put("enabled", group.enabled);
        object.put("items", new JSONArray(group.items));
        array.put(object);
      } catch (JSONException ignored) {
      }
    }
    return array.toString();
  }
}
