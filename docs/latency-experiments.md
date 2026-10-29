# Navigation latency experiments

The developer settings contain **Thử nghiệm độ trễ điều hướng**. All nineteen
new switches default to **off**, independently of existing preferences. Existing
settings, including the reading-order cache, split speech and text lookahead,
keep their stored values. Changing one experiment never enables another.

| Preference suffix (`pref_latency_…`) | Enabled behavior |
| --- | --- |
| `trace` | Retain the latest 1,024 gesture records, individual stages, work spans, outcomes and counts exceeding 20 ms and 50 ms. Turning this switch on or off starts a new statistics session. |
| `reset_session` | Reset performance, traversal, event and audio-gap counters when clearing diagnostic logs. |
| `stall_capture` | Capture main-thread stalls from 50 ms, retaining stacks in the diagnostic export even without the verbose log file. |
| `light_logging` | Suppress most verbose/debug formatting during touch and for 250 ms after input; retain warnings, errors and latency markers. Disable the legacy debug watchdog unless explicit stall capture is enabled. |
| `nonblocking_prefetch` | Skip the synchronous window-node prefetch wait on navigation. Existing background prefetch remains governed by the extras master switch. |
| `async_order` | Build a missing logical reading order on a single background worker, then resume the original gesture. Queue subsequent navigation actions in arrival order. Requires the existing reading-order cache switch. |
| `scoped_cache` | Preserve reading order and prepared speech when a known foreign window changes. Preserve the order for same-window metadata-only changes. Active-window transitions still invalidate the root. |
| `input_priority` | Defer text/state-only updates during touch and the input grace interval, invalidate prepared text immediately, prioritize focus events, coalesce more aggressively and yield the compositor queue after a 2 ms slice. |
| `toast_filter` | Avoid heads-up window-tree scans for toast events without an Android notification payload. Real notification processing remains available. |
| `early_controls` | Allow early complete speech for sliders, progress controls and page indicators already exposing text, description or range information. Web, keyboard and known focus-dependent content retain their event path. |
| `cached_text` | Prepare announcements only from a current saved tree, without an extra refresh. Prediction itself can still query nodes. |
| `fresh_prepared` | Allow prepared speech younger than 100 ms when its content generation is still current. Expiry, target type and invalidation remain enforced. |
| `pcm_adjacent` | Prepare PCM for the predicted adjacent target in both directions. |
| `pcm_second` | Prepare PCM for targets two steps away, independently of adjacent PCM. |
| `immediate_speech` | Prioritize speech from navigation actions and linked responses over older noncritical speech. Preserve globally uninterruptible and wait-cap-exempt messages under their existing policies. |
| `fast_scroll` | Resume from ready scroll data with a shorter settling delay and faster early checks. Preserve target/readiness checks and the existing bounded fallback. |
| `live_edge_reuse` | Allow same-window edge checks to reuse a live traversal strategy. |
| `audio_resample` | Resample each new speech stream outside the shared audio-output lock, under a per-stream lock. Takes effect for newly opened streams. |
| `audio_keep_warm` | Extend idle audio-track retention from 2 seconds to 10 seconds. The shorter microphone-in-use policy remains. |

## Preparation and cache boundaries

**Tắt việc phụ gây trễ** is the master veto for background preparation. It must
be off for text lookahead, background node prefetch and both new PCM switches
to work. It now also prevents the previously ungated synchronous prefetch.
The individual PCM switches are disabled in settings while that veto is on;
their stored values are retained. Async construction requested by an actual
navigation action is foreground work dispatched to a worker, so it does not
require idle preparation to be allowed. Idle async warmup does require it.

Text lookahead and PCM are distinct. The existing adjacent/second text switches
prepare feedback; the PCM switches additionally request audio. PCM misses
immediately use normal synthesis. There is no wait for preparation.

