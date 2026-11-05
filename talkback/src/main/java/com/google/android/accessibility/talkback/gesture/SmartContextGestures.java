package com.google.android.accessibility.talkback.gesture;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.SpeechController;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SmartContextGestures {
  public static final String MODE_INHERIT = "inherit";
  public static final String MODE_OWN = "own";
  public static final String MODE_OFF = "off";
  public static final String[] BUILTINS = {"dialog", "call_incoming", "call_active", "short_video", "media"};
  public static final String PREF_MAX_SCROLL = "smart_context_max_scroll";
  public static final String PREF_PRIORITIES = "smart_context_priorities";
  private static final String RULE_PREFIX = "smart_context_rules_";
  private static final String MODE_PREFIX = "smart_context_mode_";
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final String LOG_TAG = "SmartContextGestures";
  private static final String[] CALL_ANSWER = {"Trả lời", "Nhận cuộc gọi", "Answer", "Accept"};
  private static final String[] CALL_DECLINE = {"Từ chối", "Reject", "Decline"};
  private static final String[] CALL_END = {"Kết thúc", "Ngắt cuộc gọi", "End call", "Hang up"};
  private static final String[] CALL_MUTE = {"Tắt tiếng", "Bật tiếng", "Mute", "Unmute"};
  private static Search activeSearch;

  private SmartContextGestures() {}

  public static final class Rule {
    public final String text;
    public final String match;
    public final String other;
    public final boolean caseSensitive;
    public final boolean enabled;

    public Rule(String text, String match, String other, boolean caseSensitive, boolean enabled) {
      this.text = text.trim();
      this.match = match;
      this.other = other.trim();
      this.caseSensitive = caseSensitive;
      this.enabled = enabled;
    }

    public JSONObject toJson() {
      JSONObject value = new JSONObject();
      try {
        value.put("text", text);
        value.put("match", match);
        value.put("other", other);
        value.put("case", caseSensitive);
        value.put("enabled", enabled);
      } catch (org.json.JSONException ignored) {
      }
      return value;
    }

    static Rule fromJson(JSONObject value) {
      return new Rule(
          value.optString("text"),
          value.optString("match", "exact"),
          value.optString("other"),
          value.optBoolean("case", false),
          value.optBoolean("enabled", true));
    }

    boolean matches(String candidate) {
      if (!enabled || text.isEmpty() || candidate == null) return false;
      String value = candidate.trim();
      String target = text;
      String comparison = other;
      if (!caseSensitive) {
        value = value.toLowerCase(Locale.ROOT);
        target = target.toLowerCase(Locale.ROOT);
        comparison = comparison.toLowerCase(Locale.ROOT);
      }
      int position = value.indexOf(target);
      return switch (match) {
        case "contains" -> position >= 0;
        case "starts" -> value.startsWith(target);
        case "ends" -> value.endsWith(target);
        case "before" -> !comparison.isEmpty() && position >= 0
            && value.indexOf(comparison, position + target.length()) >= 0;
        case "after" -> !comparison.isEmpty() && position >= 0
            && value.lastIndexOf(comparison, position) >= 0;
        case "prefix" -> position > 0;
        case "suffix" -> position >= 0 && position + target.length() < value.length();
        default -> value.equals(target);
      };
    }
  }

  private static SharedPreferences prefs(Context context) {
    return SharedPreferencesUtils.getSharedPreferences(context);
  }

  private static String direction(boolean down) {
    return down ? "down" : "up";
  }

  private static String ruleKey(String packageName, boolean down) {
    return RULE_PREFIX + (packageName.isEmpty() ? "global" : "app_" + packageName)
        + "_" + direction(down);
  }

  private static String modeKey(String packageName, boolean down) {
    return MODE_PREFIX + packageName + "_" + direction(down);
  }

  public static String getMode(Context context, String packageName, boolean down) {
    return prefs(context).getString(modeKey(packageName, down), MODE_INHERIT);
  }

  public static void setMode(Context context, String packageName, boolean down, String mode) {
    if (!packageName.isEmpty()) prefs(context).edit().putString(modeKey(packageName, down), mode).apply();
  }

  public static List<Rule> getRules(Context context, String packageName, boolean down) {
    String saved = prefs(context).getString(ruleKey(packageName, down), "[]");
    List<Rule> rules = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(saved);
      for (int i = 0; i < array.length(); i++) {
        JSONObject value = array.optJSONObject(i);
        if (value != null) {
          Rule rule = Rule.fromJson(value);
          if (!rule.text.isEmpty()) rules.add(rule);
        }
      }
    } catch (org.json.JSONException ignored) {
    }
    return rules;
  }

  public static List<Rule> getEffectiveRules(Context context, String packageName, boolean down) {
    String mode = getMode(context, packageName, down);
    if (MODE_OFF.equals(mode)) return Collections.emptyList();
    return getRules(context, MODE_OWN.equals(mode) ? packageName : "", down);
  }

  public static void saveRules(Context context, String packageName, boolean down, List<Rule> rules) {
    JSONArray array = new JSONArray();
    for (Rule rule : rules) array.put(rule.toJson());
    prefs(context).edit().putString(ruleKey(packageName, down), array.toString()).apply();
  }

  public static void addRule(Context context, String packageName, boolean down, Rule rule) {
    if (rule.text.isEmpty()) return;
    List<Rule> rules = getRules(context, packageName, down);
    rules.add(rule);
    saveRules(context, packageName, down, rules);
    if (!packageName.isEmpty()) setMode(context, packageName, down, MODE_OWN);
  }

  public static boolean isBuiltinEnabled(Context context, String name) {
    return prefs(context).getBoolean("smart_context_builtin_" + name, false);
  }

  public static void setBuiltinEnabled(Context context, String name, boolean enabled) {
    prefs(context).edit().putBoolean("smart_context_builtin_" + name, enabled).apply();
  }

  public static List<String> priorities(Context context) {
    List<String> result = new ArrayList<>();
    String saved = prefs(context).getString(PREF_PRIORITIES, "");
    for (String item : saved.split(",")) {
      if (Arrays.asList(BUILTINS).contains(item) && !result.contains(item)) result.add(item);
    }
    for (String item : BUILTINS) if (!result.contains(item)) result.add(item);
    return result;
  }

  public static void setPriorities(Context context, List<String> values) {
    prefs(context).edit().putString(PREF_PRIORITIES, TextUtils.join(",", values)).apply();
  }

  public static int builtinTitle(String name) {
    return switch (name) {
      case "dialog" -> R.string.smart_context_builtin_dialog;
      case "call_incoming" -> R.string.smart_context_builtin_call_incoming;
      case "call_active" -> R.string.smart_context_builtin_call_active;
      case "short_video" -> R.string.smart_context_builtin_short_video;
      default -> R.string.smart_context_builtin_media;
    };
  }

  public static String currentPackage(TalkBackService service) {
    AccessibilityNodeInfo root = service.getRootInActiveWindow();
    return root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
  }

  public static String focusedText(TalkBackService service) {
    AccessibilityNodeInfo root = service.getRootInActiveWindow();
    if (root == null) return "";
    AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
    if (focus == null) return "";
    CharSequence text = !TextUtils.isEmpty(focus.getContentDescription())
        ? focus.getContentDescription() : focus.getText();
    return text == null ? "" : text.toString().trim();
  }

  public static void cancel() {
    Search current = activeSearch;
    if (current != null) {
      current.cancelled = true;
      activeSearch = null;
    }
  }

  public static void perform(TalkBackService service, boolean down) {
    cancel();
    String packageName = currentPackage(service);
    if (packageName.isEmpty()) {
      say(service, service.getString(R.string.smart_context_no_match));
      return;
    }
    boolean builtin = false;
    for (String name : BUILTINS) builtin |= isBuiltinEnabled(service, name);
    List<Rule> rules = getEffectiveRules(service, packageName, down);
    if (!builtin && rules.isEmpty()) {
      say(service, service.getString(R.string.smart_context_not_configured));
      return;
    }
    Search search = new Search(service, packageName, down, rules);
    activeSearch = search;
    search.step();
  }

  public static void readCurrentApplication(TalkBackService service) {
    String packageName = currentPackage(service);
    List<Rule> up = getRules(service, packageName, false);
    List<Rule> down = getRules(service, packageName, true);
    if (packageName.isEmpty() || (up.isEmpty() && down.isEmpty())) {
      say(service, service.getString(R.string.smart_context_no_app_rules));
      return;
    }
    StringBuilder message = new StringBuilder(service.getString(R.string.smart_context_app_rules_title));
    appendDirection(service, message, up, false);
    appendDirection(service, message, down, true);
    say(service, message.toString());
  }

  private static void appendDirection(TalkBackService service, StringBuilder result, List<Rule> rules, boolean down) {
    if (rules.isEmpty()) return;
    result.append(". ").append(service.getString(down ? R.string.smart_context_down : R.string.smart_context_up)).append(": ");
    for (Rule rule : rules) {
      if (!rule.enabled) continue;
      result.append(rule.text).append(", ");
    }
  }

  private static void say(TalkBackService service, String message) {
    if (service.getSpeechController() != null) {
      service.getSpeechController().speak(message, Performance.EVENT_ID_UNTRACKED,
          SpeechController.SpeakOptions.create().setQueueMode(SpeechController.QUEUE_MODE_QUEUE));
    }
  }

  private static final class Candidate {
    final AccessibilityNodeInfo node;
    final long distance;
    final int order;
    Candidate(AccessibilityNodeInfo node, long distance, int order) {
      this.node = node;
      this.distance = distance;
      this.order = order;
    }
  }

  private static List<AccessibilityNodeInfo> visibleNodes(AccessibilityNodeInfo root) {
    List<AccessibilityNodeInfo> nodes = new ArrayList<>();
    ArrayDeque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
    stack.addFirst(root);
    int count = 0;
    while (!stack.isEmpty() && count++ < 2500) {
      AccessibilityNodeInfo node = stack.removeFirst();
      if (!node.isVisibleToUser()) continue;
      nodes.add(node);
      for (int i = node.getChildCount() - 1; i >= 0; i--) {
        AccessibilityNodeInfo child = node.getChild(i);
        if (child != null) stack.addFirst(child);
      }
    }
    return nodes;
  }

  private static Candidate findMatch(AccessibilityNodeInfo root, List<Rule> rules) {
    AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
    Rect focusBounds = new Rect();
    if (focus != null) focus.getBoundsInScreen(focusBounds);
    List<Candidate> choices = new ArrayList<>();
    int order = 0;
    for (AccessibilityNodeInfo node : visibleNodes(root)) {
      if (!node.isEnabled() || node.isEditable() || node.isPassword()) {
        order++;
        continue;
      }
      boolean matched = false;
      for (Rule rule : rules) {
        if (rule.matches(asText(node.getText()))
            || rule.matches(asText(node.getContentDescription()))
            || (android.os.Build.VERSION.SDK_INT >= 28
                && rule.matches(asText(node.getTooltipText())))) {
          matched = true;
          break;
        }
      }
      if (matched) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty()) {
          long distance = focusBounds.isEmpty() ? 0 : distance(bounds, focusBounds);
          choices.add(new Candidate(node, distance, order));
        }
      }
      order++;
    }
    if (choices.isEmpty()) return null;
    choices.sort(Comparator.comparingLong((Candidate c) -> c.distance).thenComparingInt(c -> c.order));
    return choices.get(0);
  }

  static boolean isCallUiPackage(String packageName) {
    if (packageName == null) return false;
    String normalized = packageName.toLowerCase(Locale.ROOT);
    return normalized.contains("incallui")
        || normalized.contains("dialer")
        || normalized.equals("com.android.phone");
  }

  static boolean matchesBuiltinLabel(String candidate, String target) {
    if (candidate == null || target == null || target.isEmpty()) return false;
    String text = candidate.toLowerCase(Locale.ROOT);
    String match = target.toLowerCase(Locale.ROOT);
    int index = text.indexOf(match);
    while (index >= 0) {
      int end = index + match.length();
      boolean startsAtBoundary = index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
      boolean endsAtBoundary = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
      if (startsAtBoundary && endsAtBoundary) return true;
      index = text.indexOf(match, index + 1);
    }
    return false;
  }

  private static Candidate findBuiltinMatch(AccessibilityNodeInfo root, String[] names) {
    AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
    Rect focusBounds = new Rect();
    if (focus != null) focus.getBoundsInScreen(focusBounds);
    List<Candidate> choices = new ArrayList<>();
    int order = 0;
    for (AccessibilityNodeInfo node : visibleNodes(root)) {
      if (!node.isEnabled() || node.isEditable() || node.isPassword()) {
        order++;
        continue;
      }
      for (String name : names) {
        if (!matchesBuiltinLabel(asText(node.getText()), name)
            && !matchesBuiltinLabel(asText(node.getContentDescription()), name)
            && (android.os.Build.VERSION.SDK_INT < 28
                || !matchesBuiltinLabel(asText(node.getTooltipText()), name))) continue;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty()) {
          long distance = focusBounds.isEmpty() ? 0 : distance(bounds, focusBounds);
          choices.add(new Candidate(node, distance, order));
        }
        break;
      }
      order++;
    }
    if (choices.isEmpty()) return null;
    choices.sort(Comparator.comparingLong((Candidate c) -> c.distance).thenComparingInt(c -> c.order));
    return choices.get(0);
  }

  private static String asText(CharSequence text) {
    return text == null ? "" : text.toString();
  }

  private static long distance(Rect a, Rect b) {
    long dx = (long) a.centerX() - b.centerX();
    long dy = (long) a.centerY() - b.centerY();
    return dx * dx + dy * dy;
  }

  private static boolean activate(TalkBackService service, AccessibilityNodeInfo target) {
    AccessibilityNodeInfo current = target;
    for (int depth = 0; current != null && depth < 12; depth++) {
      if (current.isEnabled() && current.isClickable()
          && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
      current = current.getParent();
    }
    Rect rect = new Rect();
    target.getBoundsInScreen(rect);
    if (rect.isEmpty()) return false;
    Path path = new Path();
    path.moveTo(rect.centerX(), rect.centerY());
    GestureDescription description = new GestureDescription.Builder()
        .addStroke(new GestureDescription.StrokeDescription(path, 0, 75)).build();
    return service.dispatchGesture(description, null, null);
  }

  private static AccessibilityNodeInfo scroller(AccessibilityNodeInfo root) {
    AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
    for (AccessibilityNodeInfo node = focus; node != null; node = node.getParent()) {
      if (node.isScrollable()) return node;
    }
    AccessibilityNodeInfo best = null;
    long area = -1;
    for (AccessibilityNodeInfo node : visibleNodes(root)) {
      if (!node.isScrollable()) continue;
      Rect rect = new Rect();
      node.getBoundsInScreen(rect);
      long candidate = (long) rect.width() * rect.height();
      if (candidate > area) {
        area = candidate;
        best = node;
      }
    }
    return best;
  }

  private static boolean scroll(AccessibilityNodeInfo root, boolean forward) {
    AccessibilityNodeInfo node = scroller(root);
    return node != null && node.performAction(
        forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
  }

  private static String visibleSignature(AccessibilityNodeInfo root) {
    StringBuilder signature = new StringBuilder();
    int count = 0;
    for (AccessibilityNodeInfo node : visibleNodes(root)) {
      CharSequence text = !TextUtils.isEmpty(node.getText())
          ? node.getText() : node.getContentDescription();
      if (!TextUtils.isEmpty(text)) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        signature.append(text).append(bounds.top).append(';');
        if (++count >= 35) break;
      }
    }
    return signature.toString();
  }

  private static final class Search {
    final TalkBackService service;
    final String packageName;
    final boolean down;
    final List<Rule> rules;
    final int limit;
    boolean cancelled;
    boolean backwards;
    boolean restoring;
    int scrollCount;
    int displacement;
    int restoreSteps;
    int unchanged;
    String lastSignature = "";

    Search(TalkBackService service, String packageName, boolean down, List<Rule> rules) {
      this.service = service;
      this.packageName = packageName;
      this.down = down;
      this.rules = rules;
      int requested = 20;
      try { requested = Integer.parseInt(prefs(service).getString(PREF_MAX_SCROLL, "20")); }
      catch (NumberFormatException ignored) {}
      limit = requested < 0 ? Integer.MAX_VALUE : requested;
    }

    void step() {
      if (cancelled || activeSearch != this) return;
      AccessibilityNodeInfo root = service.getRootInActiveWindow();
      if (root == null || root.getPackageName() == null
          || !packageName.contentEquals(root.getPackageName())) {
        cancel();
        return;
      }
      if (restoring) {
        if (displacement == 0 || restoreSteps++ >= Math.max(50, scrollCount + 5)) {
          finishFailure();
          return;
        }
        boolean forward = displacement < 0;
        if (!scroll(root, forward)) {
          finishFailure();
          return;
        }
        displacement += forward ? 1 : -1;
        MAIN.postDelayed(this::step, 180);
        return;
      }
      if (rules.isEmpty() && performBuiltins(root)) {
        activeSearch = null;
        return;
      }
      Candidate match = findMatch(root, rules);
      if (match != null && activate(service, match.node)) {
        activeSearch = null;
        return;
      }
      if (rules.isEmpty() || scrollCount >= limit) {
        beginRestore();
        return;
      }
      String signature = visibleSignature(root);
      unchanged = signature.equals(lastSignature) ? unchanged + 1 : 0;
      lastSignature = signature;
      if (unchanged >= 2) {
        if (!backwards) {
          backwards = true;
          unchanged = 0;
          lastSignature = "";
        } else {
          beginRestore();
          return;
        }
      }
      boolean forward = !backwards;
      if (!scroll(root, forward)) {
        if (!backwards) {
          backwards = true;
          unchanged = 0;
          lastSignature = "";
          MAIN.post(this::step);
        } else {
          beginRestore();
        }
        return;
      }
      scrollCount++;
      displacement += forward ? 1 : -1;
      MAIN.postDelayed(this::step, 180);
    }

    boolean performBuiltins(AccessibilityNodeInfo root) {
      for (String name : priorities(service)) {
        if (!isBuiltinEnabled(service, name)) continue;
        if ("dialog".equals(name) && isDialog(root)) {
          if (activateByNames(root, down
              ? new String[]{"Hủy", "Huỷ", "Cancel", "Không", "No"}
              : new String[]{"Đồng ý", "OK", "Có", "Yes", "Xác nhận"})) return true;
        }
        if ("call_incoming".equals(name)) {
          boolean incomingUi = isCallUiPackage(packageName)
              && (findBuiltinMatch(root, CALL_ANSWER) != null
                  || findBuiltinMatch(root, CALL_DECLINE) != null);
          if ((phoneState() == TelephonyManager.CALL_STATE_RINGING || incomingUi)
              && activateByNames(root, down ? CALL_ANSWER : CALL_DECLINE)) {
            return true;
          }
        }
        if ("call_active".equals(name)) {
          boolean activeUi = isCallUiPackage(packageName)
              && (findBuiltinMatch(root, CALL_END) != null
                  || findBuiltinMatch(root, CALL_MUTE) != null);
          if ((phoneState() == TelephonyManager.CALL_STATE_OFFHOOK || activeUi)
              && activateByNames(root, down ? CALL_END : CALL_MUTE)) {
            return true;
          }
        }
        if ("short_video".equals(name) && isShortVideo(root)
            && (pageVideo(root, down) || swipeVideo(service, root, down)
                || scroll(root, down))) return true;
        if ("media".equals(name) && activateByNames(root,
            new String[]{"Tạm dừng", "Phát", "Pause", "Play", "Tiếp tục"})) return true;
      }
      return false;
    }

    static boolean pageVideo(AccessibilityNodeInfo root, boolean next) {
      int pageAction = 0;
      if (android.os.Build.VERSION.SDK_INT >= 29) {
        pageAction = next
            ? AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.getId()
            : AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.getId();
      }
      int scrollAction = next
          ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
          : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
      for (AccessibilityNodeInfo node : visibleNodes(root)) {
        CharSequence className = node.getClassName();
        if (className == null || !className.toString().contains("ViewPager")) continue;
        if (pageAction != 0 && runIfSupported(node, pageAction)) return true;
        if (runIfSupported(node, scrollAction)) return true;
      }
      return false;
    }

    static boolean runIfSupported(AccessibilityNodeInfo node, int action) {
      for (AccessibilityNodeInfo.AccessibilityAction available : node.getActionList()) {
        if (available.getId() == action && node.performAction(action)) return true;
      }
      return false;
    }

    static boolean swipeVideo(TalkBackService service, AccessibilityNodeInfo root, boolean next) {
      Rect bounds = new Rect();
      root.getBoundsInScreen(bounds);
      for (AccessibilityNodeInfo node : visibleNodes(root)) {
        CharSequence className = node.getClassName();
        if (className == null || !className.toString().contains("ViewPager")) continue;
        Rect pager = new Rect();
        node.getBoundsInScreen(pager);
        if (pager.width() >= bounds.width() / 2 && pager.height() >= bounds.height() / 2) {
          bounds.set(pager);
          break;
        }
      }
      if (bounds.height() < 240 || bounds.width() < 160) {
        bounds.set(0, 0, service.getResources().getDisplayMetrics().widthPixels,
            service.getResources().getDisplayMetrics().heightPixels);
      }
      float start = bounds.top + bounds.height() * (next ? 0.78f : 0.22f);
      float end = bounds.top + bounds.height() * (next ? 0.22f : 0.78f);
      GestureDescription.Builder builder = new GestureDescription.Builder();
      for (float fraction : new float[]{0.42f, 0.58f}) {
        float x = bounds.left + bounds.width() * fraction;
        Path path = new Path();
        path.moveTo(x, start);
        path.lineTo(x, end);
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 380));
      }
      boolean dispatched = service.dispatchGesture(builder.build(), new AccessibilityService.GestureResultCallback() {
        @Override
        public void onCompleted(GestureDescription description) {
          Log.d(LOG_TAG, "Video swipe gesture completed");
        }

        @Override
        public void onCancelled(GestureDescription description) {
          Log.w(LOG_TAG, "Video swipe gesture cancelled");
        }
      }, MAIN);
      Log.d(LOG_TAG, "Video swipe submitted=" + dispatched + " next=" + next);
      return dispatched;
    }

    int phoneState() {
      try {
        TelephonyManager manager = service.getSystemService(TelephonyManager.class);
        return manager == null ? TelephonyManager.CALL_STATE_IDLE : manager.getCallState();
      } catch (SecurityException ignored) {
        return TelephonyManager.CALL_STATE_IDLE;
      }
    }

    boolean isDialog(AccessibilityNodeInfo root) {
      boolean positive = findMatch(root, Arrays.asList(
          new Rule("OK", "exact", "", false, true),
          new Rule("Đồng ý", "exact", "", false, true),
          new Rule("Có", "exact", "", false, true))) != null;
      boolean negative = findMatch(root, Arrays.asList(
          new Rule("Hủy", "exact", "", false, true),
          new Rule("Huỷ", "exact", "", false, true),
          new Rule("Cancel", "exact", "", false, true),
          new Rule("Không", "exact", "", false, true))) != null;
      return positive && negative;
    }

    boolean isShortVideo(AccessibilityNodeInfo root) {
      String lower = packageName.toLowerCase(Locale.ROOT);
      if (lower.contains("musically") || lower.contains("ugc.trill") || lower.contains("tiktok")) return true;
      for (AccessibilityNodeInfo node : visibleNodes(root)) {
        String label = asText(node.getContentDescription()).toLowerCase(Locale.ROOT);
        if (label.equals("shorts") || label.equals("reels") || label.equals("video ngắn")) return true;
      }
      return false;
    }

    boolean activateByNames(AccessibilityNodeInfo root, String[] names) {
      Candidate match = findBuiltinMatch(root, names);
      boolean clicked = match != null && activate(service, match.node);
      Log.d(LOG_TAG, "Built-in target found=" + (match != null) + " activated=" + clicked);
      return clicked;
    }

    void beginRestore() {
      restoring = true;
      MAIN.post(this::step);
    }

    void finishFailure() {
      if (activeSearch != this || cancelled) return;
      activeSearch = null;
      say(service, service.getString(R.string.smart_context_no_match));
    }
  }
}
