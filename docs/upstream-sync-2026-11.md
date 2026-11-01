# November 2026 integration

The owner approved the behavior for this integration after the 38-question review. Meridian is the official interface name. Source identifiers and required copyright and license notices remain compatible with the existing project.

The integration reviews upstream through `c3a26d67c65dda21afe97de9710f58f2bdbe1ea5`, plus the subsequent front-application/window-list fix. It adapts the selected changes directly rather than merging the complete upstream tree.

Included behavior:

- Gesture profiles covering existing touch gestures, with script/application priority, inheritance, explicit unassignment, reordering, restart to Default and settings backup.
- Desktop keyboard layouts, configurable double-modifier browse toggle, manual browse mode in editors, pass-next-key, object navigation without implicit focus movement, and an accessibility-tree review cursor that follows normal focus.
- Touch-controller activation under the existing direct tap command, normal-click fallback and per-node script rules.
- Immediate Send text using the dictation target resolver and conservative Send detection.
- An opt-in black live wallpaper and common updated vibration defaults. The owner later excluded the new sound set, so all new audio assets and theme integration were removed.
- Selected audio routing, microphone, scrolling, column navigation, click feedback, notification yielding, selector timing, cache cleanup, clock, app picker, settings command and Quick Settings fixes.

Excluded behavior remains excluded: swipe-and-hold, external service shortcut commands, magnification commands, independent Bluetooth volume, review OCR, forced script speech during calls or recording, and removed product/platform modules.

Validation belongs to GitHub Actions. The repository rules prohibit local builds, checks and tests. CI automatically selects the relevant classes or broadens to affected modules when source changes have uncertain consumers. A passing module run is not a claim that every module or device scenario was tested.

The owner checks the published development APK on a phone: gesture reversal, application priority, profiles and backup, keyboard focus, editor passthrough, touch fallback, Send target safety, calls and recording, audio routes, changing lists, vibration, themes and wallpaper lifecycle. Touch-controller behavior requires a compatible Android 13+ device with the optional gesture backend active. Hardware vibration and OEM background-process behavior cannot be established by unit tests.
