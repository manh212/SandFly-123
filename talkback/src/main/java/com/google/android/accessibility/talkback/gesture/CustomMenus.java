package com.google.android.accessibility.talkback.gesture;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class CustomMenus {
  public static final String PREF_KEY = "pref_custom_menus";
  private static final String ACTION_PREFIX = "custom_menu:";

  public static final class Item {
    public final String label;
    public final String value;

    public Item(String label, String value) {
      this.label = label;
      this.value = value;
    }
  }

  public static final class Menu {
    public final String id;
    public final String name;
    public final List<Item> items;

    Menu(String id, String name, List<Item> items) {
      this.id = id;
      this.name = name;
      this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }
  }

  private CustomMenus() {}

  public static String actionFor(String id) {
    return ACTION_PREFIX + id;
  }

  public static boolean isMenuAction(String value) {
    return value != null && value.startsWith(ACTION_PREFIX);
  }

  public static List<Menu> menus(Context context) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    ArrayList<Menu> result = new ArrayList<>();
    try {
      JSONArray saved = new JSONArray(prefs.getString(PREF_KEY, "[]"));
      for (int i = 0; i < saved.length(); i++) {
        JSONObject entry = saved.optJSONObject(i);
        if (entry == null) continue;
        String id = entry.optString("id", "");
        String name = entry.optString("name", "");
        if (id.isEmpty() || name.isEmpty()) continue;
        JSONArray values = entry.optJSONArray("items");
        ArrayList<Item> items = new ArrayList<>();
        if (values != null) {
          for (int j = 0; j < values.length(); j++) {
            JSONObject item = values.optJSONObject(j);
            if (item != null) {
              String value = item.optString("value", "");
              String label = item.optString("label", "");
              if (!value.isEmpty() && !label.isEmpty()) items.add(new Item(label, value));
            }
          }
        }
        result.add(new Menu(id, name, items));
      }
    } catch (JSONException ignored) {
      return List.of();
    }
    return result;
  }

  public static @Nullable Menu find(Context context, String id) {
    for (Menu menu : menus(context)) {
      if (menu.id.equals(id)) return menu;
    }
    return null;
  }

  public static @Nullable String label(Context context, String action) {
    if (!isMenuAction(action)) return null;
    Menu menu = find(context, action.substring(ACTION_PREFIX.length()));
    return menu == null ? null : menu.name;
  }

  public static boolean nameTaken(Context context, String id, String name) {
    for (Menu menu : menus(context)) {
      if (!menu.id.equals(id) && menu.name.equalsIgnoreCase(name.trim())) return true;
    }
    return false;
  }

  public static @Nullable String add(Context context, String name) {
    String trimmed = name.trim();
    if (trimmed.isEmpty() || nameTaken(context, "", trimmed)) return null;
    List<Menu> saved = menus(context);
    String id = UUID.randomUUID().toString();
    saved.add(new Menu(id, trimmed, List.of()));
    write(context, saved);
    return id;
  }

  public static boolean rename(Context context, String id, String name) {
    String trimmed = name.trim();
    if (trimmed.isEmpty() || nameTaken(context, id, trimmed)) return false;
    List<Menu> saved = menus(context);
    for (int i = 0; i < saved.size(); i++) {
      Menu menu = saved.get(i);
      if (menu.id.equals(id)) {
        saved.set(i, new Menu(id, trimmed, menu.items));
        write(context, saved);
        return true;
      }
    }
    return false;
  }

  public static void delete(Context context, String id) {
    List<Menu> saved = menus(context);
    saved.removeIf(menu -> menu.id.equals(id));
    write(context, saved);
  }

  public static boolean addItem(Context context, String id, String label, String value) {
    if (value.isEmpty() || isMenuAction(value)) return false;
    List<Menu> saved = menus(context);
    for (int i = 0; i < saved.size(); i++) {
      Menu menu = saved.get(i);
      if (menu.id.equals(id)) {
        List<Item> items = new ArrayList<>(menu.items);
        items.add(new Item(label, value));
        saved.set(i, new Menu(id, menu.name, items));
        write(context, saved);
        return true;
      }
    }
    return false;
  }

  public static void removeItem(Context context, String id, int index) {
    changeItem(context, id, index, 0);
  }

  public static void moveItem(Context context, String id, int index, int distance) {
    changeItem(context, id, index, distance);
  }

  private static void changeItem(Context context, String id, int index, int distance) {
    List<Menu> saved = menus(context);
    for (int i = 0; i < saved.size(); i++) {
      Menu menu = saved.get(i);
      if (!menu.id.equals(id)) continue;
      ArrayList<Item> items = new ArrayList<>(menu.items);
      if (index < 0 || index >= items.size()) return;
      if (distance == 0) {
        items.remove(index);
      } else {
        int next = index + distance;
        if (next < 0 || next >= items.size()) return;
        Collections.swap(items, index, next);
      }
      saved.set(i, new Menu(id, menu.name, items));
      write(context, saved);
      return;
    }
  }

  private static void write(Context context, List<Menu> menus) {
    JSONArray saved = new JSONArray();
    for (Menu menu : menus) {
      try {
        JSONObject entry = new JSONObject();
        entry.put("id", menu.id);
        entry.put("name", menu.name);
        JSONArray items = new JSONArray();
        for (Item item : menu.items) {
          JSONObject value = new JSONObject();
          value.put("label", item.label);
          value.put("value", item.value);
          items.put(value);
        }
        entry.put("items", items);
        saved.put(entry);
      } catch (JSONException ignored) {
        return;
      }
    }
    SharedPreferencesUtils.getSharedPreferences(context)
        .edit().putString(PREF_KEY, saved.toString()).apply();
  }
}
