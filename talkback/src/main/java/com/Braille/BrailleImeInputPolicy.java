package com.Braille;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.text.InputType;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.EditorInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityWindowInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.AccessibilityEventUtils;
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

    public static void allowUserReopenForActivation(
            Context context, AccessibilityNodeInfoCompat node) {
        if (context != null && node != null
                && (node.isEditable() || Role.getRole(node) == Role.ROLE_EDIT_TEXT
                    || isBrailleKeyboardPickerItem(context, node))) {
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
                BrailleKeyboardService.showFromUserActivation(context);
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
