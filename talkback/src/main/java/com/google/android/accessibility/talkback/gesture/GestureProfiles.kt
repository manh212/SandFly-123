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

package com.google.android.accessibility.talkback.gesture

import android.content.Context
import android.content.SharedPreferences
import com.google.android.accessibility.talkback.R
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object GestureProfiles {

  const val KEY = "pref_gesture_profiles"

  const val ACTIVE_KEY = "pref_gesture_profile"

  private const val PREFIX = "GESTURE_PROFILE:"

  const val DEFAULT_VALUE = "GESTURE_PROFILE_DEFAULT"

  private const val ROW_PREFIX = "gesture_profile_row:"

  data class Profile(val id: String, val name: String, val gestures: Map<String, String>)

  @Volatile private var profiles: List<Profile> = emptyList()

  @Volatile private var active: Map<String, String>? = null

  @JvmStatic
  fun load(prefs: SharedPreferences) {
    val read = read(prefs)
    profiles = read
    val id = prefs.getString(ACTIVE_KEY, "").orEmpty()
    active = read.firstOrNull { it.id == id }?.gestures
  }

  @JvmStatic
  fun reset(prefs: SharedPreferences) {
    prefs.edit().putString(ACTIVE_KEY, "").apply()
    load(prefs)
  }

  @JvmStatic
  fun action(gestureKey: String?): String? {
    if (gestureKey == null) return null
    return active?.get(gestureKey)
  }

  @JvmStatic fun hasProfiles(): Boolean = profiles.isNotEmpty()

  @JvmStatic fun isActive(): Boolean = active != null

  @JvmStatic
  fun activeId(prefs: SharedPreferences): String {
    val id = prefs.getString(ACTIVE_KEY, "").orEmpty()
    return if (read(prefs).any { it.id == id }) id else ""
  }

  @JvmStatic
  fun select(context: Context, prefs: SharedPreferences, id: String): String? {
    val all = read(prefs)
    val profile = all.firstOrNull { it.id == id }
    if (id.isNotEmpty() && profile == null) return null
    prefs.edit().putString(ACTIVE_KEY, id).apply()

    profiles = all
    active = profile?.gestures
    return profile?.name ?: context.getString(R.string.gesture_profile_default)
  }

  @JvmStatic
  fun adjacentId(prefs: SharedPreferences, next: Boolean): String {
    val ids = listOf("") + read(prefs).map { it.id }
    val index = ids.indexOf(activeId(prefs))
    return ids[Math.floorMod(index + if (next) 1 else -1, ids.size)]
  }

  @JvmStatic
  fun choices(context: Context, prefs: SharedPreferences): List<Pair<String, String>> =
    listOf("" to context.getString(R.string.gesture_profile_default)) +
      read(prefs).map { it.id to it.name }

  @JvmStatic fun valueOf(id: String): String = PREFIX + id

  @JvmStatic fun isAction(value: String?): Boolean = value != null && value.startsWith(PREFIX)

  @JvmStatic fun idOf(value: String): String = value.removePrefix(PREFIX)

  @JvmStatic
  fun label(context: Context, prefs: SharedPreferences, value: String?): String? {
    if (!isAction(value)) return null
    val id = idOf(value!!)
    val name =
      if (id.isEmpty()) context.getString(R.string.gesture_profile_default)
      else read(prefs).firstOrNull { it.id == id }?.name ?: return null
    return context.getString(R.string.shortcut_use_gesture_profile, name)
  }

  @JvmStatic fun rowKey(id: String, gestureKey: String): String = "$ROW_PREFIX$id:$gestureKey"

  @JvmStatic fun isRowKey(key: String?): Boolean = key != null && key.startsWith(ROW_PREFIX)

  @JvmStatic
  fun rowValue(prefs: SharedPreferences, key: String?): String? {
    val (id, gestureKey) = parseRowKey(key) ?: return null
    val profile = read(prefs).firstOrNull { it.id == id } ?: return null
    return profile.gestures[gestureKey] ?: DEFAULT_VALUE
  }

  @JvmStatic
  fun setRowValue(prefs: SharedPreferences, key: String?, value: String): Boolean {
    val (id, gestureKey) = parseRowKey(key) ?: return false
    write(
      prefs,
      read(prefs).map {
        if (it.id != id) it
        else if (value == DEFAULT_VALUE) it.copy(gestures = it.gestures - gestureKey)
        else it.copy(gestures = it.gestures + (gestureKey to value))
      },
    )
    return true
  }

  private fun parseRowKey(key: String?): Pair<String, String>? {
    if (!isRowKey(key)) return null
    val rest = key!!.removePrefix(ROW_PREFIX)
    val colon = rest.indexOf(':')
    if (colon <= 0 || colon == rest.length - 1) return null
    return rest.substring(0, colon) to rest.substring(colon + 1)
  }

  fun newId(): String = UUID.randomUUID().toString()

  @JvmStatic
  fun read(prefs: SharedPreferences): List<Profile> = fromJson(prefs.getString(KEY, null))

  @JvmStatic
  fun write(prefs: SharedPreferences, profiles: List<Profile>) {
    prefs.edit().putString(KEY, toJson(profiles)).apply()
    load(prefs)
  }

  fun fromJson(json: String?): List<Profile> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
      val array = JSONArray(json)
      (0 until array.length()).mapNotNull { index ->
        val profile = array.optJSONObject(index) ?: return@mapNotNull null
        val id = profile.optString("id")
        if (id.isEmpty()) return@mapNotNull null
        val gestures = profile.optJSONObject("gestures") ?: JSONObject()
        Profile(
          id,
          profile.optString("name", id),
          gestures.keys().asSequence().associateWith { gestures.getString(it) },
        )
      }
    } catch (e: JSONException) {
      emptyList()
    }
  }

  fun toJson(profiles: List<Profile>): String {
    val array = JSONArray()
    for (profile in profiles) {
      val gestures = JSONObject()
      profile.gestures.forEach { (key, value) -> gestures.put(key, value) }
      array.put(
        JSONObject().put("id", profile.id).put("name", profile.name).put("gestures", gestures)
      )
    }
    return array.toString()
  }
}
