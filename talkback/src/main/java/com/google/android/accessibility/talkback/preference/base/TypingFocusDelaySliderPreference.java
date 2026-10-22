/*
 * Copyright 2025 The Android Open Source Project
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

package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.SeekBar;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.preference.PreferenceViewHolder;
import com.google.android.accessibility.material.preference.AccessibilitySeekBarPreference;
import com.google.android.accessibility.talkback.R;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Slider for the typing focus delay. The state description states the current value in
 * milliseconds, so it is read out instead of a percentage of the slider range.
 */
public class TypingFocusDelaySliderPreference extends AccessibilitySeekBarPreference {

  public TypingFocusDelaySliderPreference(@NonNull Context context) {
    super(context);
  }

  public TypingFocusDelaySliderPreference(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
    super.onBindViewHolder(holder);
    SeekBar seekBar = (SeekBar) holder.findViewById(androidx.preference.R.id.seekbar);
    if (seekBar == null) {
      return;
    }
    ViewCompat.setStateDescription(
        seekBar, getContext().getString(R.string.template_milliseconds, getValue()));
  }
}
