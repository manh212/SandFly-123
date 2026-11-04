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

## Automatic call duration limit

Call settings now include an opt-in automatic disconnect switch, an integer
minute limit from 1 through 1440 (initially 10), and a scope with exactly two
choices: outgoing calls only (the default), or both outgoing and incoming calls.
There is no permission-method selector. Existing settings remain unchanged and
the new switch starts off.

Permission handling prefers the ordinary READ_PHONE_STATE/ANSWER_PHONE_CALLS
runtime request when ongoing-call management has not already been granted.
Initial setup offers that ordinary permission first. The management-permission
step is optional during the automatic walk and remains available for explicit
review from the summary. When management access already exists, the ordinary
ending-permission step is omitted and the managed backend is selected directly.
Enabling the limit requests missing ordinary permissions when needed; denial
leaves the switch off.

On Android 12 and later with the existing MANAGE_ONGOING_CALLS app-op, individual
Telecom calls are disconnected using Call.disconnect after the chosen elapsed
connection duration. Incoming versus outgoing is taken from Telecom. Emergency,
emergency-callback, conference, self-managed and external calls are excluded;
unknown direction or unavailable emergency classification is not guessed.
Held calls keep their original deadline.

Without that access, Android 9 and later use TelecomManager.endCall with the
ordinary permission. The API was deprecated in Android 10; the permission itself
was not deprecated. Incoming timing begins when answered. Outgoing OFFHOOK can
include dialing time, which is stated in the settings. Duplicate phone-state
broadcasts do not restart the timer. Call waiting makes the legacy target
ambiguous, so its timer is canceled until the phone returns to idle. Android
rejects emergency-call termination through this API. No silent permission-method
choice is exposed to the user, and compatibility on every device is not promised.
The settings explain the newer-device limitation and point to Shizuku/adb for
the current managed mechanism, without claiming that adb guarantees reliability.

Every limit longer than one minute schedules a single warning exactly one minute
before its deadline. The warning vibrates, plays a short tone on the accessibility
stream and reads "Cuộc gọi sẽ kết thúc sau một phút nữa." A one-minute limit has
no advance warning. Warning and end schedules are both canceled with their target;
late callbacks cannot affect another call. If the system delivers the warning
late, termination is postponed to preserve a full minute after the warning.
Warning speech uses Meridian's active
voice and is independent of the call-reading and call-quiet-hours switches.
Device sound volume, vibrator availability and platform scheduling still apply.

An elapsed-realtime alarm listener and a main-handler backup share a single-fire
guard. The listener alarm does not require a new exact-alarm permission. Timers
are canceled when Meridian stops, the call ends, its target becomes ambiguous, or
the enabled/minutes/scope settings change. Changes apply to subsequent calls;
existing calls at attachment or reconfiguration are not newly armed. No pending
termination is restored after process death. A rejected legacy ending attempt
reports that the call may still be ongoing; it is not retried against a later call.

Additional CI coverage checks scope, normal-permission fallback, cancellation,
permission revocation, call waiting, exact one-minute warnings, the one-minute
exception, duplicate callbacks, saved defaults and input validation. Device
verification remains necessary for actual disconnection, emergency exclusion,
voice output, vibration, sound and screen-off scheduling on supported phones.
