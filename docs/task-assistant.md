# Task assistant

The owner approved this feature for `feat/vietnamese-task-assistant`, overriding
this repository's usual master-only workflow for this task. It is a working
implementation that executes Android accessibility actions, not a simulated demo.
Do not merge or publish it as the rolling dev build without the owner's request.

## Entry and input

Open **Giao việc cho SandFly** from the SandFly menu or Apps settings. Users can
also assign the action to a gesture; no default gesture is replaced. Type a
Vietnamese goal or use the system speech recognizer, review the recognized text,
choose an AI, then press **Thực hiện**. Speech recognition availability depends
on the recognition service installed on the phone.

The AI picker reuses configured services and their credentials. The task service
and model are remembered separately from other AI features. A previously selected
cloud model remains selected when the provider's general model setting changes.
Only the active local model is offered, and only if it supports JSON output.
Removing credentials or changing the active local model requires choosing again;
there is no silent fallback to another provider.

## Execution

The controller observes the active non-overlay window, sends bounded accessibility
content and the launcher app catalog to the selected AI, validates one structured
action, checks a fresh screen, executes, then observes again. Supported actions
include launching apps, clicking, long pressing, replacing editable text, exposed
IME submission, scrolling, Back and Home. It can ask a question and continue with
the answer. It cannot execute scripts, arbitrary intents, coordinates or package
names absent from the installed launcher catalog.

Screen content is shared with the chosen provider when cloud AI is used. Password
nodes, identified credential inputs and their children are excluded. The planner is instructed to treat app
content as data, avoid unrelated actions and leave credentials to the user.
Credential questions pause for private entry instead of collecting an answer.

Sending, posting, deleting, payments, installing, granting permissions and other
important actions require confirmation. Native checks also require confirmation
for unknown buttons, long presses, IME submission and sensitive screen contexts,
even if the AI marks them safe. Confirmation is conservative; it is not a proof
that arbitrary third-party labels correctly describe their effects.

Manual touch, gestures or key presses invalidate pending work. SandFly asks each
time whether to pause or cancel. Resume captures the current screen and replans.
Late responses and callbacks cannot resume a stopped task. Locking the phone or
pausing SandFly prevents further execution. Stale screens are not acted upon.

Completion requires visible evidence in the current snapshot and a further fresh
observation. An action returning success alone does not complete the goal. The
controller stops after repeated failures, unchanged results, loops, 40 steps or
ten active minutes. Paused time is excluded from that time limit.

## Limits and validation

This implementation depends on apps exposing meaningful accessibility nodes and
actions. Canvas-only controls, unsupported embedded content and apps that reject
accessibility actions may require manual completion. It does not claim universal
app compatibility or use OCR coordinates as a fallback. AI interpretation and
completion evidence still need owner validation on a phone.

Unit coverage targets strict action validation, confirmation rules, current-screen
completion evidence, cancellation, manual interruption, stale callbacks, unchanged
results, loop prevention and independent AI selection. GitHub Actions performs
checks, tests, lint and APK packaging. The feature branch produces the `apks`
artifact and must skip dev publication. Shared CI workflow changes can broaden
test selection beyond the task assistant classes. No local build or test is run.
