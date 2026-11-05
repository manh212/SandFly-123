# Braille IME compatibility and cursor editing

## Compatibility fixes

The Braille input method tracks when its window was hidden while editing the
current field. Repeated implicit show requests for the same editor are declined.
A new editor or an explicit user request can reopen it. This is intended for
chat clients, including Gemini, which may repeatedly request the keyboard.
The host application and Android retain control of focus and may still make
explicit keyboard requests.

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

Relevant Robolectric tests include BrailleEditingSupportTest and
BrailleKeyboardServiceTest, exercising gesture navigation ranges, deletion of
the focused character/word, real cursor selection, password speech privacy,
input policies, and fallback key-event injection. The repository's selected
GitHub Actions tests, lint and build must pass before publishing.

On-device verification remains necessary for Gemini's keyboard visibility,
third-party password/identity forms, Telegram/Messenger regressions, TalkBack
feedback, emoji and Vietnamese accent editing, and cursor placement in
multi-line editors. No IME can guarantee that a third-party app will accept
input into every custom or protected field.
