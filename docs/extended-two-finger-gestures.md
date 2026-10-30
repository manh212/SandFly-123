# Extended two-finger gesture experiment

The owner approved twelve two-segment gestures, disabled by default, with the enable switch inside Navigation > Touch and activation. Assignment rows belong directly to the existing Two-finger category in the regular gesture screen. There is no separate experiment page or assignment screen. Every new row defaults to Unassigned; disabling the experiment preserves saved assignments.

On Android 13+, the optional backend registers a TouchInteractionController callback and uses service-side gesture matching. No callback is created while disabled or on older Android versions. Existing framework service flags remain unchanged. Unregistering returns touch handling to the framework. Pause, shutdown and disabling touch exploration stop the optional backend; a registration or processing failure disables the experiment to avoid leaving touch handling trapped.

The standard matchers are adapted from Google's Apache-2.0 TalkBack sources at commit `229212fdf5842191d0a93fc95d9ca1423b346866`, under `utils/src/main/java/com/google/android/accessibility/utils/gestures/`. Their license headers are retained. The full TouchInteractionMonitor is not imported. The fork's existing gesture engine remains the default.

The added matcher tracks both pointer IDs independently, requires two clear movements of at least six millimetres, rejects pinching, rotation, extra fingers, delayed second contact and long release gaps, and only completes after both fingers lift. Four reversals and eight perpendicular combinations are recognized. The winning extended matcher suppresses the competing straight-swipe matcher. Ordinary straight swipes still use their existing scroll actions, but are recognized before executing, so they do not provide the same continuous dragging feedback as framework passthrough. Actual accuracy and device-specific touch behavior require phone testing.

## One-finger latency

TouchInteractionController transfers gesture detection for the whole display to
the service. Its public API cannot select only two-finger streams while keeping
native one-finger recognition unchanged. Observing completed framework gestures
instead does not provide the full two-segment stream before scrolling starts.

The optional backend executes callbacks inline on the main thread and dispatches
recognized gestures synchronously, without posting either step to another main
queue task. The non-null direct executor also allows framework callback removal
to return touch handling correctly. One-finger swipes execute as the finger lifts.
The touch-exploration classification timeout is retained to distinguish taps,
double taps, exploration and gestures. This reduces avoidable scheduling latency;
it does not promise zero latency or fully independent one-finger processing.
Disabling the experiment restores the unchanged framework path.

## Menu action audit

Clipboard history and speech history already had stored action identifiers and handlers, but their commands were absent from the action picker. MenuGestureActions inventories all configured menu entries and supplies missing choices without duplicating action values already listed. Existing identifiers are retained. Additional submenu commands use stable named identifiers, not generated Android resource numbers.

New routes open the existing submenu or invoke the existing menu action. They do not briefly open the root menu. A gesture can still invoke a command that was hidden through menu customization; node availability, lock-screen restrictions and service capability checks remain enforced. Dynamic script commands are accessible through their current list because the script API does not expose persistent command identifiers.

## CI coverage

- TwoFingerDirectionalGestureTest: twelve combinations, completion timing, straight and short movements, pinch, rotation, third fingers, cancellation, mismatched directions and delayed contacts/releases.
- GestureManifoldCompatibilityTest: one-finger swipes, straight two-finger swipes and an extended reversal competing with the standard recognizer.
- ExtendedGesturesTest: default-off behavior, saved assignments, unsupported Android versions and placement inside existing settings/categories.
- ExtendedTouchBackendTest: inline callback execution, immediate one-finger swipe dispatch and stopped-backend rejection.
- MenuGestureActionsTest: configured menu coverage, clipboard and speech history visibility, duplicate prevention and stable identifiers.

Builds, lint and tests run only in GitHub Actions. The owner requested that the coding agent stop after pushing rather than monitor the next CI run. The owner validates the APK's real gesture behavior, scrolling, focus order and accessibility labels on a phone.
