package com.google.android.accessibility.talkback.screenrecognition;

import android.content.Context;
import android.graphics.Rect;
import com.google.android.accessibility.talkback.actor.gemini.AiTextRequests;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ScreenRecognitionAi {
  private static final String PROMPT =
      "Identify visible interactive controls and meaningful text on this screenshot. "
      + "Return only JSON: {\"elements\":[{\"label\":\"short readable name\","
      + "\"role\":\"button\",\"bounds\":[left,top,right,bottom]}]}. "
      + "Choose one role for each element from button, text, heading, image, text_field, "
      + "check_box, switch, slider, tab, or link. "
      + "Coordinates must be integers from 0 to 1000 relative to the image. "
      + "Use visible screen locations only. Do not invent elements or labels. "
      + "Use the language shown on screen. Limit results to 80 elements.";
  private static final Set<String> ROLES =
      new HashSet<>(
          Arrays.asList(
              "button",
              "text",
              "heading",
              "image",
              "text_field",
              "check_box",
              "switch",
              "slider",
              "tab",
              "link"));

  private final AiTextRequests requests;

  public ScreenRecognitionAi(Context context) {
    requests = new AiTextRequests(context, message -> {});
  }

  public void recognize(
      byte[] jpeg, int screenWidth, int screenHeight, Consumer<List<RecognizedItem>> done) {
    if (jpeg.length == 0) {
      done.accept(null);
      return;
    }
    requests.askImage(
        PROMPT,
        jpeg,
        (text, failure) -> {
          done.accept(parse(text, screenWidth, screenHeight));
          return kotlin.Unit.INSTANCE;
        });
  }

  public void cancel() {
    requests.cancel();
  }

  static List<RecognizedItem> parse(String raw, int screenWidth, int screenHeight) {
    if (raw == null || screenWidth <= 0 || screenHeight <= 0) {
      return null;
    }
    try {
      int start = raw.indexOf('{');
      int end = raw.lastIndexOf('}');
      if (start < 0 || end <= start) {
        return null;
      }
      JSONArray elements = new JSONObject(raw.substring(start, end + 1)).optJSONArray("elements");
      if (elements == null) {
        return null;
      }
      List<RecognizedItem> items = new ArrayList<>();
      for (int index = 0; index < elements.length() && items.size() < 80; index++) {
        JSONObject element = elements.optJSONObject(index);
        if (element == null) {
          continue;
        }
        String label = element.optString("label", "").trim();
        String role = element.optString("role", "text").trim().toLowerCase(Locale.ROOT);
        if (!ROLES.contains(role)) {
          role = "text";
        }
        JSONArray bounds = element.optJSONArray("bounds");
        if (label.isEmpty() || bounds == null || bounds.length() != 4) {
          continue;
        }
        int left = coord(bounds.optInt(0, -1), screenWidth);
        int top = coord(bounds.optInt(1, -1), screenHeight);
        int right = coord(bounds.optInt(2, -1), screenWidth);
        int bottom = coord(bounds.optInt(3, -1), screenHeight);
        if (left < 0 || top < 0 || right - left < 5 || bottom - top < 5) {
          continue;
        }
        String name = label.length() > 180 ? label.substring(0, 180) : label;
        items.add(new RecognizedItem(name, new Rect(left, top, right, bottom), false, role));
      }
      RecognitionLayout.sort(items);
      return items;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static int coord(int normalized, int dimension) {
    if (normalized < 0 || normalized > 1000) {
      return -1;
    }
    return (int) Math.round(normalized * dimension / 1000.0);
  }
}
