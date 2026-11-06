package com.Braille;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.text.InputType;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethod;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityWindowInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.AccessibilityEventUtils;
import com.google.android.accessibility.utils.Role;

public final class BrailleImeInputPolicy {
    public static final String PREFS_NAME = "BraillePhoneBreakthroughV2Prefs";
    public static final String PREF_USER_DISMISSED = "braille_ime_user_dismissed";
    private static final String PREF_DISMISSED_EDITOR = "braille_ime_dismissed_editor";

    private SharedPreferences preferences;
    private String currentEditor;
    private String dismissedEditor;
    private boolean dismissed;

    public void reloadFromPreferences(SharedPreferences prefs) {
        preferences = prefs;
        dismissed = prefs.getBoolean(PREF_USER_DISMISSED, false);
        dismissedEditor = prefs.getString(PREF_DISMISSED_EDITOR, null);
    }

    public void onStartInput(EditorInfo info) {
        String nextEditor = identifyEditor(info);
        // Closing an IME must not disable it for every editor, including after a restart.
        // Legacy preferences may have a dismissal flag but no editor identity.
        if (dismissed && nextEditor != null && !TextUtils.equals(nextEditor, dismissedEditor)) {
            clearUserDismissal();
        }
        currentEditor = nextEditor;
    }

    public void onHidden() {
        // Android/app-driven hiding is not a user dismissal.
    }

    public void onUserDismissed(SharedPreferences prefs) {
        preferences = prefs;
        dismissed = true;
        dismissedEditor = currentEditor;
        prefs.edit()
                .putBoolean(PREF_USER_DISMISSED, true)
                .putString(PREF_DISMISSED_EDITOR, dismissedEditor == null ? "" : dismissedEditor)
                .apply();
    }

    public static void allowUserReopen(SharedPreferences prefs) {
        prefs.edit().putBoolean(PREF_USER_DISMISSED, false).remove(PREF_DISMISSED_EDITOR).apply();
    }

    private void clearUserDismissal() {
        dismissed = false;
        dismissedEditor = null;
        if (preferences != null) {
            allowUserReopen(preferences);
        }
    }

    public static void allowUserReopenForActivation(
            Context context, AccessibilityNodeInfoCompat node) {
        if (context == null || node == null) {
            return;
        }
        if (isBrailleKeyboardPickerItem(context, node)) {
            BrailleKeyboardService.showFromPickerActivation(context);
        } else if (node.isEditable() || Role.getRole(node) == Role.ROLE_EDIT_TEXT) {
            BrailleKeyboardService.showFromUserActivation(context);
        }
    }

    public static void onKeyboardPickerClick(Context context, AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED
                || !isKeyboardPickerPackage(event.getPackageName())) {
            return;
        }
        AccessibilityNodeInfoCompat source = AccessibilityEventUtils.sourceCompat(event);
        if (source == null) {
            return;
        }
        try {
            if (isBrailleKeyboardPickerItem(context, source)) {
                BrailleKeyboardService.showFromPickerActivation(context);
            }
        } finally {
            source.recycle();
        }
    }

    private static boolean isBrailleKeyboardPickerItem(
            Context context, AccessibilityNodeInfoCompat node) {
        if (!isKeyboardPickerPackage(node.getPackageName())) {
            return false;
        }
        AccessibilityWindowInfoCompat window = node.getWindow();
        if (window == null) {
            return false;
        }
        try {
            return isKeyboardPickerWindow(node, window)
                    && (hasPickerLabel(node, context.getString(R.string.meridian_braille_keyboard_ime))
                        || hasPickerLabel(node, context.getString(R.string.meridian_braille_ime_subtype)));
        } finally {
            window.recycle();
        }
    }

    private static boolean isKeyboardPickerWindow(
            AccessibilityNodeInfoCompat node, AccessibilityWindowInfoCompat window) {
        CharSequence windowTitle = window.getTitle();
        if (TextUtils.equals(windowTitle, "Select input method")
                || TextUtils.equals(windowTitle, "IME Switcher Menu")) {
            return true;
        }

        Resources systemResources = Resources.getSystem();
        int titleId = systemResources.getIdentifier("select_input_method", "string", "android");
        if (titleId == 0) {
            return false;
        }
        String pickerTitle = systemResources.getString(titleId);
        if (TextUtils.equals(windowTitle, pickerTitle) || hasPickerPane(node, pickerTitle)) {
            return true;
        }

        AccessibilityNodeInfoCompat root = window.getRoot();
        if (root == null) {
            return false;
        }
        try {
            boolean found = false;
            for (AccessibilityNodeInfoCompat title :
                    root.findAccessibilityNodeInfosByViewId("android:id/alertTitle")) {
                found |= TextUtils.equals(title.getText(), pickerTitle);
                title.recycle();
            }
            return found;
        } finally {
            root.recycle();
        }
    }

    private static boolean hasPickerPane(AccessibilityNodeInfoCompat node, String pickerTitle) {
        AccessibilityNodeInfoCompat ancestor = node.getParent();
        while (ancestor != null) {
            boolean matches = TextUtils.equals(ancestor.getPaneTitle(), pickerTitle);
            AccessibilityNodeInfoCompat parent = matches ? null : ancestor.getParent();
            ancestor.recycle();
            if (matches) {
                return true;
            }
            ancestor = parent;
        }
        return false;
    }

    private static boolean isKeyboardPickerPackage(CharSequence packageName) {
        return TextUtils.equals(packageName, "android")
                || TextUtils.equals(packageName, "com.android.systemui");
    }

    private static boolean hasPickerLabel(AccessibilityNodeInfoCompat node, String label) {
        if (matchesPickerLabel(node, label)) {
            return true;
        }
        if (!node.isClickable()) {
            return false;
        }
        boolean found = false;
        for (AccessibilityNodeInfoCompat child : node.findAccessibilityNodeInfosByText(label)) {
            found |= matchesPickerLabel(child, label);
            child.recycle();
        }
        return found;
    }

    private static boolean matchesPickerLabel(AccessibilityNodeInfoCompat node, String label) {
        return TextUtils.equals(label, node.getText())
                || TextUtils.equals(label, node.getContentDescription());
    }

    public void onFinishInput() {
        currentEditor = null;
    }

    public boolean isUserDismissed() {
        return dismissed;
    }

    public boolean shouldShow(EditorInfo info, int flags) {
        String editor = identifyEditor(info);
        if (editor != null) {
            if (dismissed && !TextUtils.equals(editor, dismissedEditor)) {
                clearUserDismissal();
            }
            currentEditor = editor;
        }
        // An explicit request must always bypass the remembered manual dismissal.
        if (isExplicitShow(flags)) {
            if (dismissed) {
                clearUserDismissal();
            }
            return true;
        }
        return !dismissed;
    }

    public static boolean isExplicitShow(int flags) {
        return (flags & (InputMethod.SHOW_EXPLICIT | InputMethod.SHOW_FORCED)) != 0;
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
