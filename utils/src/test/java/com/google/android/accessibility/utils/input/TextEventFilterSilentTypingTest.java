package com.google.android.accessibility.utils.input;

import static com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_CHARACTERS_AND_WORDS;
import static com.google.android.accessibility.utils.input.TextEventFilter.PREF_ECHO_SILENT_TYPING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.robolectric.Shadows.shadowOf;

import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import com.google.android.accessibility.utils.input.TextEventFilter.TypingInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TextEventFilterSilentTypingTest {
  private static final long START = 10_000;
  private final List<Integer> sent = new ArrayList<>();
  private TextEventFilter filter;
  private TextEventHistory history;

  @Before public void setUp() {
    history = new TextEventHistory();
    filter = new TextEventFilter(RuntimeEnvironment.getApplication(), null, history);
    filter.addListener(result -> sent.add(result.interpretation.getEvent()));
    filter.setKeyboardEcho(TypingInput.ON_SCREEN_KEYBOARD, PREF_ECHO_CHARACTERS_AND_WORDS);
    filter.setKeyboardEcho(TypingInput.PHYSICAL_KEYBOARD, PREF_ECHO_SILENT_TYPING);
  }

  @Test public void physicalTypingSilencesInsertionDeletionAndReplacementButKeepsHistory() {
    int[] changes = {TextEventInterpretation.TEXT_ADD, TextEventInterpretation.TEXT_REMOVE,
      TextEventInterpretation.TEXT_REPLACE, TextEventInterpretation.TEXT_CLEAR,
      TextEventInterpretation.TEXT_PASSWORD_ADD, TextEventInterpretation.TEXT_PASSWORD_REMOVE,
      TextEventInterpretation.TEXT_PASSWORD_REPLACE};
    for (int i = 0; i < changes.length; i++) {
      long time = START + i * 300;
      filter.setLastKeyEventTime(time - 10);
      change(changes[i], time);
      assertEquals(time, history.getLastTextChangeTime());
    }
    assertEquals(List.of(), sent);
    assertFalse(filter.shouldEchoAddedText(START + (changes.length - 1) * 300));
  }

  @Test public void physicalSilentChoiceDoesNotSilenceTheOnScreenKeyboard() {
    change(TextEventInterpretation.TEXT_ADD, START);
    assertEquals(List.of(TextEventInterpretation.TEXT_ADD), sent);
  }

  @Test public void silentOnScreenModeDoesNotSilencePhysicalEcho() {
    filter.setKeyboardEcho(TypingInput.ON_SCREEN_KEYBOARD, PREF_ECHO_SILENT_TYPING);
    filter.setKeyboardEcho(TypingInput.PHYSICAL_KEYBOARD, PREF_ECHO_CHARACTERS_AND_WORDS);
    change(TextEventInterpretation.TEXT_REMOVE, START);
    filter.setLastKeyEventTime(START + 300);
    change(TextEventInterpretation.TEXT_ADD, START + 310);
    assertEquals(List.of(TextEventInterpretation.TEXT_ADD), sent);
  }

  @Test public void explicitCursorNavigationIsStillAnnounced() {
    filter.setLastKeyEventTime(START);
    AccessibilityEvent event = event(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED, START + 10);
    AccessibilityNodeInfo node = new EditText(RuntimeEnvironment.getApplication()).createAccessibilityNodeInfo();
    node.setFocused(true);
    shadowOf(event).setSourceNode(node);
    filter.filterAndSendInterpretation(event, null,
      new TextEventInterpretation(TextEventInterpretation.SELECTION_MOVE_CURSOR_NO_SELECTION));
    assertEquals(List.of(TextEventInterpretation.SELECTION_MOVE_CURSOR_NO_SELECTION), sent);
  }

  @Test public void cursorUpdateAfterSilentTypingIsNotAnnounced() {
    filter.setLastKeyEventTime(START);
    change(TextEventInterpretation.TEXT_ADD, START + 10);
    AccessibilityEvent selection = event(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED, START + 20);
    selection.setFromIndex(1);
    selection.setToIndex(1);
    filter.filterAndSendInterpretation(selection, null,
      new TextEventInterpretation(TextEventInterpretation.SELECTION_MOVE_CURSOR_NO_SELECTION));
    assertEquals(List.of(), sent);
    assertEquals(1, history.getLastFromIndex());
    assertEquals(0, history.getTextChangesAwaitingSelection());
  }

  private void change(int type, long time) {
    filter.filterAndSendInterpretation(event(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, time), null,
      new TextEventInterpretation(type));
  }

  private AccessibilityEvent event(int type, long time) {
    AccessibilityEvent event = AccessibilityEvent.obtain(type);
    event.setEventTime(time);
    event.setPackageName("com.example.editor");
    return event;
  }
}
