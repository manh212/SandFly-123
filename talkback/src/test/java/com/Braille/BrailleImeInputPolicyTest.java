package com.Braille;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethod;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BrailleImeInputPolicyTest {
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
