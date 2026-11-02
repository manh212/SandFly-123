# Keyboard schemes

Creating a keyboard scheme creates an empty set of assignments with Caps Lock as
its fixed Meridian modifier. Older custom schemes remain in their previous
format. The new scheme's editor initially contains only Add shortcut. Recording
does not save an assignment until the user selects a command. Cancelling either
step leaves the scheme unchanged. Assigning keys already in use requires an
explicit replacement. Existing assignments can change commands or be removed.

The picker uses the shared command catalogue, including navigation, system
actions and installed applications with launcher activities. Assignments remain
in the existing key-combo preference storage and settings backup includes them.

## Keys

- Ctrl stops current speech as soon as it goes down. It cannot be assigned alone.
  It remains usable in chords such as Ctrl+T.
- Caps Lock is fixed and cannot be reassigned alone. A single tap reports the
  existing system state. Two taps less than 400 ms apart toggle that state.
  Holding it with another key makes a chord, without a standalone announcement.
- A chord is recorded and executed when its main key is released. Releasing the
  modifier first does not change the recorded chord.
- Plain shortcuts require two separate presses. The interval is measured from
  the first release to the second press and must be less than 400 ms. Key repeat
  is not another press. Different keys or keyboard devices break the pair.
- Plain keys, including Telex sequences such as aa and dd, go to the input method
  while the input-focused node is editable, independently of accessibility focus.
  Explicit recording is exempt from that rule.

## Caps Lock access

Start Shizuku and authorize Meridian. The recording flow requests Shizuku access
and checks that the shell user can inject keyboard events before recording.
This uses the existing Shizuku user service rather than requesting the
signature-only INJECT_EVENTS permission for Meridian itself.

Android's input reader changes native Caps Lock on a physical key release before
an accessibility service sees it. For a single tap or modifier chord, Meridian
uses Android's Meta+Alt system shortcut through Shizuku to reverse that native
toggle on the same keyboard. The second tap of a double press keeps its native
toggle. Injected control events bypass Meridian's own key processing.

While a correction is pending, physical keyboard events are copied into an
ordered queue. They are processed after the correction; unconsumed events are
injected with the intended Caps Lock metadata and still pass through the active
input method. No editor text is rewritten and no persistent system key remapping
is installed. Failure is announced instead of claiming that Caps Lock changed.
Without the bridge, Caps Lock retains native behavior and Meridian reports that
access is needed. Other assigned modifier chords remain usable.

## Validation

Unit tests cover blank creation, persistence, deletion, older scheme preservation,
reserved modifiers, the 400 ms boundary, cancellation, cross-device presses,
modifier release order, held Caps Lock, and plain Telex input in editable fields.
They do not establish OEM input-policy compatibility or physical keyboard latency.
CI also validates affected shared code, builds, lint and dev publication.

The owner verifies these cases using the published APK:

1. Bluetooth and USB OTG keyboards, including disconnect and reconnect.
2. Single, double, triple and held Caps Lock with Caps Lock initially on and off.
3. Caps Lock with navigation keys, including releasing Caps Lock first.
4. Fast typing immediately after releasing Caps Lock; keyboard LED and actual
   letter case must agree. Confirm aa, dd and accented Telex text in the usual IME.
5. Input focus remains in an editor while accessibility focus moves elsewhere.
6. Ctrl stops reading immediately; assigned Ctrl chords run only once on release.
7. Application launch, removed applications and collision replacement/cancellation.
8. Shizuku denied, stopped and restarted, plus pausing Meridian while Caps Lock
   is held. OEMs that change the Meta+Alt shortcut require device-specific review.
