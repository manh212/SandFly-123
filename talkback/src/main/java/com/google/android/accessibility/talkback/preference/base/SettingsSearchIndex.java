package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.text.TextUtils;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.actor.gemini.AiProvider;
import com.google.android.accessibility.talkback.gesture.CustomMenus;
import com.google.android.accessibility.talkback.individualfeedback.FeedbackItem;
import com.google.android.accessibility.talkback.individualfeedback.IndividualFeedbackSettings;
import com.google.android.accessibility.talkback.notificationlist.NotificationPart;
import com.google.android.accessibility.talkback.preference.TalkBackPreferenceFilter;
import com.google.android.accessibility.talkback.quicksettings.QuickControl;
import com.google.android.accessibility.talkback.status.StatusItem;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.VoiceProfiles;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

public final class SettingsSearchIndex {
  private static final String ANDROID = "http://schemas.android.com/apk/res/android";
  private static final String BASE = "com.google.android.accessibility.talkback.preference.base.";

  public static final class Entry {
    public final String title;
    public final String path;
    public final String fragment;
    public final String key;
    public final Bundle arguments;
    public final boolean developer;
    public final String actionQuery;
    private final String searchable;

    Entry(
        String title,
        String summary,
        String path,
        String fragment,
        String key,
        Bundle arguments,
        boolean developer,
        String actionQuery) {
      this.title = title;
      this.path = path;
      this.fragment = fragment;
      this.key = key;
      this.arguments = new Bundle(arguments);
      this.developer = developer;
      this.actionQuery = actionQuery;
      searchable = SearchMatcher.normalize(title + " " + summary);
    }

    public boolean matches(String normalizedQuery) {
      return SearchMatcher.matchesNormalized(normalizedQuery, searchable);
    }
  }

  private record Screen(String fragment, int xml, String path, Bundle arguments, boolean developer) {}
  private record Level(int depth, boolean available, String path) {}

  public static final class Builder {
    private final Context context;
    private final Resources resources;
    private final TalkBackPreferenceFilter filter;
    private final ArrayDeque<Screen> pending = new ArrayDeque<>();
    private final Set<String> visited = new HashSet<>();
    private final Set<String> identities = new HashSet<>();
    private final List<Entry> entries = new ArrayList<>();
    private boolean finished;

    public Builder(Context context) {
      this.context = context;
      resources = context.getResources();
      filter = new TalkBackPreferenceFilter(context);
      pending.add(new Screen(BASE + "TalkBackPreferenceFragment", R.xml.preferences, "", new Bundle(), false));
    }

    public boolean step() {
      if (finished) {
        return false;
      }
      if (!pending.isEmpty()) {
        Screen screen = pending.removeFirst();
        if (visited.add(screen.fragment + ":" + screen.xml)) {
          read(screen);
        }
        return true;
      }
      addSupplemental();
      addCommands();
      addEnumeratedSettings();
      finished = true;
      return false;
    }

    public List<Entry> entries() {
      return new ArrayList<>(entries);
    }

