/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.soundthemes

import com.google.android.accessibility.talkback.R

/** One sound of the built-in library, offered for every sound event. */
data class LibrarySound(val name: String, val resId: Int)

/**
 * Every sound of the packs sent in, in one list, with no split by pack: the newer
 * pack first, then the earlier pack. A sound plays when its row is focused, and
 * choosing it makes it the sound of the event.
 */
val SOUND_LIBRARY: List<LibrarySound> =
  listOf(
    LibrarySound("beep", R.raw.lib_new_beep),
    LibrarySound("camera_click", R.raw.lib_new_camera_click),
    LibrarySound("cancel", R.raw.lib_new_cancel),
    LibrarySound("click", R.raw.lib_new_click),
    LibrarySound("click2", R.raw.lib_new_click2),
    LibrarySound("clock", R.raw.lib_new_clock),
    LibrarySound("focus0", R.raw.lib_new_focus0),
    LibrarySound("focus1", R.raw.lib_new_focus1),
    LibrarySound("focus2", R.raw.lib_new_focus2),
    LibrarySound("focus3", R.raw.lib_new_focus3),
    LibrarySound("focus4", R.raw.lib_new_focus4),
    LibrarySound("long_clicked", R.raw.lib_new_long_clicked),
    LibrarySound("space", R.raw.lib_new_space),
    LibrarySound("tick", R.raw.lib_new_tick),
    LibrarySound("window_state", R.raw.lib_new_window_state),
    LibrarySound("focus0", R.raw.lib_old_focus0),
    LibrarySound("timer_end", R.raw.lib_old_timer_end),
    LibrarySound("feedback_resume", R.raw.lib_old_feedback_resume),
    LibrarySound("next_text", R.raw.lib_old_next_text),
    LibrarySound("scroll_top", R.raw.lib_old_scroll_top),
    LibrarySound("to_back", R.raw.lib_old_to_back),
    LibrarySound("click", R.raw.lib_old_click),
    LibrarySound("paste", R.raw.lib_old_paste),
    LibrarySound("scroll_down", R.raw.lib_old_scroll_down),
    LibrarySound("scroll_up", R.raw.lib_old_scroll_up),
    LibrarySound("long_click", R.raw.lib_old_long_click),
    LibrarySound("tick", R.raw.lib_old_tick),
    LibrarySound("focus4", R.raw.lib_old_focus4),
    LibrarySound("focus_actionable", R.raw.lib_old_focus_actionable),
    LibrarySound("unlock", R.raw.lib_old_unlock),
    LibrarySound("screen_off", R.raw.lib_old_screen_off),
    LibrarySound("beep", R.raw.lib_old_beep),
    LibrarySound("focus1", R.raw.lib_old_focus1),
    LibrarySound("has_link", R.raw.lib_old_has_link),
    LibrarySound("feedback_paused", R.raw.lib_old_feedback_paused),
    LibrarySound("dialog", R.raw.lib_old_dialog),
    LibrarySound("volume", R.raw.lib_old_volume),
    LibrarySound("scroll_page", R.raw.lib_old_scroll_page),
    LibrarySound("view_entered", R.raw.lib_old_view_entered),
    LibrarySound("window_state", R.raw.lib_old_window_state),
    LibrarySound("copy", R.raw.lib_old_copy),
    LibrarySound("append_copy", R.raw.lib_old_append_copy),
    LibrarySound("8F", R.raw.lib_old_f_8f),
    LibrarySound("8M", R.raw.lib_old_f_8m),
    LibrarySound("CX", R.raw.lib_old_cx),
    LibrarySound("Cd", R.raw.lib_old_cd),
    LibrarySound("EI", R.raw.lib_old_ei),
    LibrarySound("Eb", R.raw.lib_old_eb),
    LibrarySound("K2", R.raw.lib_old_k2),
    LibrarySound("PX1", R.raw.lib_old_px1),
    LibrarySound("dG", R.raw.lib_old_dg),
    LibrarySound("en", R.raw.lib_old_en),
    LibrarySound("focus", R.raw.lib_old_focus),
    LibrarySound("lG", R.raw.lib_old_lg),
    LibrarySound("r7", R.raw.lib_old_r7),
  )
