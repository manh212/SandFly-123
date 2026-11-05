package com.google.android.accessibility.utils.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import com.google.android.accessibility.utils.monitor.InputModeTracker;
import com.google.android.accessibility.utils.output.ActorStateProvider;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TextEventInterpreterBrailleImeTest {
  private Context context;
  private String previousIme;
  private TextEventInterpreter interpreter;
  private AccessibilityNodeInfo node;
  private final List<TextEventInterpretation> spoken = new ArrayList<>();

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    previousIme = Settings.Secure.getString(context.getContentResolver(),
        Settings.Secure.DEFAULT_INPUT_METHOD);
    selectIme("io.github.aaron_gh.meridian/com.Braille.BrailleKeyboardService");
    TextEventHistory history = new TextEventHistory();
    TextCursorTracker tracker = new TextCursorTracker();
    interpreter = new TextEventInterpreter(context, tracker, new InputModeTracker(), history,
        new ActorStateProvider(), null, null, new TextEventFilter(context, tracker, history),
        false, false);
    interpreter.addListener(result -> spoken.add(result.interpretation));
    node = new EditText(context).createAccessibilityNodeInfo();
    node.setFocused(true);
    node.setEditable(true);
    node.setVisibleToUser(false);
  }

  @After
  public void tearDown() {
    selectIme(previousIme);
  }

  @Test
  public void coveredFocusedEditorStillEchoesCharacterOnce() {
    interpreter.interpret(change("", "a", 0), null);
    assertEquals(1, spoken.size());
    assertEquals(TextEventInterpretation.TEXT_ADD, spoken.get(0).getEvent());
    assertEquals("a", spoken.get(0).getAddedText().toString());
  }

  @Test
  public void coveredEditorEchoesCompletedWord() {
    interpreter.setBrailleKeyboardEcho(TextEventFilter.PREF_ECHO_WORDS);
    interpreter.interpret(change("xin", "xin ", 3), null);
    assertEquals(1, spoken.size());
    assertTrue(spoken.get(0).getInitialWord().toString().startsWith("xin"));
    assertEquals("", spoken.get(0).getAddedText().toString());
  }

  @Test
  public void silentBrailleTypingStillDropsFeedback() {
    interpreter.setBrailleKeyboardEcho(TextEventFilter.PREF_ECHO_SILENT_TYPING);
    interpreter.interpret(change("", "a", 0), null);
    assertTrue(spoken.isEmpty());
  }

  @Test
  public void hiddenEditorWithOrdinaryKeyboardIsStillDropped() {
    selectIme("com.google.android.inputmethod.latin/.LatinIME");
    interpreter.interpret(change("", "a", 0), null);
    assertTrue(spoken.isEmpty());
  }

  @Test
  public void hiddenUnfocusedEditorIsStillDropped() {
    node.setFocused(false);
    interpreter.interpret(change("", "a", 0), null);
    assertTrue(spoken.isEmpty());
  }

  @Test
  public void bundledKeyboardOwnsPasswordFeedbackWithoutASecondReadout() {
    selectIme(context.getPackageName() + "/com.Braille.BrailleKeyboardService");
    AccessibilityEvent event = change("", "a", 0);
    event.setPassword(true);
    interpreter.interpret(event, null);
    assertTrue(spoken.isEmpty());
  }

  private void selectIme(String ime) {
    Settings.Secure.putString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, ime);
  }

  private AccessibilityEvent change(String before, String after, int from) {
    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
    shadowOf(event).setSourceNode(node);
    event.setPackageName("com.example.chat");
    event.setEventTime(10_000);
    event.setBeforeText(before);
    event.getText().add(after);
    event.setItemCount(after.length());
    event.setFromIndex(from);
    event.setAddedCount(1);
    event.setRemovedCount(0);
    return event;
  }
}
