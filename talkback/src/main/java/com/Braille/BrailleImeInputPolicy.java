package com.Braille;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.Role;

public final class BrailleImeInputPolicy {
    public static final String PREFS_NAME = "BraillePhoneBreakthroughV2Prefs";
    public static final String PREF_USER_DISMISSED = "braille_ime_user_dismissed";

    private String currentEditor;
    private boolean dismissed;

    public void reloadFromPreferences(SharedPreferences prefs) {
        dismissed = prefs.getBoolean(PREF_USER_DISMISSED, false);
    }

    public void onStartInput(EditorInfo info) {
        currentEditor = identifyEditor(info);
    }

    public void onHidden() {
    }

    public void onUserDismissed(SharedPreferences prefs) {
        dismissed = true;
        prefs.edit().putBoolean(PREF_USER_DISMISSED, true).apply();
    }

    public static void allowUserReopen(SharedPreferences prefs) {
        prefs.edit().putBoolean(PREF_USER_DISMISSED, false).apply();
    }

    public static void allowUserReopenForEditor(
            Context context, AccessibilityNodeInfoCompat editor) {
        if (context != null && editor != null
                && (editor.isEditable() || Role.getRole(editor) == Role.ROLE_EDIT_TEXT)) {
            allowUserReopen(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
        }
    }

    public void onFinishInput() {
        currentEditor = null;
    }

    public boolean isUserDismissed() {
        return dismissed;
    }

    public boolean shouldShow(EditorInfo info, int flags) {
        currentEditor = identifyEditor(info);
        return !dismissed;
    }

    public static boolean shouldCapitalize(EditorInfo info, boolean enabled) {
        if (!enabled || info == null) return false;
        int inputClass = info.inputType & InputType.TYPE_MASK_CLASS;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false;
        return variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                && variation != InputType.TYPE_TEXT_VARIATION_URI;
    }

    public static boolean needsKeyEvents(EditorInfo info) {
        return info != null && (info.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL;
    }

    private static String identifyEditor(EditorInfo info) {
        if (info == null) return null;
        return String.valueOf(info.packageName) + ":" + info.fieldId + ":"
                + String.valueOf(info.fieldName) + ":" + info.inputType;
    }
}
