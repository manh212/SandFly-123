/*
 * Copyright (C) 2026 The SandFly Authors
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

package com.google.android.accessibility.utils.output;

import android.text.TextUtils;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Holds the text of the most recent utterance actually dispatched to speech. In memory only. */
public final class LastSpokenTextHolder {

  private static final AtomicReference<String> lastSpokenText = new AtomicReference<>();
  private static final AtomicReference<String> savedSpokenText = new AtomicReference<>();

  private LastSpokenTextHolder() {}

  public static void setLastSpokenText(@Nullable CharSequence text) {
    if (TextUtils.isEmpty(text) || TextUtils.getTrimmedLength(text) == 0) {
      return;
    }
    lastSpokenText.set(text.toString());
  }

  public static @Nullable String getLastSpokenText() {
    return lastSpokenText.get();
  }

  public static void setSavedSpokenText(@Nullable CharSequence text) {
    savedSpokenText.set(
        (text == null || TextUtils.getTrimmedLength(text) == 0) ? null : text.toString());
  }

  public static @Nullable String getSavedSpokenText() {
    return savedSpokenText.get();
  }
}
