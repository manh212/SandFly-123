package com.Braille;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethod;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BrailleImeInputPolicyTest {
    @Test
    public void onlyUserActivatedEditorsPermitReopening() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs =
                context.getSharedPreferences(BrailleImeInputPolicy.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        BrailleImeInputPolicy policy = new BrailleImeInputPolicy();
        policy.onUserDismissed(prefs);
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
                new ComponentName(context, BrailleKeyboardService.class).flattenToShortString());
        AccessibilityNodeInfoCompat plain = AccessibilityNodeInfoCompat.obtain();
        plain.setClassName("android.widget.TextView");
        BrailleImeInputPolicy.allowUserReopenForActivation(context, plain);
        policy.reloadFromPreferences(prefs);
        assertFalse(policy.shouldShow(new EditorInfo(), InputMethod.SHOW_EXPLICIT));

        AccessibilityNodeInfoCompat editor = AccessibilityNodeInfoCompat.obtain();
        editor.setClassName("android.widget.EditText");
        editor.setEditable(true);
        BrailleImeInputPolicy.allowUserReopenForActivation(context, editor);
        policy.reloadFromPreferences(prefs);
        assertTrue(policy.shouldShow(new EditorInfo(), 0));
        plain.recycle();
        editor.recycle();
        prefs.edit().clear().commit();
    }

    @Test
    public void manualDismissalBlocksAllShowFlagsEvenAfterEditorRestarts() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("test_manual_ime_dismiss", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        BrailleImeInputPolicy policy = new BrailleImeInputPolicy();
        policy.reloadFromPreferences(prefs);

        EditorInfo gemini = new EditorInfo();
        gemini.packageName = "com.google.android.googlequicksearchbox";
        gemini.fieldId = 1;
        policy.onStartInput(gemini);
        assertTrue(policy.shouldShow(gemini, 0));

        policy.onUserDismissed(prefs);
        assertFalse(policy.shouldShow(gemini, 0));
        assertFalse(policy.shouldShow(gemini, InputMethod.SHOW_EXPLICIT));
        assertFalse(policy.shouldShow(gemini, InputMethod.SHOW_FORCED));

        policy.onFinishInput();
        gemini.fieldId = 2;
        policy.onStartInput(gemini);
        assertFalse(policy.shouldShow(gemini, InputMethod.SHOW_EXPLICIT));

        BrailleImeInputPolicy recreated = new BrailleImeInputPolicy();
        recreated.reloadFromPreferences(prefs);
        assertFalse(recreated.shouldShow(gemini, InputMethod.SHOW_FORCED));

        BrailleImeInputPolicy.allowUserReopen(prefs);
        recreated.reloadFromPreferences(prefs);
        assertTrue(recreated.shouldShow(gemini, 0));
        prefs.edit().clear().commit();
    }
}
