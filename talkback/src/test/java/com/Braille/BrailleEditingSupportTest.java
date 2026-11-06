package com.Braille;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethod;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BrailleEditingSupportTest {
    @Test
    public void focusedCharacterFollowsBothDirectionsWithoutSplittingSurrogates() {
        BrailleTextOperations.NavigationState navigation = new BrailleTextOperations.NavigationState();
        navigation.recordCharacterMovement(3, true);
        BrailleTextOperations.Range forward = navigation.focusedCharacter("ABC", 3);
        assertEquals(2, forward.start);
        assertEquals(3, forward.end);
        navigation.recordCharacterMovement(2, false);
        BrailleTextOperations.Range backward = navigation.focusedCharacter("ABC", 2);
        assertEquals(2, backward.start);
        assertEquals(3, backward.end);
        BrailleTextOperations.Range emoji = BrailleTextOperations.characterAt("a😀b", 1, false);
        assertEquals("😀", "a😀b".substring(emoji.start, emoji.end));
        BrailleTextOperations.Range combining = BrailleTextOperations.characterAt("a\u0301b", 0, false);
        assertEquals("a\u0301", "a\u0301b".substring(combining.start, combining.end));
    }

    @Test
    public void focusedWordIsTheWordUnderTheCursor() {
        String text = "Tôi đang học chữ nổi";
        BrailleTextOperations.Range range = BrailleTextOperations.wordAt(text, 9);
        assertEquals("học", text.substring(range.start, range.end));
        BrailleTextOperations.Range endRange = BrailleTextOperations.wordAt(text, text.length());
        assertEquals("nổi", text.substring(endRange.start, endRange.end));
    }

    @Test
    public void repeatGestureAlternatesStartAndEndForEveryLine() {
        String text = "abc\nđẹp\nxyz";
        BrailleTextOperations.NavigationState navigation = new BrailleTextOperations.NavigationState();
        int cursor = 2;
        int[] destinations = {0, 3, 4, 7, 8, 11};
        for (int destination : destinations) {
            cursor = navigation.nextLineBoundary(text, cursor);
            assertEquals(destination, cursor);
        }
        assertEquals(text.length(), navigation.nextLineBoundary(text, cursor));
        assertEquals(4, BrailleTextOperations.lineStart(text, 6));
        assertEquals(7, BrailleTextOperations.lineEnd(text, 6));
    }

    @Test
    public void manualDismissalBlocksBothImplicitAndExplicitReopen() {
        android.content.SharedPreferences prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("editing_support_ime", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        BrailleImeInputPolicy policy = new BrailleImeInputPolicy();
        policy.reloadFromPreferences(prefs);
        EditorInfo field = new EditorInfo();
        field.packageName = "com.example.gemini";
        field.fieldId = 3;
        field.inputType = InputType.TYPE_CLASS_TEXT;
        policy.onStartInput(field);
        assertTrue(policy.shouldShow(field, 0));
        policy.onUserDismissed(prefs);
        policy.onHidden();
        assertFalse(policy.shouldShow(field, 0));
        assertFalse(policy.shouldShow(field, InputMethod.SHOW_EXPLICIT));
        EditorInfo another = new EditorInfo();
        another.packageName = "com.example.gemini";
        another.fieldId = 4;
        another.inputType = InputType.TYPE_CLASS_TEXT;
        policy.onFinishInput();
        policy.onStartInput(another);
        assertFalse(policy.shouldShow(another, 0));
        BrailleImeInputPolicy.allowUserReopen(prefs);
        policy.reloadFromPreferences(prefs);
        assertTrue(policy.shouldShow(another, 0));
        prefs.edit().clear().commit();
    }

    @Test
    public void specialFieldsAvoidForcedCapitalizationAndCanPreferKeyEvents() {
        EditorInfo info = new EditorInfo();
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
        assertFalse(BrailleImeInputPolicy.shouldCapitalize(info, true));
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        assertFalse(BrailleImeInputPolicy.shouldCapitalize(info, true));
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME;
        assertTrue(BrailleImeInputPolicy.shouldCapitalize(info, true));
        info.inputType = InputType.TYPE_NULL;
        assertTrue(BrailleImeInputPolicy.needsKeyEvents(info));
    }

    @Test
    public void rejectedTextCommitFallsBackToKeyEvents() {
        Context context = RuntimeEnvironment.getApplication();
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger keyEvents = new AtomicInteger();
        InputConnection connection = new BaseInputConnection(new View(context), true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                commits.incrementAndGet();
                return false;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                keyEvents.incrementAndGet();
                return true;
            }
        };
        assertTrue(BrailleInputWriter.commit(connection, "a", false));
        assertEquals(1, commits.get());
        assertTrue(keyEvents.get() > 0);
        commits.set(0);
        keyEvents.set(0);
        assertTrue(BrailleInputWriter.commit(connection, "1", true));
        assertEquals(0, commits.get());
        assertTrue(keyEvents.get() > 0);
    }
}
