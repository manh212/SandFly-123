# Call and volume controls

Calls have their own settings under **Nội dung đọc > Cài đặt cuộc gọi**. Their
announcement switch, voice profile, repeat count, duration speech, Do Not Disturb
choice and quiet hours are independent of notification reading settings.

Incoming announcements are on by default and have zero additional repeats.
A repeat waits for the previous announcement to finish, then waits three seconds.
Answering, dismissing or ending the call cancels pending incoming announcements.
The announcement uses a contact name when contact access is granted, otherwise
an available number or a short unknown-caller announcement. Notification events
and listener events for the same call are deduplicated. An ongoing CallStyle
notification stops notification-based ringing announcements.

End-of-call duration and periodic duration speech are off by default. Periodic
choices are 10, 15, 30, 60, 120 and 300 seconds. Telecom call state provides the
connection time; elapsed time advances with the monotonic clock. A held call
retains its duration but does not announce periodically until active again.
Without Telecom access, a granted call-log permission can supply the final
completed duration, but no duration is guessed from the time spent dialing.

Quiet hours are off by default, with an initial interval of 23:00 to 06:00.
The start is inclusive and the end exclusive, including intervals crossing
midnight. Equal start and end times mean all-day silence. Quiet hours suppress
both incoming and duration speech. They do not change the phone's ringtone.
Calls respect Do Not Disturb unless the separate call setting permits speech.

Initial setup requests phone state, contacts, call-log access and, on Android 12
or later, ongoing-call management. Existing granted steps are skipped. Missing
permissions remain visible in the summary and can be reviewed or skipped.
Ongoing-call management uses the existing Shizuku grant or displays the existing
adb app-op command. Call-log availability remains subject to Android's permission
and installer restrictions. Denial does not stop setup or crash announcement
handling. Phone-state permission is requested through setup instead of again
on service startup or tutorial completion.

## Touch exploration and volume keys

The existing resume setting now controls both paused Meridian and disabled touch
exploration. Its choices are lock-screen appearance, screen on, screen off and
manual. Touch exploration's preference and active state change together. Reloading
an unrelated setting leaves it disabled; the selected screen transition restores
it. Disabling it on an already visible lock screen waits for a new lock-screen
appearance. Existing pause, competing-screen-reader and device restrictions still
apply when requesting touch exploration.

The default simultaneous volume-key action toggles touch exploration, in either
press order, including when individual key management is off. It triggers once
until both keys are released and cancels pending tap and hold actions. A single
press waits up to 150 ms to distinguish the combination; a quick release runs
its single action immediately. Actions already executed before the second key
was pressed cannot be undone. Existing assigned holds still use 500 ms. Calls
and ringing alarms retain normal system key handling. A previously enabled
notification-stop combination retains priority while notification reading is active.

**Bật hoặc tắt nhấn nhiều lần phím âm lượng** is off by default and is independent
of individual key management. Six assignments cover two, three or four presses
of volume up or volume down; all start unassigned. When enabled, up to 400 ms
between releases and the next press groups a sequence. Only the exact count's
assignment runs, so a triple press does not first run the double-press assignment.
The fourth release executes immediately. A lone press uses its existing single
assignment after the sequence window. Changing direction completes the previous
sequence. Holding a key uses its existing hold action or repeats the ordinary
single volume, slider or cursor action. Screen-off, service shutdown, cancellation
or a changed call/lock/settings context drops pending actions.

## Validation before publication

Source and diff review are performed locally. Repository policy keeps automated
checks, tests, lint and builds in GitHub Actions. No local test or build is run.
The initial implementation is held locally pending the owner's publication approval.

Added coverage:

- `TouchExplorationResumePolicyTest`: distinct screen triggers, lock-screen edges,
  manual mode and one-time restoration.
- `VolumeKeyManagerTest`: both chord orders, exact multi-press assignments, empty
  defaults, single presses, reset and call pass-through.
- `CallSettingsTest`: overnight/daytime/all-day boundaries, independent settings
  and bounded stored numeric values.
- `CallAnnouncementsTest`: duplicate sources, finite completion-driven repeats,
  cancellation, independent quiet hours and ongoing-call updates.
- `InitialSetupFlowTest`: asynchronous grants and protection against double
  advancement when returning from Shizuku.

The manifest and shared service changes make the CI selector broaden coverage to
the TalkBack module and affected consumers. Review the actual test-plan artifact
after approval to push. Only the CI run can establish build and test success.
The owner verifies physical volume keys, lock-screen transitions, permissions,
caller identity, duration, voice engines and accessibility focus order on a phone
after the dev release is published.
