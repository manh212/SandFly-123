package com.Braille;

import android.content.SharedPreferences;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;

/**
 * A request to show an IME does not identify who initiated it. In particular,
 * SHOW_EXPLICIT may come from an application, so it must never revoke a user's dismissal.
 *
 * Only the keyboard's own UI may explicitly re-arm it through allowUserReopen().
 */
public final class BrailleImeInputPolicy {
    public static final String PREF_USER_DISMISSED = "braille_ime_user_dismissed";

    private String currentEditor;
    private boolean dismissed;

    public void reloadFromPreferences(SharedPreferences prefs) {
        dismissed = prefs.getBoolean(PREF_USER_DISMISSED, false);
    }

    public void onStartInput(EditorInfo info) {
        currentEditor = identifyEditor(info);
        // An editor change or restart is NOT proof of a new user gesture.
    }

    /** A system hide alone does not prove that a person chose to dismiss the keyboard. */
    public void onHidden() {
        // Kept for the IME lifecycle; never clear an explicit dismissal here.
    }

    public void onUserDismissed(SharedPreferences prefs) {
        dismissed = true;
        prefs.edit().putBoolean(PREF_USER_DISMISSED, true).apply();
    }

    public static void allowUserReopen(SharedPreferences prefs) {
        prefs.edit().putBoolean(PREF_USER_DISMISSED, false).apply();
    }

    public void onFinishInput() {
        currentEditor = null;
        // Do not clear dismissed when an app restarts or changes the editor.
    }

    public boolean isUserDismissed() {
        return dismissed;
    }

    public boolean shouldShow(EditorInfo info, int flags) {
        currentEditor = identifyEditor(info);
        // Explicit and forced requests are not trusted evidence of a human action.
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