PCM preparation uses a separate TTS connection to the current engine and voice,
only while input and foreground synthesis are quiet. New input cancels pending
preparation without a synchronous engine call on the input thread. A shared
engine process can still contend internally; this is an experiment to measure
on the actual engine. Voice profiles and semantic TTS spans fall back to normal
synthesis. Engine, voice, locale, pitch, rate, output sample rate and configuration
isolate cache entries. Voice/settings changes invalidate prepared audio. Local
audio playback is required. The cache has an 8 MiB/eight-entry bound, a four-item
pending queue, six-second clip limit and a 2.5-second synthesis timeout. Temporary
files are private and deleted on completion, cancellation or shutdown.

Async order construction publishes complete trees only after a revision check.
A cooperative 200 ms budget rejects partial results; a single blocked Android
node query cannot be preempted by that budget. Failed or stale builds use the
existing synchronous path and record the fallback. Window transitions and
activation cancel pending actions; queue overflow is reported as a failure.
Script node overrides use foreground traversal to preserve their thread model.
Turning async order off cancels outstanding work. No gesture is silently skipped
by treating a failed or partial build as a complete order.

The input-priority slice yields between events. It cannot interrupt a listener
already blocked in an Android IPC. Structural, focus, editing, direct input and
expected scroll-response events retain their existing processing paths. The
very-low processing-level gate still has its existing single deferred slot and
500 ms release rate; the priority experiment extends deferral to active-window
text/state-only updates during interaction. Dropped background updates are
coalesced, so background announcements can arrive later or omit intermediate
states while experimenting.

## Repeatable device measurements

1. Keep app content, TTS engine/voice/rate, speech splitting, old controls and event
   level unchanged. Record cold start, warm navigation and scrolling separately.
2. Enable per-gesture trace and, when investigating stalls, explicit stall capture.
   Compare logging modes separately because debug logging itself has a cost.
3. Choose **Bắt đầu phiên đo độ trễ** after changing settings and before each run.
   This clears raw files and measurement counters, preserving warm caches.
   Turning on reset-on-clear makes the existing log-clear action reset counters too.
4. Repeat the same swipe sequence with the baseline, then one experiment at a time.
   Do not omit startup, cancellation, failure, missing audio or slow samples.
5. Export diagnostics after each run. Verify source commit, raw preferences,
   settings-enabled preparation and actual PCM availability/hit statistics.

Stage times are cumulative from the original recognized gesture callback, even
through async order, scroll and focus responses. `input_to_callback_ms` separately
measures the last supplied motion timestamp to that callback; older APIs may not
supply it. `ACTION_RESULT` and the legacy action stage are not action durations;
`action_<id>_ms` is the measured duration of that action. Work spans report wall
milliseconds and CPU microseconds measured on the same thread. Aggregate stage
percentiles cannot be subtracted to infer per-gesture work.

`FIRST_PCM` is the first PCM callback (or ready cached PCM). `FIRST_SOUND_PCM`
is the first PCM block containing a nonzero sample. `AUDIO_SUBMITTED` is the
first successful track write; `SOUND_SUBMITTED` is the write containing the first
nonzero samples. Neither proves physical speaker onset. Canceled/no-audio records
and overdue pending gestures remain visible. Counts, budget violations, mean
and maximum cover the session; percentiles cover the latest 300 samples and
individual records the latest 1,024 gestures. Starting a session resets both
populations. The diagnostic export retains up to sixteen captured stall records.

## Validation limits

CI validates cache limits/keying, independent settings, content invalidation,
background deferral, preserved critical speech, gesture attribution, bounded
traversal, prefetch vetoes and toast filtering, along with affected module tests,
compilation and lint. Android IPC scheduling, real TTS startup/contention, audio
routing, physical onset and accessibility focus order require device testing.

The engineering target is typical navigation near 20 ms and a 50 ms upper budget.
These experiments expose budget violations and attempt to remove identifiable
waits. They do not establish or guarantee those figures before matched device
measurements; external app response, scrolling and the selected TTS engine can
still exceed the budget. A separate native audio backend or replacement TTS
engine should be considered only if measurements identify that remaining need.
