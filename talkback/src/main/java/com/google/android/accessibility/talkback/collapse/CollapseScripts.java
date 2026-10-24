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

import android.text.TextUtils;
import java.util.HashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/** The built-in collapse scripts, keyed by package name. At most one script per package. */
public final class CollapseScripts {
  private static final Map<String, CollapseScript> BY_PACKAGE = new HashMap<>();

  static {
    register(new YouTubeCollapseScript());
    register(new FacebookCollapseScript());
  }

  private CollapseScripts() {}

  private static void register(CollapseScript script) {
    BY_PACKAGE.put(script.packageName(), script);
  }

  public static @Nullable CollapseScript forPackage(@Nullable CharSequence packageName) {
    if (TextUtils.isEmpty(packageName)) {
      return null;
    }
    return BY_PACKAGE.get(packageName.toString());
  }

  static Map<String, CollapseScript> all() {
    return BY_PACKAGE;
  }
}
