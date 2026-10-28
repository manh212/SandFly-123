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

The shared picker in the entry form and task settings provides Online and Offline
modes. Online mode exposes the service, selected model, configuration link and a
refresh action. It retrieves eligible text-generation models from the service,
including paginated Gemini and Claude lists, and caches their names against a
hash of the service address and credentials. Changing credentials invalidates the
cached list. No API key is stored in the model cache. Previously selected aliases
remain visible even when they are absent from the returned list.

The cloud model is remembered separately for each service. Gemini's global
Automatic setting resolves to the build's concrete default instead of an empty
model path. Gemini requests ask for a structured task object. Errors distinguish
access rejection, missing endpoints or models, quota limits, connectivity and
incomplete responses without showing raw server payloads.

Offline mode lists the complete existing model catalog with file size and
installed/compatibility status. Only models marked as supporting structured
answers and compatible with the device can be selected. Download, pause and
resume reuse SandFly's existing download manager and file verification. A download
may continue when the picker closes. The default task candidate is Gemma 4 E2B;
E4B is also available on compatible phones. These are not device performance or
task-accuracy guarantees.

The task local model is independent of the general AI model preference. A task
client binds to the existing inference service with the selected model file;
it does not replace the preferred model used by other features. GPU preference
and existing memory safeguards remain shared. There is no silent cloud fallback.
Local input uses a compact prompt capped at 16,000 UTF-8 bytes, prioritizes
interactive nodes and relevant app names, and marks incomplete catalogs. This
reduces prefill work but is not an exact tokenizer count. Goals that cannot fit
are rejected rather than silently shortened.

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