    private void read(Screen screen) {
      ArrayDeque<Level> levels = new ArrayDeque<>();
      levels.push(new Level(0, true, screen.path));
      try (XmlResourceParser xml = resources.getXml(screen.xml)) {
        int event;
        while ((event = xml.next()) != XmlPullParser.END_DOCUMENT) {
          if (event != XmlPullParser.START_TAG) {
            continue;
          }
          int depth = xml.getDepth();
          while (levels.peek().depth >= depth) {
            levels.pop();
          }
          Level parent = levels.peek();
          String key = text(xml, "key");
          String title = text(xml, "title");
          String summary = text(xml, "summary");
          String childFragment = xml.getAttributeValue(ANDROID, "fragment");
          boolean available = parent.available && filter.isSearchable(key);
          boolean category = xml.getName().endsWith("PreferenceCategory");
          String path = category && !title.isEmpty() ? join(parent.path, title) : parent.path;
          levels.push(new Level(depth, available, path));
          if (!available) {
            continue;
          }
          boolean developer = screen.developer || (childFragment != null && childFragment.endsWith(".DeveloperPrefFragment"));
          if (!title.isEmpty() && (!key.isEmpty() || childFragment != null)) {
            add(
                new Entry(
                    title,
                    summary,
                    parent.path,
                    childFragment == null ? screen.fragment : childFragment,
                    childFragment == null ? key : "",
                    screen.arguments,
                    developer,
                    ""));
            int options = xml.getAttributeResourceValue(ANDROID, "entries", 0);
            if (options != 0) {
              for (CharSequence option : resources.getTextArray(options)) {
                add(
                    new Entry(
                        option.toString(),
                        title,
                        join(parent.path, title),
                        screen.fragment,
                        key,
                        screen.arguments,
                        screen.developer,
                        ""));
              }
            }
          }
          if (childFragment != null && childFragment.startsWith("com.google.android.accessibility.talkback.")) {
            int childXml = xmlFor(childFragment);
            if (childXml > 0) {
              pending.add(new Screen(childFragment, childXml, join(parent.path, title), new Bundle(), developer));
              if (childFragment.endsWith(".TalkBackKeyboardShortcutPreferenceFragment")) {
                pending.add(new Screen(childFragment, R.xml.new_key_combo_preferences,
                    join(parent.path, title), new Bundle(), developer));
              }
            }
          }
        }
      } catch (IOException | XmlPullParserException | Resources.NotFoundException error) {
        LogUtils.w("SettingsSearch", "Cannot index %s: %s", screen.fragment, error);
      }
    }

    private int xmlFor(String fragment) {
      try {
        Object screen = Class.forName(fragment).getDeclaredConstructor().newInstance();
        return screen instanceof TalkbackBaseFragment base ? base.getXmlResId() : 0;
      } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
        LogUtils.w("SettingsSearch", "Cannot inspect %s: %s", fragment, error);
        return 0;
      }
    }

    private String text(XmlResourceParser xml, String attribute) {
      int id = xml.getAttributeResourceValue(ANDROID, attribute, 0);
      if (id != 0) {
        return resources.getText(id).toString();
      }
      String value = xml.getAttributeValue(ANDROID, attribute);
      return value == null || value.startsWith("@") || value.startsWith("?") ? "" : value;
    }

    private void addCommands() {
      String path = context.getString(R.string.title_pref_category_manage_gestures);
      for (GestureListPreference.ActionItem item : new GestureListPreference(context, null).getActionItems()) {
        if (item.viewType == GestureListPreference.TYPE_ACTION_ITEM && !item.text.isEmpty()) {
          add(
              new Entry(
                  item.text,
                  "",
                  path,
                  BASE + "TalkBackGestureShortcutPreferenceFragment",
                  "",
                  new Bundle(),
                  false,
                  item.text));
        }
      }
    }

    private void addSupplemental() {
      String fragment = "";
      String path = "";
      try (XmlResourceParser xml = resources.getXml(R.xml.settings_search_extra)) {
        int event;
        while ((event = xml.next()) != XmlPullParser.END_DOCUMENT) {
          if (event != XmlPullParser.START_TAG) {
            continue;
          }
          if (xml.getName().equals("screen")) {
            fragment = xml.getAttributeValue(null, "fragment");
            int title = xml.getAttributeResourceValue(null, "title", 0);
            path = title == 0 ? "" : resources.getString(title);
          } else if (xml.getName().equals("item")) {
            int title = xml.getAttributeResourceValue(null, "title", 0);
            String key = xml.getAttributeValue(null, "key");
            if (title != 0) {
              add(
                  new Entry(
                      resources.getString(title),
                      "",
                      path,
                      fragment,
                      key == null ? "" : key,
                      new Bundle(),
                      false,
                      ""));
            }
          }
        }
      } catch (IOException | XmlPullParserException error) {
        LogUtils.w("SettingsSearch", "Cannot index extra screens: %s", error);
      }
    }

