package com.Braille;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import com.google.android.accessibility.talkback.keyboardecho.BrailleKeyboardEchoSettings;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi", instrumentedPackages = "com.Braille",
    shadows = BrailleKeyboardServiceTest.BridgeShadow.class)
public class BrailleKeyboardServiceTest {
  @Implements(value = BrailleMeridianBridge.class, isInAndroidSdk = false)
  public static class BridgeShadow {
    static boolean available = true;
    static final List<String> spoken = new ArrayList<>();

    @Implementation
    public static boolean isAvailable() {
      return available;
    }

    @Implementation
    public static void speak(String text, boolean interrupt) {
      spoken.add(text);
    }
  }

  public static class Keyboard extends BrailleKeyboardService {
    InputConnection connection;

    @Override
    public InputConnection getCurrentInputConnection() {
      return connection;
    }
  }

  private Keyboard keyboard;
  private View canvas;
  private Editable text;

  @Before
  public void setUp() {
    BridgeShadow.available = true;
    BridgeShadow.spoken.clear();
    Context context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("BraillePhoneBreakthroughV2Prefs", Context.MODE_PRIVATE)
        .edit().clear().putInt("feedback_mode", 3).putBoolean("auto_capitalize", false).commit();
    SharedPreferencesUtils.getSharedPreferences(context).edit().clear().commit();
    keyboard = Robolectric.buildService(Keyboard.class).create().get();
    text = new SpannableStringBuilder();
    Selection.setSelection(text, 0);
    keyboard.connection = new BaseInputConnection(new View(context), true) {
      @Override
      public Editable getEditable() {
        return text;
      }
    };
    keyboard.onStartInput(new EditorInfo(), false);
    ViewGroup container = (ViewGroup) keyboard.onCreateInputView();
    canvas = container.getChildAt(0);
    canvas.layout(0, 0, 600, 800);
  }

  @After
  public void tearDown() {
    keyboard.onDestroy();
    BridgeShadow.available = true;
  }

