# Reverse swipe gestures

**Đảo ngược cử chỉ vuốt** is a switch under Navigation > Movement and window
navigation (**Điều hướng > Di chuyển và chuyển cửa sổ**). It is off by default
and works with the existing one-finger gestures on Android 8 and later.

While enabled, each physical one-finger direction invokes the command previously
assigned to the direction in the second column:

| Physical direction | Existing command assignment |
| --- | --- |
| Up | Left |
| Down | Right |
| Left | Up |
| Right | Down |

The same exchange applies to both segments of the twelve one-finger reversals
and angle gestures. For example, up then right becomes the command previously
assigned to left then down. Multi-finger swipes, including the experimental
two-finger combinations, retain their separate assignments. Taps, holds, physical
keyboard keys and fingerprint gestures retain their behavior.

The switch never rewrites saved gesture assignments. Both stored gesture sets
keep their values, and switching off restores the previous directions. The
existing RTL assignment rules run before the exchange. The shortcut cache is
rebuilt when preferences change, so touch handling needs no preference reads,
extra classification timeout, callback or motion-event interception.

Gesture labels in the existing assignment screen show the current physical
swipe for each saved slot. Changing a command there updates the same slot used
at runtime. Action hints and exported gesture-to-command mappings likewise show
the current physical gesture. Row/column navigation and script gesture handlers
use the corresponding command direction. Gesture practice still names the
physical gesture and checks its actual assigned command.

## CI coverage

- ReverseSwipeSettingsTest covers all sixteen one-finger swipe IDs, default-off
  behavior, reversible mapping, independent multi-finger gestures and taps,
  preservation of custom assignments, physical-direction hints, both stored
  gesture sets, Android 8 and RTL assignments.
- OrganizedSettingsTest covers the switch being inside the Navigation child
  page and the assignment screen showing and saving the active directions.
- Existing gesture, tutorial and settings tests remain relevant because the
  shared shortcut cache and settings-key contract changed. Automatic CI
  selection broadens coverage for these shared changes.

Builds, tests, lint and packaging run only in GitHub Actions. The owner requested
that the coding agent stop after pushing without monitoring the next run.
