# Accessibility feedback fixes

## Ride applications

Be passenger (`xyz.be.customer`) and Grab passenger (`com.grabtaxi.passenger`)
request unimportant accessibility views only while their application window is
foreground. Other service flags are preserved. Switching applications restores
the regular tree and invalidates traversal and prepared speech caches.
Visible enabled click-action containers with decorative children remain focus
candidates in these apps; containers with independently actionable descendants
keep the existing child traversal. Hidden views and script-hidden nodes remain
excluded. No coordinates, guessed labels, OCR or automatic calls/messages are
introduced. Actual driver-contact screens still require owner device validation.

## Keyboard schemes

Plain shortcuts record and execute on one release, ignoring key repeat. Existing
assignments remain in their existing storage. Editor input keeps plain letters.
Recording has no Shizuku prerequisite. Held Caps Lock chords work without the
bridge; Android's native case toggle remains in that mode. With the authorized
bridge already running, the existing corrected Caps Lock behavior is retained.

## Clipboard history

History and favorite rows show two lines with full accessible text and a minimum
48 dp height. The list gets the available space inside a dialog bounded by the
screen and keyboard-resized viewport. Up/down buttons provide activation-based
scrolling without changing screen-reader swipe gestures. Accessibility actions
open full text and the existing item menu. Existing paste, search and selection
semantics are preserved.

## Engine voice switching

The shared command catalogue includes previous/next engine voice actions for
keyboard and gesture assignments. Speech settings contain an engine-specific
ordered voice selection. Without a custom order, cycling uses installed voices
in the current voice's language. Explicit order may cross languages; unavailable
voices are skipped without choosing outside the saved list. Stable voice names
are persisted, not list positions. Engines that do not expose individual voices
cannot be forced to switch their private internal voice collection.

Default Meridian speech remembers a voice per engine. Active voice profiles only
change their own voice and language, preserving engine, rate, pitch and volume.
Failures do not save a new selection. Voice changes invalidate prepared audio;
voice-insensitive speech and navigation caches are disabled for a selected voice.

## Typing methods and manual labels

The parent typing-method summary resolves the same enum as the chooser, including
direct silent typing. Existing stored numeric values remain unchanged.

Manual labeling is available for actionable controls even when the missing-label
heuristic thinks text already exists. Custom labels take precedence over generic
control descriptions. ID-less actionable controls use the same synthetic key in
the editor, search and spoken output. Synthetic keys depend on tree position and
may need relabeling when the target app changes its layout.

## Validation

CI covers single-press recognition, cancellation, editor passthrough, saved key
assignments, no-bridge Caps Lock chords, compact clipboard previews, typing choice
persistence, manual label lookup, ride-app flags and focus candidates, voice order,
wraparound, missing voices, engine isolation, backend errors and profile settings.
Build, lint and affected shared tests run only in GitHub Actions. The owner checks
physical keyboards, one-finger focus/scroll behavior, actual MultiTTS voices and
both apps' accepted-trip contact controls on the published development APK.