  @Test
  public void practiceModeReadsChordButNeverWritesIntoTheEditor() {
    Context context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("BraillePhoneBreakthroughV2Prefs", Context.MODE_PRIVATE)
        .edit().putBoolean(BraillePracticeMode.PREF_KEY, true).commit();
    keyboard.onStartInput(new EditorInfo(), false);
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    touch(MotionEvent.ACTION_UP, 100, 100);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501));
    assertEquals("", text.toString());
    assertEquals(List.of("Chấm 1"), BridgeShadow.spoken);
  }

  @Test
  public void tapCommitsOneCharacterAfterChordDelay() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    touch(MotionEvent.ACTION_UP, 100, 100);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501));
    assertEquals("a", text.toString());
  }

  @Test
  public void swipeAfterDotHoldDoesNotInsertAPhantomCharacter() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
    touch(MotionEvent.ACTION_MOVE, 100, 260);
    touch(MotionEvent.ACTION_UP, 100, 260);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501));
    assertEquals("", text.toString());
  }

  @Test
  public void swipeUsesTheFinalLiftCoordinatesWithoutAMoveEvent() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    touch(MotionEvent.ACTION_UP, 100, 260);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501));
    assertEquals("", text.toString());
  }

  @Test
  public void cancelNeverCommitsAPendingDot() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
    touch(MotionEvent.ACTION_CANCEL, 100, 100);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
    assertEquals("", text.toString());
  }

  @Test
  public void switchingEditorsCancelsThePendingCharacter() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    touch(MotionEvent.ACTION_UP, 100, 100);
    keyboard.onStartInput(new EditorInfo(), false);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501));
    assertEquals("", text.toString());
  }

  @Test
  public void unavailableMeridianNeverCommits() {
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    touch(MotionEvent.ACTION_UP, 100, 100);
    BridgeShadow.available = false;
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501));
    assertEquals("", text.toString());
  }

  @Test
  public void echoGestureUpdatesTheMeridianPreference() {
    ReflectionHelpers.callInstanceMethod(keyboard, "executeImeAction",
        ClassParameter.from(String.class, MainActivity.ACT_CYCLE_ECHO));
    assertEquals(2, BrailleKeyboardEchoSettings.read(keyboard));
  }

  @Test
  public void textUriIsNotMistakenForANumericPassword() {
    assertFalse(password(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
    assertTrue(password(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
    assertTrue(password(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD));
  }

  @Test
  public void passwordTypingAndReadingDoNotRevealTextByDefault() {
    EditorInfo info = new EditorInfo();
    info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    keyboard.onStartInput(info, false);
    touch(MotionEvent.ACTION_DOWN, 100, 100);
    touch(MotionEvent.ACTION_UP, 100, 100);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501));
    assertEquals("a", text.toString());
    assertEquals(List.of("Sao"), BridgeShadow.spoken);
    ReflectionHelpers.callInstanceMethod(keyboard, "executeImeAction",
        ClassParameter.from(String.class, MainActivity.ACT_SPEAK_TEXT));
    assertEquals(List.of("Sao", "Mật khẩu"), BridgeShadow.spoken);
  }

  @Test
  public void silentTypingSuppressesPasswordTypingAndDeletion() {
    SharedPreferencesUtils.getSharedPreferences(keyboard).edit()
        .putString("pref_echo_braille_keyboard", "4").commit();
    EditorInfo info = new EditorInfo();
    info.inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    keyboard.onStartInput(info, false);
    ReflectionHelpers.callInstanceMethod(keyboard, "processBrailleChordOnInputConnection",
        ClassParameter.from(String.class, "1"));
    ReflectionHelpers.callInstanceMethod(keyboard, "executeImeAction",
        ClassParameter.from(String.class, MainActivity.ACT_BACKSPACE));
    assertTrue(BridgeShadow.spoken.isEmpty());
    assertEquals("", text.toString());
  }

  @Test
  public void deletionAtCursorRemovesTheCharacterThatWasJustRead() {
    text.append("ABC");
    Selection.setSelection(text, 3);
    BrailleTextOperations.NavigationState state =
        ReflectionHelpers.getField(keyboard, "navigationState");
    state.recordCharacterMovement(3, true);
    ReflectionHelpers.callInstanceMethod(keyboard, "executeImeAction",
        ClassParameter.from(String.class, MainActivity.ACT_DELETE_CHAR_AT_CURSOR));
    assertEquals("AB", text.toString());
    assertEquals(2, Selection.getSelectionStart(text));
  }

  @Test
  public void deletionAtCursorRemovesOnlySelectedWord() {
    text.append("Tôi đang học chữ nổi");
    Selection.setSelection(text, 9);
    ReflectionHelpers.callInstanceMethod(keyboard, "executeImeAction",
        ClassParameter.from(String.class, MainActivity.ACT_DELETE_WORD_AT_CURSOR));
    assertEquals("Tôi đang  chữ nổi", text.toString());
    assertEquals(9, Selection.getSelectionStart(text));
  }

  @Test
  public void repeatedLineBoundaryActionMovesTheRealEditorSelection() {
    text.append("abc\nxy");
    Selection.setSelection(text, 2);
    Context context = RuntimeEnvironment.getApplication();
    keyboard.connection = new BaseInputConnection(new View(context), true) {
      @Override
      public Editable getEditable() {
        return text;
      }

      @Override
      public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        ExtractedText result = new ExtractedText();
        result.text = text.toString();
        result.startOffset = 0;
        result.selectionStart = Selection.getSelectionStart(text);
        result.selectionEnd = Selection.getSelectionEnd(text);
        return result;
      }

      @Override
      public boolean setSelection(int start, int end) {
        Selection.setSelection(text, start, end);
        return true;
      }
    };
    int[] expected = {0, 3, 4, 6};
    for (int position : expected) {
      ReflectionHelpers.callInstanceMethod(keyboard, "executeImeAction",
          ClassParameter.from(String.class, MainActivity.ACT_MOVE_LINE_BOUNDARY_NEXT));
      assertEquals(position, Selection.getSelectionStart(text));
    }
    ReflectionHelpers.callInstanceMethod(keyboard, "executeImeAction",
        ClassParameter.from(String.class, MainActivity.ACT_MOVE_LINE_START));
    assertEquals(4, Selection.getSelectionStart(text));
    ReflectionHelpers.callInstanceMethod(keyboard, "executeImeAction",
        ClassParameter.from(String.class, MainActivity.ACT_MOVE_LINE_END));
    assertEquals(6, Selection.getSelectionStart(text));
    ReflectionHelpers.callInstanceMethod(keyboard, "executeImeAction",
        ClassParameter.from(String.class, MainActivity.ACT_MOVE_START));
    assertEquals(0, Selection.getSelectionStart(text));
  }

  private boolean password(int inputType) {
    EditorInfo info = new EditorInfo();
    info.inputType = inputType;
    return ReflectionHelpers.callInstanceMethod(keyboard, "isEditorPassword",
        ClassParameter.from(EditorInfo.class, info));
  }

  private void touch(int action, float x, float y) {
    long now = SystemClock.uptimeMillis();
    MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
    canvas.onTouchEvent(event);
    event.recycle();
  }
}
