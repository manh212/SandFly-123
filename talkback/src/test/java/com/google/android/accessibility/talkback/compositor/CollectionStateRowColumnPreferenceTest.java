package com.google.android.accessibility.talkback.compositor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.monitor.CollectionState;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CollectionStateRowColumnPreferenceTest {
  private Context context;
  private SharedPreferences prefs;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit()
        .putString(
            context.getString(R.string.pref_verbosity_preset_key),
            context.getString(R.string.pref_verbosity_preset_value_custom))
        .putBoolean(customKey(), false)
        .commit();
  }

  @Test
  public void disabledPreferenceSuppressesCoordinatesIncludingLegacyOverload() throws Exception {
    CollectionState grid = grid();
    assertFalse(CollectionStateFeedbackUtils.shouldSpeakRowColumnNumbers(context));
    assertTrue(
        CollectionStateFeedbackUtils.getCollectionItemTransitionDescription(
                null, grid, context, GlobalVariables.TABLE_HEADERS_AFTER, true)
            .toString().isEmpty());
    assertTrue(
        CollectionStateFeedbackUtils.getCollectionItemTransitionDescription(null, grid, context)
            .toString().isEmpty());
  }

  @Test
  public void enablingPreferenceRestoresCoordinatesImmediately() throws Exception {
    CollectionState grid = grid();
    assertTrue(
        CollectionStateFeedbackUtils.getCollectionItemTransitionDescription(
                null, grid, context, GlobalVariables.TABLE_HEADERS_AFTER, true)
            .toString().isEmpty());
    prefs.edit().putBoolean(customKey(), true).commit();
    assertTrue(CollectionStateFeedbackUtils.shouldSpeakRowColumnNumbers(context));
    String announcement =
        CollectionStateFeedbackUtils.getCollectionItemTransitionDescription(
                null, grid, context, GlobalVariables.TABLE_HEADERS_AFTER, true)
            .toString();
    assertTrue(announcement.contains(context.getString(R.string.row_index_template, 1)));
    assertTrue(announcement.contains(context.getString(R.string.column_index_template, 2)));
  }

  @Test
  public void gridSizeIsSuppressedOnlyWhileSwitchIsOff() throws Exception {
    CollectionState grid = grid();
    String off =
        CollectionStateFeedbackUtils.getCollectionTransitionDescription(grid, context).toString();
    String rows = context.getResources().getQuantityString(R.plurals.template_list_row_count, 4, 4);
    String cols = context.getResources().getQuantityString(R.plurals.template_list_column_count, 4, 4);
    assertFalse(off.contains(rows));
    assertFalse(off.contains(cols));
    prefs.edit().putBoolean(customKey(), true).commit();
    String on =
        CollectionStateFeedbackUtils.getCollectionTransitionDescription(grid, context).toString();
    assertTrue(on.contains(rows));
    assertTrue(on.contains(cols));
  }

  @Test
  public void respectsCurrentlySelectedPresetInsteadOfOldBoolean() {
    prefs.edit()
        .putString(
            context.getString(R.string.pref_verbosity_preset_key),
            context.getString(R.string.pref_verbosity_preset_value_high))
        .commit();
    assertTrue(CollectionStateFeedbackUtils.shouldSpeakRowColumnNumbers(context));
    prefs.edit()
        .putString(
            context.getString(R.string.pref_verbosity_preset_key),
            context.getString(R.string.pref_verbosity_preset_value_low))
        .commit();
    assertFalse(CollectionStateFeedbackUtils.shouldSpeakRowColumnNumbers(context));
  }

  private String customKey() {
    return context.getString(R.string.pref_verbosity_preset_value_custom)
        + "_"
        + context.getString(R.string.pref_table_speak_row_column_numbers_key);
  }

  private static CollectionState grid() throws Exception {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setClassName("android.widget.GridView");
    node.setPackageName("com.example.launcher");
    node.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(4, 4, false));
    CollectionState state = new CollectionState();
    setField(state, "mCollectionRoot", AccessibilityNodeInfoCompat.wrap(node));
    setField(state, "mCollectionTransition", CollectionState.NAVIGATE_ENTER);
    setField(
        state,
        "mRowColumnTransition",
        CollectionState.TYPE_ROW | CollectionState.TYPE_COLUMN);
    setField(
        state,
        "mItemState",
        new CollectionState.TableItemState(
            CollectionState.TYPE_NONE, null, null, null, 0, 1));
    return state;
  }

  private static void setField(CollectionState state, String name, Object value) throws Exception {
    Field field = CollectionState.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(state, value);
  }
}
