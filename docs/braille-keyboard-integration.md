# Bundled Braille keyboard integration

## Input and feedback paths

1. Android selects `com.Braille.BrailleKeyboardService` as the input method.
2. `DirectTouchController.directKeyboards` adds only its input-method window to
   the passthrough region, allowing raw multi-pointer input on Android 11+.
3. `BrailleImeCanvasView` accumulates dots and resolves a chord after finger
   release. A single-finger chord waits for the configured letter delay;
   multi-finger chords commit on final release. Cancellation discards the chord.
4. `processBrailleChordOnInputConnection` translates dots and commits text to
   the current application's `InputConnection`.
5. Android sends text changes from the receiving application, not the IME.
   `TextEventInterpreter` accepts a focused, editable node covered by the
   selected Braille IME. Other hidden nodes retain the existing filter.
6. `TextEventFilter` selects the Braille echo preference for software input.
   Recent physical keyboard input retains its physical keyboard preference.
7. The compositor sends character and completed-word feedback to Meridian's
   existing speech controller. The IME does not also echo ordinary characters.
8. Password input uses the IME's masked or explicitly enabled character feedback.
   Automatic password events from the bundled IME are suppressed after text
   history updates, avoiding a second readout. Password reading commands also
   respect the keyboard's masking setting.

`BrailleMeridianBridge` routes command announcements through the same speech
controller and refuses input and speech while Meridian is unavailable or paused.
Its interactive feedback can be spoken during media playback or recording.
Keyboard tones and custom sounds use accessibility audio attributes rather than
the media stream. Sound/vibration choices remain independent of speech echo.

## Corrected integration defects

- The fullscreen keyboard could cover the editor, causing the text interpreter
  to discard the event while the IME assumed Meridian would speak it.
- The imported keyboard's echo enum differed from Meridian's enum for words and
  none. Both now read `pref_echo_braille_keyboard`; legacy values are translated
  only when that preference has not been saved.
- The echo gesture now cycles the actual Meridian preference, including silent
  typing, rather than announcing an instruction or saving a separate value.
- One-finger gesture labels now name the actual screen directions used by the
  recognizer. Stored action assignments retain their command codes.
- Holding a dot no longer prevents a subsequent swipe within the gesture delay.
  Lift coordinates are included even if no final move event arrives.
- Editor changes, input completion, cancellation and hiding cancel pending input.
  New editors also reset capitalization, double-space and deletion-confirmation
  state.
- A text URI field is no longer mistaken for a numeric password merely because
  their variation bits share a value.
- Enabled/selected keyboard checks compare the exact IME component.

## Regression coverage and device validation

Focused regression classes are `BrailleKeyboardServiceTest`,
`BrailleKeyboardEchoSettingsTest`, `TextEventInterpreterBrailleImeTest` and
`TextEventFilterBrailleImeTest`. They exercise chord commits, cancellation,
editor switching, hold-then-swipe, final lift coordinates, shared echo values,
legacy mapping, covered-editor events, password masking and physical input.
CI may broaden selection because the interpreter and filter are shared code.

The owner should validate the published dev APK on a phone:

- Type characters and complete words in a native editor and a browser editor,
  with the keyboard covering the input field.
- Check all five echo settings and change them through the assigned gesture.
- Check one-finger and multi-finger chords, held-dot swipes, calibration,
  rotation, switching editors and closing the keyboard before a pending commit.
- Check default/custom typing, capital and space sounds with vibration enabled
  and disabled, including simultaneous media playback and Bluetooth audio.
- Check masked password input, optional password character speech, silent input,
  deletion and explicit reading commands.
- Pause and resume Meridian, and switch between Braille and another IME.

Automated tests do not establish physical multi-touch behavior, OEM event
delivery, TTS pronunciation, audio routing or real vibration strength. Custom
sound loading still uses synchronous `MediaPlayer.prepare`; large or slow sound
sources can delay the first playback. Editors that emit no accessibility text
events cannot be verified through the ordinary event-based echo path and need
device diagnostics if they remain silent.
