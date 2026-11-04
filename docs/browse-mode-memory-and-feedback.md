# Browse mode memory and feedback corrections

## Browse mode memory

The former per-app browse-mode switch is now a three-choice setting named
**Ghi nhớ chế độ duyệt**. The choices are global memory, per-app memory and
no memory. The existing boolean preference is migrated to the equivalent
per-app or no-memory choice. The previously saved app choices remain intact.

Global memory keeps the user's manually selected browse-mode state across
application/window changes and service restarts. While global memory is
selected, app restoration and smart-browse focus automation cannot change
browse mode. Explicit user browse-mode commands still work. Selecting global
memory records the current browse-mode state when the service is active.
Per-app memory retains the old behavior; no memory disables app-specific
restoration without deleting stored choices.

## Call volume announcements

Media-volume change broadcasts immediately around call-state transitions are
not read unless a volume key was used recently. Other volume announcements and
the user setting for volume speech remain available. Call-state permission and
device-specific audio behavior can affect recognition of transitions. The
Android volume broadcast does not itself identify the source of a change.

## Voice task input

A successful voice-recognition result with a nonempty, bounded goal starts the
selected task assistant immediately using the same model validation and
execution controller as the original **Thực hiện** button. Missing models,
recognition cancellation and invalid text return to the entry form. Important
actions retain the existing confirmation safeguards.

## Dial-pad pronunciation

Recognized T9 digit-and-letter labels in supported dialer packages are reduced
to the digit for spoken accessibility feedback. Ordinary application text,
unrecognized dial-pad labels and explicitly customized control labels are
preserved.

## Validation

Relevant source and settings changes are covered by focused browse-memory,
call-volume policy and dial-pad unit tests and the task-engine test suite. Android call transitions,
volume slider adjustments, speech recognition timing, real dialer labels,
focus order and window changes still require physical-device testing. GitHub
Actions owns unit tests, lint, builds and development APK publication.
