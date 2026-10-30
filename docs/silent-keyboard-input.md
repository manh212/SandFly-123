# Silent keyboard input

The existing Typing method chooser includes **Chạm trực tiếp, không phản hồi**.
Selecting it explicitly stores method 6; existing values and the default remain
unchanged. On Android 11 and later, the keyboard window is included in the
existing direct-touch region. A floating or resized keyboard uses its current
window bounds. No full-screen passthrough or global pause is enabled.

DirectTouchController owns this region together with existing direct-touch apps
and the navigation bar. It refreshes keyboard bounds immediately on window
changes and again after windows settle. Hiding the keyboard or selecting another
method removes its portion. Screen-off, pause and shutdown stop keyboard event
suppression; resuming restores the region for the visible keyboard. The new
choice is disabled on Android 8–10, and a restored method 6 falls back to the
existing lift-to-type behavior there.

Keyboard-originated interaction and content events are suppressed before normal
focus, speech and feedback processing. The text interpreter still receives edits
from the application's text field and maintains its cursor and edit history.
Its on-screen echo is silent while the direct-silent method is selected.
Meridian does not change the keyboard application's own sound or vibration
settings.

The existing keyboard echo choices also include **Không đọc khi nhập** for each
keyboard type. Unlike the older None choice, this suppresses edit feedback for
insertions, deletions, replacements, clearing text and password changes. The two
keyboard types retain independent saved choices. Explicit cursor navigation and
keyboard shortcuts retain their current behavior. Settings, the menu and reading
controls use the same choice list. No new switch or settings page is added.

## CI coverage

- DirectTouchControllerTest covers keyboard-only regions, immediate floating
  keyboard changes, disabling, pause and screen-off behavior.
- OrganizedSettingsTest covers selecting the new radio choice inside the existing
  chooser and disabling it on unsupported Android versions.
- TypingMethodPrefFragmentTest covers the tap-delay control staying hidden.
- KeyboardEchoSettingsTest covers the independent saved silent choice and its
  shared label.
- TextEventFilterSilentTypingTest covers silent edits, preserved cursor history,
  independent keyboard types and explicit cursor movement.

Tests, lint and packaging run only in GitHub Actions. Changes to shared service
and text-event code broaden CI to their affected modules. Device testing remains
necessary for keyboard bounds, direct touch and keyboard-specific behavior. The
owner requested that the coding agent stop after pushing without monitoring CI.