    private void addEnumeratedSettings() {
      for (StatusItem item : StatusItem.values()) {
        add(
            new Entry(
                resources.getString(item.getTitle()),
                resources.getString(item.getSummary()),
                resources.getString(R.string.title_pref_status_readout),
                "com.google.android.accessibility.talkback.status.StatusReadoutFragment",
                item.name(),
                new Bundle(),
                false,
                ""));
      }
      for (NotificationPart part : NotificationPart.values()) {
        add(
            new Entry(
                resources.getString(part.getTitle()),
                "",
                resources.getString(R.string.title_pref_notification_parts),
                "com.google.android.accessibility.talkback.notificationlist.NotificationPartsFragment",
                part.name(),
                new Bundle(),
                false,
                ""));
      }
      for (AiProvider provider : AiProvider.values()) {
        if (provider == AiProvider.GEMINI) {
          continue;
        }
        Bundle args = new Bundle();
        args.putString("provider", provider.getId());
        String title = resources.getString(provider.getLabel());
        add(
            new Entry(
                title,
                "",
                resources.getString(R.string.title_pref_category_ai_services),
                "com.google.android.accessibility.talkback.actor.gemini.AiProviderFragment",
                "",
                args,
                false,
                ""));
        add(
            new Entry(
                resources.getString(R.string.title_pref_gemini_api_key_of, title),
                "",
                title,
                "com.google.android.accessibility.talkback.actor.gemini.AiProviderFragment",
                "",
                args,
                false,
                ""));
        add(
            new Entry(
                resources.getString(R.string.title_pref_gemini_model_of, title),
                "",
                title,
                "com.google.android.accessibility.talkback.actor.gemini.AiProviderFragment",
                "",
                args,
                false,
                ""));
      }
      for (QuickControl control : QuickControl.values()) {
        add(
            new Entry(
                resources.getString(control.getTitle()),
                "",
                resources.getString(R.string.title_pref_quick_settings_items),
                "com.google.android.accessibility.talkback.quicksettings.QuickSettingsItemsFragment",
                control.name(),
                new Bundle(),
                false,
                ""));
      }
      addFeedback(IndividualFeedbackSettings.INSTANCE.getSOUNDS(), "sound", R.string.individual_feedback_sounds_category);
      if (FeatureSupport.isVibratorSupported(context)) {
        addFeedback(IndividualFeedbackSettings.INSTANCE.getVIBRATIONS(), "vibration", R.string.individual_feedback_vibrations_category);
      }
      android.content.SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
      for (String id : VoiceProfiles.ids(prefs)) {
        VoiceProfiles.VoiceProfile profile = VoiceProfiles.read(prefs, id);
        Bundle args = new Bundle();
        args.putString(VoiceProfileFragment.ARG_PROFILE_ID, id);
        add(
            new Entry(
                profile.name(),
                "",
                resources.getString(R.string.title_pref_voice_profiles),
                BASE + "VoiceProfileFragment",
                "",
                args,
                false,
                ""));
      }
      for (CustomMenus.Menu menu : CustomMenus.menus(context)) {
        Bundle args = new Bundle();
        args.putString(CustomMenuEditorFragment.ARG_MENU_ID, menu.id);
        add(
            new Entry(
                menu.name,
                "",
                resources.getString(R.string.custom_menus_title),
                BASE + "CustomMenuEditorFragment",
                "",
                args,
                false,
                ""));
      }
    }

    private void addFeedback(List<FeedbackItem> items, String kind, int category) {
      for (FeedbackItem item : items) {
        add(
            new Entry(
                resources.getString(item.getTitle()),
                "",
                resources.getString(category),
                "com.google.android.accessibility.talkback.individualfeedback.IndividualFeedbackFragment",
                "pref_individual_" + kind + "_" + item.getKey(),
                new Bundle(),
                false,
                ""));
      }
    }

    private void add(Entry entry) {
      if (TextUtils.isEmpty(entry.fragment) || TextUtils.isEmpty(entry.title)) {
        return;
      }
      String identity = entry.fragment + "\n" + entry.key + "\n" + entry.title + "\n" + entry.arguments;
      if (identities.add(identity)) {
        entries.add(entry);
      }
    }
  }

  public static List<Entry> search(List<Entry> entries, String query) {
    String normalized = SearchMatcher.normalize(query);
    List<Entry> matches = new ArrayList<>();
    if (normalized.isEmpty()) {
      return matches;
    }
    for (Entry entry : entries) {
      if (entry.matches(normalized)) {
        matches.add(entry);
      }
    }
    return matches;
  }

  private static String join(String path, String title) {
    return path.isEmpty() ? title : title.isEmpty() ? path : path + " › " + title;
  }

  private SettingsSearchIndex() {}
}
