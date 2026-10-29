# Subtitle OCR

The owner approved automatic spoken subtitle updates, a dedicated settings page, selectable OCR engines, seven region modes, locked automatic regions with manual rediscovery, configurable interruption and a full stop when changing apps. Tesseract is the default. The waiting speech policy finishes current speech and retains only the latest pending subtitle.

`SubtitleSettings` maps persisted choices and custom percentages to the active app window. Focus mode snapshots the current accessibility focus without changing it. `SubtitleTracker` groups candidate lines, locks a padded horizontal region after repeated observations, filters stable text and suppresses overlapping lines. Empty observations never release the locked region. Rotation scales the existing region rather than starting another search.

`SubtitleController` runs one capture/OCR task at a time. It invalidates old callbacks when stopped, restarted, resized or manually reset. Package checks run before the normal accessibility event gate as well as at capture completion and before speech. Screen-off and pause stop the session. A separate speech group lets user input, stop and a newer subtitle affect subtitle speech without interrupting unrelated navigation feedback. Recognition uses the selected local OCR engine and never falls back silently to another engine.

The update dialog's Save to Downloads switch is off by default and remembered. With it off, installation still deletes the cached APK. With it on, the existing package/signature checks run before publishing a separate APK copy to Downloads. Android 10+ uses a pending MediaStore download; Android 8–9 requests write permission and registers the completed public file. A failed copy removes the partial public file. Normal and Shizuku installation retain their existing paths.

## CI coverage

- `SubtitleTrackerTest`: stable text, punctuation, rolling lines, overlapping words, repeated sentences after gaps, locked regions and rotation.
- `SubtitleSettingsTest`: defaults, engine and speech choices, seven regions, focused bounds, malformed restored settings and custom percentages.
- `SubtitleControllerTest`: app changes, screen off, old callbacks, latest pending speech, interruption and explicit user input.
- `UpdateDownloadsTest`: saving disabled by default, scoped storage publication, persistence after cache cleanup, incomplete-copy rollback and legacy storage permissions.

Run these tests in GitHub Actions only. Shared speech-group, resource and manifest changes allow the automatic CI selector to broaden coverage to the affected modules. Device testing remains necessary for real subtitle accuracy, speech timing, accessibility focus and OEM screen-capture behavior.
