# Braille IME compatibility and cursor editing

## Compatibility fixes

The Braille input method remembers a deliberate dismissal only for the editor
that was dismissed. Repeated implicit show requests for that same editor are
declined so chat clients, including Gemini, cannot immediately reopen the
keyboard after the user closes it. New editors are permitted to show; a
manually requested show always clears the old dismissal, including explicit
and forced Android IME requests, selecting the Braille keyboard in the system
picker, and activating an editable field. Legacy unscoped dismissal flags are
discarded when a new editor starts rather than blocking every input field.

The IME explicitly accepts manual requests instead of letting implicit-show
restrictions veto them. Picker activations can arrive before Android updates
its selected IME; in that case the show request stays pending for up to two
seconds while keyboard selection and service binding complete. A user-triggered
request is tracked until Android signals `onWindowShown()`; if no input view
appears, Meridian makes one bounded retry while the original keyboard and
input connection remain active.
Retries stop after dismissal, editor exit, an IME change, or a second unsuccessful
attempt. Diagnostics separately report show requests, acceptance, and
window-visible callbacks. The host application and Android retain ultimate
control of actually displaying the IME window, so acceptance is not a guarantee
on every OEM build.

Braille input uses InputConnection.commitText for standard editors. When an
editor declines a commit, it attempts input key events. Editors with a null
input type, often custom or web controls, prefer key events first. A rejected
commit produces an audible input error rather than claiming success. An editor
that returns success but silently discards text cannot be detected reliably
by an IME; host applications may also deliberately restrict their secure fields.

Password, email and URI fields do not receive forced sentence capitalization.
Password speech continues to use the existing privacy setting and defaults to
not reading password characters.

## Editing actions

Open the Braille keyboard's **Cài đặt cử chỉ** to assign any newly available
action. All existing short/long gesture mappings retain their defaults.

- **Xóa ký tự đang trỏ tới** deletes the last character navigated to using
  forward/back character movement, or the character at the current caret.
  At the end of the field, the preceding character is chosen. Unicode
  combining marks and surrogate pairs are treated as a character unit.
- **Xóa từ đang trỏ tới** deletes the word containing or adjacent to the caret
  without also removing surrounding spaces. It does not automatically delete
  just because the user moves the cursor.
- **Di chuyển: Về đầu dòng hiện tại** moves the editor's real insertion caret
  to the beginning of the current newline-delimited line.
- **Di chuyển: Về cuối dòng hiện tại** moves it to the line's end.
- **Di chuyển: Lần lượt đầu và cuối từng dòng** first moves to the start of
  the current line, next to its end, next to the following line's start,
  then its end. It does not wrap around after the final line.
- The existing **Di chuyển: Về đầu văn bản** and **Di chuyển: Về cuối văn bản**
  actions remain assignable and use absolute editor navigation.

Navigating and deleting are supported in both the in-app Braille preview and
external Android editors. External editors must expose a usable InputConnection,
selection and/or surrounding text; when a host restricts that access, the IME
reports that the operation is unsupported rather than guessing at an unsafe
deletion. A visual soft wrap without a newline may not be distinguishable from
a single logical line through the Android text API.

## Validation

Relevant Robolectric tests include BrailleEditingSupportTest,
BrailleImeInputPolicyTest, and BrailleKeyboardServiceTest, exercising gesture
navigation ranges, deletion of the focused character/word, real cursor selection,
password speech privacy, scoped dismissal, picker-selection races, manual
explicit and forced shows, bounded show retries, input policies, and fallback
key-event injection. The repository's selected
GitHub Actions tests, lint and build must pass before publishing.

On-device verification remains necessary for Gemini's keyboard visibility,
third-party password/identity forms, Telegram/Messenger regressions, TalkBack
feedback, emoji and Vietnamese accent editing, and cursor placement in
multi-line editors. No IME can guarantee that a third-party app will accept
input into every custom or protected field.
