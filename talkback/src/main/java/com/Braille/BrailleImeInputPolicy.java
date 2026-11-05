package com.Braille;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethod;

public final class BrailleImeInputPolicy {
    private String currentEditor;
    private boolean dismissed;

    public void onStartInput(EditorInfo info) {
        String editor = identifyEditor(info);
        if (editor != null && !editor.equals(currentEditor)) dismissed = false;
        if (editor != null) currentEditor = editor;
    }

    public void onHidden() {
        if (currentEditor != null) dismissed = true;
    }

    public void onFinishInput() {
        currentEditor = null;
        dismissed = false;
    }

    public boolean shouldShow(EditorInfo info, int flags) {
        String editor = identifyEditor(info);
        if (editor != null && !editor.equals(currentEditor)) {
            currentEditor = editor;
            dismissed = false;
        }
        if (!dismissed) return true;
        boolean explicitlyRequested =
                (flags & (InputMethod.SHOW_EXPLICIT | InputMethod.SHOW_FORCED)) != 0;
        if (explicitlyRequested) dismissed = false;
        return explicitlyRequested;
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
