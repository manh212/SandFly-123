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
        policy.reloadFromPreferences(prefs);
        policy.onStartInput(new EditorInfo());
        policy.onUserDismissed(prefs);
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
                new ComponentName(context, BrailleKeyboardService.class).flattenToShortString());
        AccessibilityNodeInfoCompat plain = AccessibilityNodeInfoCompat.obtain();
        plain.setClassName("android.widget.TextView");
        BrailleImeInputPolicy.allowUserReopenForActivation(context, plain);
        policy.reloadFromPreferences(prefs);
        assertFalse(policy.shouldShow(new EditorInfo(), 0));
        assertTrue(policy.shouldShow(new EditorInfo(), InputMethod.SHOW_EXPLICIT));

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
    public void explicitAndForcedRequestsAlwaysOverrideManualDismissal() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("test_manual_ime_dismiss", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        BrailleImeInputPolicy policy = new BrailleImeInputPolicy();
        policy.reloadFromPreferences(prefs);

        EditorInfo editor = new EditorInfo();
        editor.packageName = "com.google.android.googlequicksearchbox";
        editor.fieldId = 1;
        policy.onStartInput(editor);
        policy.onUserDismissed(prefs);
        assertFalse(policy.shouldShow(editor, 0));
        assertTrue(policy.shouldShow(editor, InputMethod.SHOW_EXPLICIT));
        assertFalse(prefs.getBoolean(BrailleImeInputPolicy.PREF_USER_DISMISSED, false));

        policy.onUserDismissed(prefs);
        assertTrue(policy.shouldShow(editor, InputMethod.SHOW_FORCED));
        assertFalse(prefs.getBoolean(BrailleImeInputPolicy.PREF_USER_DISMISSED, false));
    }

    @Test
    public void manualDismissalStopsImplicitReopenOnlyForSameEditor() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("test_scoped_ime_dismiss", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        BrailleImeInputPolicy policy = new BrailleImeInputPolicy();
        policy.reloadFromPreferences(prefs);

        EditorInfo editor = new EditorInfo();
        editor.packageName = "com.google.android.googlequicksearchbox";
        editor.fieldId = 1;
        policy.onStartInput(editor);
        policy.onUserDismissed(prefs);
        policy.onFinishInput();
        policy.onStartInput(editor);
        assertFalse(policy.shouldShow(editor, 0));

        EditorInfo otherEditor = new EditorInfo();
        otherEditor.packageName = editor.packageName;
        otherEditor.fieldId = 2;
        policy.onFinishInput();
        policy.onStartInput(otherEditor);
        assertTrue(policy.shouldShow(otherEditor, 0));
        assertFalse(prefs.getBoolean(BrailleImeInputPolicy.PREF_USER_DISMISSED, false));
    }

    @Test
    public void dismissalIsScopedAcrossServiceRecreation() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("test_ime_recreation", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        EditorInfo firstEditor = new EditorInfo();
        firstEditor.packageName = "app";
        firstEditor.fieldId = 101;
        BrailleImeInputPolicy first = new BrailleImeInputPolicy();
        first.reloadFromPreferences(prefs);
        first.onStartInput(firstEditor);
        first.onUserDismissed(prefs);

        BrailleImeInputPolicy restored = new BrailleImeInputPolicy();
        restored.reloadFromPreferences(prefs);
        restored.onStartInput(firstEditor);
        assertFalse(restored.shouldShow(firstEditor, 0));

        EditorInfo secondEditor = new EditorInfo();
        secondEditor.packageName = "app";
        secondEditor.fieldId = 102;
        restored.onStartInput(secondEditor);
        assertTrue(restored.shouldShow(secondEditor, 0));
    }

    @Test
    public void legacyUnscopedDismissalDoesNotDisableNewEditor() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("test_ime_legacy_dismissal", Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean(BrailleImeInputPolicy.PREF_USER_DISMISSED, true).commit();
        BrailleImeInputPolicy policy = new BrailleImeInputPolicy();
        policy.reloadFromPreferences(prefs);
        EditorInfo editor = new EditorInfo();
        editor.packageName = "app";
        editor.fieldId = 1;
        policy.onStartInput(editor);

        assertTrue(policy.shouldShow(editor, 0));
        assertFalse(prefs.getBoolean(BrailleImeInputPolicy.PREF_USER_DISMISSED, false));
    }

    @Test
    public void unrelatedSystemHideDoesNotMarkUserDismissed() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("test_ime_system_hide", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        BrailleImeInputPolicy policy = new BrailleImeInputPolicy();
        policy.reloadFromPreferences(prefs);
        policy.onStartInput(new EditorInfo());
        policy.onHidden();
        assertTrue(policy.shouldShow(new EditorInfo(), 0));
    }
}
