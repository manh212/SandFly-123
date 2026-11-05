package com.google.android.accessibility.utils.input;

import static com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_CHARACTERS;
import static com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_CHARACTERS_AND_WORDS;
import static com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_NONE;
import static com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_WORDS;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import com.google.android.accessibility.utils.input.TextEventFilter.TypingInput;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Regression tests for Braille IMEs that send text changes from the host edit field. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class TextEventFilterBrailleImeTest {
  private Context context;
  private String previousInputMethod;
  private TextEventFilter filter;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    previousInputMethod =
        Settings.Secure.getString(
            context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
    filter = new TextEventFilter(context, null, new TextEventHistory());
  }

  @After
  public void tearDown() {
    Settings.Secure.putString(
        context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, previousInputMethod);
  }

  @Test
  public void recognizesSelectedBrailleKeyboardFromItsImeComponent() {
    assertTrue(
        TextEventFilter.isBrailleInputMethodId("com.Braille/.BrailleKeyboardService"));
    assertTrue(
        TextEventFilter.isBrailleInputMethodId("abk.keyboard/.BrailleKeyboard"));
    assertTrue(
        TextEventFilter.isBrailleInputMethodId(
            "io.github.aaron_gh.meridian/com.Braille.BrailleKeyboardService"));
    assertFalse(
        TextEventFilter.isBrailleInputMethodId("com.google.android.inputmethod.latin/.LatinIME"));
    assertFalse(TextEventFilter.isBrailleInputMethodId(null));
    assertFalse(TextEventFilter.isBrailleInputMethodId("not-an-input-method"));
  }

  @Test
  public void brailleKeyboardEchoAppliesWhenTextChangedEventBelongsToHostApplication() {
    selectInputMethod("com.Braille/.BrailleKeyboardService");
    filter.setKeyboardEcho(TypingInput.ON_SCREEN_KEYBOARD, PREF_ECHO_NONE);
    filter.setBrailleKeyboardEcho(PREF_ECHO_CHARACTERS_AND_WORDS);

    AccessibilityEvent event = hostTextChange();

    assertTrue(filter.shouldEchoAddedText(event));
    assertTrue(filter.shouldEchoInitialWords(event));
  }

  @Test
  public void brailleWordOnlyChoiceIsRespectedInsteadOfOnScreenCharacterChoice() {
    selectInputMethod("com.Braille/.BrailleKeyboardService");
    filter.setKeyboardEcho(TypingInput.ON_SCREEN_KEYBOARD, PREF_ECHO_CHARACTERS);
    filter.setBrailleKeyboardEcho(PREF_ECHO_WORDS);

    AccessibilityEvent event = hostTextChange();

    assertFalse(filter.shouldEchoAddedText(event));
    assertTrue(filter.shouldEchoInitialWords(event));
  }

  @Test
  public void ordinaryImeKeepsTheStandardOnScreenKeyboardEcho() {
    selectInputMethod("com.google.android.inputmethod.latin/.LatinIME");
    filter.setKeyboardEcho(TypingInput.ON_SCREEN_KEYBOARD, PREF_ECHO_CHARACTERS);
    filter.setBrailleKeyboardEcho(PREF_ECHO_NONE);

    AccessibilityEvent event = hostTextChange();

    assertTrue(filter.shouldEchoAddedText(event));
    assertFalse(filter.shouldEchoInitialWords(event));
  }

  private void selectInputMethod(String inputMethodId) {
    Settings.Secure.putString(
        context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, inputMethodId);
  }

  @Test
  public void physicalKeyboardKeepsItsOwnEchoWhileBrailleImeIsSelected() {
    selectInputMethod("io.github.aaron_gh.meridian/com.Braille.BrailleKeyboardService");
    filter.setKeyboardEcho(TypingInput.PHYSICAL_KEYBOARD, PREF_ECHO_CHARACTERS);
    filter.setBrailleKeyboardEcho(PREF_ECHO_WORDS);
    filter.setLastKeyEventTime(9_999);
    assertTrue(filter.shouldEchoAddedText(hostTextChange()));
    assertFalse(filter.shouldEchoInitialWords(hostTextChange()));
  }

  private static AccessibilityEvent hostTextChange() {
    AccessibilityEvent event =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
    event.setPackageName("com.example.chat");
    event.setEventTime(10_000);
    return event;
  }
}
