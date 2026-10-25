/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.utils;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY;

import android.annotation.SuppressLint;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech.UtteranceInfoCombo;
import com.google.android.accessibility.utils.performance.AccessibilityActionDetails;
import com.google.android.accessibility.utils.performance.AccessibilityAttributes;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Locale.Category;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Utility class for tracking performance statistic per various event types & processing stages.
 *
 * <p>Stages are not strictly sequential, but rather overlap.
 *
 * <p>Latency statistics for {@code STAGE_FEEDBACK_HEARD} is currently inaccurate, as event-to-audio
 * matching is approximate.
 */
public class Performance {

  private static final String TAG = "Performance";

  private static final Logger DEFAULT_LOGGER = (format, args) -> LogUtils.v(TAG, format, args);

  // Randomly choose the threshold first. Feel free to change it.
  private static final long TTS_LATENCY_THRESHOLD_MS = 500;
  private static final long FEEDBACK_COMPOSED_THRESHOLD_MS = 150;
  private static final long FEEDBACK_QUEUED_THRESHOLD_MS = 1000;
  private static final long FEEDBACK_HEARD_THRESHOLD_MS = 1000;

  public static final boolean TRACKING_RANGE_START = false;

  /////////////////////////////////////////////////////////////////////////////////////////////
  // Constants

  /** Stages that each event goes through, where we want to measure latency. */
  @IntDef({
    STAGE_FRAMEWORK,
    STAGE_INLINE_HANDLING,
    STAGE_FEEDBACK_QUEUED,
    STAGE_FEEDBACK_HEARD,
    STAGE_BETWEEN_FEEDBACK_QUEUED_AND_FEEDBACK_HEARD,
    STAGE_FEEDBACK_COMPOSED,
    STAGE_ACTION_PERFORMED
  })
  public @interface StageId {}

  public static final int STAGE_FRAMEWORK = 0; // Latency before TalkBack
  public static final int STAGE_INLINE_HANDLING = 1; // Time during synchronous event handlers
  public static final int STAGE_FEEDBACK_QUEUED = 2; // Time until first speech is queued
  public static final int STAGE_FEEDBACK_HEARD = 3; // Time until speech is heard.
  public static final int STAGE_BETWEEN_FEEDBACK_QUEUED_AND_FEEDBACK_HEARD =
      4; // Time between speech is queued and heard.
  public static final int STAGE_FEEDBACK_COMPOSED = 5; // Time until speech is composed.
  // Time until accessibility action is performed.
  public static final int STAGE_ACTION_PERFORMED = 6;
  public static final ImmutableList<String> STAGE_NAMES =
      ImmutableList.of(
          "STAGE_FRAMEWORK",
          "STAGE_INLINE_HANDLING",
          "STAGE_FEEDBACK_QUEUED",
          "STAGE_FEEDBACK_HEARD",
          "STAGE_BETWEEN_FEEDBACK_QUEUED_AND_FEEDBACK_HEARD",
          "STAGE_FEEDBACK_COMPOSED",
          "STAGE_ACTION_PERFORMED");

  /**
   * Event types for which we want to measure latency.
   *
   * <p>The purpose of this event type is to uniquely identify an event occurring at a point in
   * time. Each event type has sub-types, since many AccessibilityEvent or KeyEvent may occur at the
   * same time, differentiated only by their sub-types.
   *
   * <p>EVENT_TYPE_ACCESSIBILITY: Subtype is AccessibilityEvent event type, from getEventType().
   * EVENT_TYPE_KEY: Subtype is key-code. EVENT_TYPE_KEY_COMBO: Subtype is combo id.
   * EVENT_TYPE_VOLUME_KEY_COMBO: Subtype is combo id. EVENT_TYPE_GESTURE: Subtype is gesture id
   * (perhaps unnecessary since concurrent gestures have not been observed).
   * EVENT_TYPE_FINGERPRINT_GESTURE: Subtype is gesture id. EVENT_TYPE_ROTATE: Subtype is
   * orientation.
   */
  @IntDef({
    EVENT_TYPE_ACCESSIBILITY,
    EVENT_TYPE_KEY,
    EVENT_TYPE_KEY_COMBO,
    EVENT_TYPE_VOLUME_KEY_COMBO,
    EVENT_TYPE_GESTURE,
    EVENT_TYPE_ROTATE,
    EVENT_TYPE_FINGERPRINT_GESTURE,
    EVENT_TYPE_MOTION_EVENT_SOURCE,
    EVENT_TYPE_GESTURE_DETECTION,
    EVENT_TYPE_TALKBACK_HINT
  })
  public @interface EventTypeId {}

  public static final int EVENT_TYPE_ACCESSIBILITY = 0;
  public static final int EVENT_TYPE_KEY = 1;
  public static final int EVENT_TYPE_KEY_COMBO = 2;
  public static final int EVENT_TYPE_VOLUME_KEY_COMBO = 3;
  public static final int EVENT_TYPE_GESTURE = 4;
  public static final int EVENT_TYPE_ROTATE = 5;
  public static final int EVENT_TYPE_FINGERPRINT_GESTURE = 6;
  public static final int EVENT_TYPE_MOTION_EVENT_SOURCE = 7;
  public static final int EVENT_TYPE_GESTURE_DETECTION = 8;
  public static final int EVENT_TYPE_TALKBACK_HINT = 9;

  public static final ImmutableList<String> EVENT_TYPE_NAMES =
      ImmutableList.of(
          "EVENT_TYPE_ACCESSIBILITY",
          "EVENT_TYPE_KEY",
          "EVENT_TYPE_KEY_COMBO",
          "EVENT_TYPE_VOLUME_KEY_COMBO",
          "EVENT_TYPE_GESTURE",
          "EVENT_TYPE_ROTATE",
          "EVENT_TYPE_FINGERPRINT_GESTURE",
          "EVENT_TYPE_MOTION_EVENT_SOURCE",
          "EVENT_TYPE_GESTURE_DETECTION",
          "EVENT_TYPE_TALKBACK_HINT");

  /** Event sub types for hint announced by TalkBack. */
  @IntDef({
    HINT_SUB_TYPE_MAGNIFICATION,
    HINT_SUB_TYPE_TALKBACK_ON,
  })
  public @interface TalkBackHint {}

  public static final int HINT_SUB_TYPE_MAGNIFICATION = 0;
  public static final int HINT_SUB_TYPE_TALKBACK_ON = 1;

  public static final ImmutableList<String> TALKBACK_HINT_SUB_TYPE_NAMES =
      ImmutableList.of("HINT_SUB_TYPE_MAGNIFICATION", "HINT_SUB_TYPE_TALKBACK_ON");

  public static final int MOTION_EVENT_DIRECTION_UNDEFINED = 0;
  public static final int MOTION_EVENT_DIRECTION_FORWARD = 1;
  public static final int MOTION_EVENT_DIRECTION_BACKWARD = 2;

  /** Classes of utterance that queued speech can be waiting behind. */
  public static final String SPEECH_WAIT_BLOCKER_SHORT_UNINTERRUPTIBLE =
      "SPEECH_WAIT_BLOCKER_SHORT_UNINTERRUPTIBLE";

  public static final String SPEECH_WAIT_BLOCKER_UNINTERRUPTIBLE_ANNOUNCEMENT =
      "SPEECH_WAIT_BLOCKER_UNINTERRUPTIBLE_ANNOUNCEMENT";
  public static final String SPEECH_WAIT_QUEUED_BY_DESIGN = "SPEECH_WAIT_QUEUED_BY_DESIGN";
  public static final String SPEECH_WAIT_BLOCKER_OTHER = "SPEECH_WAIT_BLOCKER_OTHER";

  public static final @Nullable EventId EVENT_ID_UNTRACKED = null;

  /////////////////////////////////////////////////////////////////////////////////////////////
  // Member data

  /** Enable to track the latency and compute the statistics accordingly. */
  private boolean computeStatsEnabled = false;

  /** Recent events for which we are collecting stage latencies */
  protected static final int MAX_RECENT_EVENTS = 100;

  protected ArrayDeque<EventId> eventQueue = new ArrayDeque<>();
  protected HashMap<EventId, EventData> eventIndex = new HashMap<>();
  private final HashMap<String, EventId> utteranceToEvent = new HashMap<>();
  protected final Object lockRecentEvents = new Object();

  /** Latency statistics for various event/label types */
  protected HashMap<StatisticsKey, Statistics> labelToStats = new HashMap<>();

  /**
   * Latency statistics for detecting gesture which could be in framework or Talkback side. we
   * should do this testing only on the default display for convenience.
   */
  protected SparseArray<Statistics> gestureDetectionToStats = new SparseArray<>();

  protected final Object lockGestureDetectionToStats = new Object();

  /**
   * The time interaction start obtained from the event time of {@link
   * AccessibilityEvent#TYPE_TOUCH_INTERACTION_START}, which is {@link SystemClock#uptimeMillis()}
   * stamp time base
   */
  private long timeInteractionStart;

  protected final Object lockLabelToStats = new Object();
  protected Statistics allEventStats = new Statistics();

  /** How often queued speech waited behind each class of utterance, by blocker class label. */
  private final HashMap<String, Long> speechWaitBlockerCounts = new HashMap<>();

  /** How often focus was spoken from an announcement prepared before the swipe, and how often not. */
  private long preparedSpeechHits;

  private long preparedSpeechMisses;

  /**
   * Preparation rounds and hits by what set them off; the source numbers are PreparedFocusSpeech's:
   * 0 = the focus moved, 1 = the window went quiet, 2 = the reading control changed.
   */
  private final long[] preparationRoundsBySource = new long[3];

  private final long[] preparedHitsBySource = new long[3];

  /** How often a used prepared announcement had been predicted from the previous reading order. */
  private long preparedHitsFromStaleOrder;

  /** How often a used prepared announcement had been prepared two swipes ahead. */
  private long preparedHitsSecondRing;

  /** Durations of the named segments inside one swipe, by segment name. */
  private final HashMap<String, Statistics> swipeSegmentStats = new HashMap<>();

  /** How often early speech read the focused node from the saved reading order. */
  private long speakEarlyFromSavedOrder;

  /** How often early speech had to read the focused node again from the app. */
  private long speakEarlyReRead;

  /** How often a reading order still had to be built on the thread that was swiping. */
  private long orderBuildsOnSwipePath;

  /** How often a reading order built in the background was saved for the next swipe to use. */
  private long orderSnapshotsPublished;

  /** Why the saved reading order could not be used: it had been thrown away. */
  public static final String ORDER_MISS_NO_SAVED_ORDER = "no saved order";

  /** Why the saved reading order could not be used: it belongs to another window. */
  public static final String ORDER_MISS_DIFFERENT_WINDOW = "different window";

  /** Why the saved reading order could not be used: the focused node is not in it. */
  public static final String ORDER_MISS_PIVOT_NOT_IN_ORDER = "focused node not in order";

  /** Why the saved reading order could not be used: it was kept too long after a text change. */
  public static final String ORDER_MISS_GRACE_EXPIRED = "kept too long after a text change";

  /** How often the saved reading order could not be used, by the reason it missed. */
  private final HashMap<String, Long> orderCacheMissCounts = new HashMap<>();

  /** How often a background reading-order build finished but was no longer current. */
  private long orderBuildsDiedUnpublished;

  /** How often a swipe was served by the previous reading order while a new one was built. */
  private long staleOrdersServed;

  /** How often a target taken from that previous order failed its checks and was refused. */
  private long staleTargetsRejected;

  /** How often navigation speech cut short an announcement that other speech could not cut. */
  private long navigationSpeechInterrupts;

  /** How often window-change or focus speech cut short such an announcement. */
  private long windowFocusSpeechInterrupts;

  /** How often the speech wait cap force-flushed an utterance ahead of waiting navigation. */
  private long speechWaitCapFlushes;

  /** How often the speech wait cap was held back because the blocker was exempt speech. */
  private long speechWaitCapHeld;

  /** Prepared announcements kept because a change's region spared them. */
  private long preparedKeptByRegion;

  /** Prepared announcements thrown away because a change's region touched them. */
  private long preparedEvictedByRegion;

  /** How long queued utterances waited in the speech queue before they were spoken. */
  private final Statistics speechWaitStats = new Statistics();

  /** A wait longer than this is profiled with what blocked it and what had to wait. */
  private static final long SPEECH_WAIT_PROFILE_MIN_MS = 100;

  /** How many of the longest speech waits are profiled. */
  private static final int MAX_SPEECH_WAIT_PROFILES = 10;

  /** One long speech wait: how long it lasted, what was speaking, and what had to wait. */
  private static final class SpeechWaitProfile {
    final long durationMs;
    final String blockerLabel;
    final String waiterLabel;

    SpeechWaitProfile(long durationMs, String blockerLabel, String waiterLabel) {
      this.durationMs = durationMs;
      this.blockerLabel = blockerLabel;
      this.waiterLabel = waiterLabel;
    }
  }

  /** The longest speech waits seen, longest first. */
  private final ArrayList<SpeechWaitProfile> speechWaitProfiles = new ArrayList<>();

  /** Utterances sent to the engine in pieces, cut at the first separator between their parts. */
  private long utterancesSplit;

  /** Pieces those utterances were sent in, in total. */
  private long splitPieces;

  /** Utterances played from the speech cache instead of the engine making their sound. */
  private long speechCachePlays;

  /** Prepared announcements whose sound was made in advance into the speech cache. */
  private long preparedSpeechSyntheses;

  /** How many of the slowest swipes are profiled. */
  private static final int MAX_SLOW_SWIPE_PROFILES = 10;

  /** A swipe slower than this, end to end through navigation, earns a profile. */
  private static final long SLOW_SWIPE_THRESHOLD_MS = 35;

  /** One slow swipe: how long it took, where the time went, and which paths it took. */
  private static final class SlowSwipeProfile {
    final long totalMs;
    final HashMap<String, Long> segments;
    final String orderPath;
    final String speakPath;

    SlowSwipeProfile(long totalMs, HashMap<String, Long> segments, String orderPath, String speakPath) {
      this.totalMs = totalMs;
      this.segments = segments;
      this.orderPath = orderPath;
      this.speakPath = speakPath;
    }
  }

  /** The slowest swipes seen, slowest first. */
  private final ArrayList<SlowSwipeProfile> slowSwipeProfiles = new ArrayList<>();

  /** Segment durations of the swipe being profiled, or null when no swipe is. */
  private HashMap<String, Long> activeSwipeSegments;

  /** The order path of the swipe being profiled: saved, stale, or built on the spot. */
  private String activeSwipeOrderPath;

  /** The speech-path counters as the profiled swipe began, to tell which path it took. */
  private long profilePreparedHitsBase;

  private long profileSavedOrderBase;
  private long profileReReadBase;

  private final List<LatencyTracker> latencyTrackers = new ArrayList<>();

  /**
   * Determines whether to log {@link AccessibilityAttributes} with given {@link AccessibilityEvent}
   */
  @SuppressWarnings("NonFinalStaticField")
  private static int eventMaskForLoggingAccessibilityAttributes = 0;

  private static final Performance instance = new Performance();

  /////////////////////////////////////////////////////////////////////////////////////////////
  // Construction

  public static Performance getInstance() {
    return instance;
  }

  protected Performance() {}

  /////////////////////////////////////////////////////////////////////////////////////////////
  // Simple getters/setters

  public boolean getComputeStatsEnabled() {
    return computeStatsEnabled;
  }

  public static void setEventMasksForLoggingAccessibilityAttributes(int eventMask) {
    eventMaskForLoggingAccessibilityAttributes = eventMask;
  }

  public void setComputeStatsEnabled(boolean computeStatsEnabled) {
    this.computeStatsEnabled = computeStatsEnabled;
  }

  public Statistics getAllEventStats() {
    return allEventStats;
  }

  /////////////////////////////////////////////////////////////////////////////////////////////
  // Methods to track events

  /**
   * Method to start tracking processing latency for an event. Uses event type as statistics
   * segmentation label.
   *
   * @param event An event just received by TalkBack
   * @return An event id that can be used to track performance through later stages.
   */
  public EventId onEventReceived(@NonNull AccessibilityEvent event) {
    @NonNull EventId eventId = toEventId(event);
    if (!trackEvents()) {
      return eventId;
    }

    if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
      timeInteractionStart = event.getEventTime();
    }

    // Segment events based on type.
    String label = AccessibilityEventUtils.typeToString(event.getEventType());
    String[] labels = {label};

    onEventReceived(eventId, labels, event, /* enforceTrack= */ false);
    return eventId;
  }

  public EventId onEventReceived(int motionEventSource) {
    EventId eventId = toEventId(motionEventSource);
    if (!trackEvents()) {
      return eventId;
    }

    String[] labels = {"MotionEventSource-rotary_encoder"};

    onEventReceived(eventId, labels);
    return eventId;
  }

  /**
   * Method to start tracking processing latency for a key event.
   *
   * @param event A key event just received by TalkBack
   * @return An event id that can be used to track performance through later stages.
   */
  public EventId onEventReceived(@NonNull KeyEvent event) {
    int keycode = event.getKeyCode();
    EventId eventId = toEventId(event);
    if (!trackEvents()) {
      return eventId;
    }

    // Segment key events based on key groups.
    String label = "KeyEvent-other";
    if (KeyEvent.KEYCODE_0 <= keycode && keycode <= KeyEvent.KEYCODE_9) {
      label = "KeyEvent-numeric";
    } else if (KeyEvent.KEYCODE_A <= keycode && keycode <= KeyEvent.KEYCODE_Z) {
      label = "KeyEvent-alpha";
    } else if (KeyEvent.KEYCODE_DPAD_UP <= keycode && keycode <= KeyEvent.KEYCODE_DPAD_CENTER) {
      label = "KeyEvent-dpad";
    } else if (KeyEvent.KEYCODE_VOLUME_UP <= keycode && keycode <= KeyEvent.KEYCODE_VOLUME_DOWN) {
      label = "KeyEvent-volume";
    }
    String[] labels = {label};

    onEventReceived(eventId, labels);
    return eventId;
  }

  protected void onEventReceived(@NonNull EventId eventId, String[] labels) {
    onEventReceived(eventId, labels, null, false);
  }

  protected void onEventReceived(
      @NonNull EventId eventId, String[] labels, AccessibilityEvent event, boolean enforceTrack) {
    if (!enforceTrack && !trackEvents()) {
      return;
    }

    // Create event data.
    EventData eventData = new EventData(getTime(), getUptime(), labels, eventId);
    if (event != null
        && ((event.getEventType() & eventMaskForLoggingAccessibilityAttributes) != 0)) {
      eventData.recordAccessibilityEventAttributes(event);
    }

    // Collect event data.
    addRecentEvent(eventId, eventData);
    trimRecentEvents(MAX_RECENT_EVENTS);

    if (!computeStatsEnabled) {
      return;
    }
    @StageId int prevStage = STAGE_INLINE_HANDLING - 1;
    long prevStageLatency = getUptime() - eventId.getEventTimeMs(); // Event times are uptime.
    allEventStats.increment(prevStageLatency);

    // Increment statistics for each event label.
    if (eventData.labels != null) {
      int numLabels = eventData.labels.length;
      for (int labelIndex = 0; labelIndex < numLabels; ++labelIndex) {
        String label = eventData.labels[labelIndex];
        Statistics stats = getOrCreateStatistics(label, prevStage);
        stats.increment(prevStageLatency);
      }
    }
  }

  /**
   * Method to start tracking processing latency for a gesture event. Uses event type as statistics
   * segmentation label.
   *
   * @param displayId The display where user finger touched.
   * @param event The MotionEvent which trigger the gesture detection acting. For some gesture
   *     pattern, such as TouchExplore after Split-typing, the event could be null.
   * @return An event id that can be used to track performance through later stages.
   */
  public EventId onGestureEventReceived(int displayId, @Nullable MotionEvent event) {
    EventId eventId = toEventId(displayId, event);
    String[] labels = {"GestureEvent-gesture_detection"};
    if (!trackEvents()) {
      return eventId;
    }

    onEventReceived(eventId, labels);
    onGestureDetectionStarted(eventId, (event == null) ? getUptime() : event.getEventTime());
    return eventId;
  }

  /**
   * Method to start tracking processing latency for a gesture event. Uses event type as statistics
   * segmentation label.
   *
   * @param gestureId A gesture just recognized by TalkBack
   * @return An event id that can be used to track performance through later stages.
   */
  public EventId onGestureEventReceived(int gestureId) {
    EventId eventId = new EventId(getUptime(), EVENT_TYPE_GESTURE, gestureId);
    if (!trackEvents()) {
      return eventId;
    }

    if (computeStatsEnabled) {
      logGestureDetectionDuration(gestureId);
    }
    // Segment events based on gesture id.
    String label = AccessibilityServiceCompatUtils.gestureIdToString(gestureId);
    String[] labels = {label};

    onEventReceived(eventId, labels);
    return eventId;
  }

  /**
   * Method to start tracking processing latency for a gesture event. Uses event type as statistics
   * segmentation label.
   *
   * @param hintSubType A subtype of the hint
   * @return An event id that can be used to track performance through later stages.
   */
  public EventId onHintEventReceived(@TalkBackHint int hintSubType) {
    EventId eventId = hintSubTypeToEventId(hintSubType);
    String label = TALKBACK_HINT_SUB_TYPE_NAMES.get(hintSubType);
    String[] labels = {label};

    // Hint events could be generated before the tracker is added. So we enforce to track the event.
    onEventReceived(eventId, labels, null, /* enforceTrack= */ true);
    return eventId;
  }

  /**
   * Constructs an EventId without tracking the event's times. Useful for recreating an id from an
   * event that was tracked by onEventReceived(), when the event is available but id is not. Try to
   * use this as little as possible, and instead pass the EventId from onEventReceived().
   *
   * @param event Event that has already been tracked by onEventReceived()
   * @return EventId of event
   */
  @NonNull
  public EventId toEventId(@NonNull AccessibilityEvent event) {
    return new EventId(event.getEventTime(), EVENT_TYPE_ACCESSIBILITY, event.getEventType());
  }

  @NonNull
  public EventId toEventId(@NonNull KeyEvent event) {
    return new EventId(event.getEventTime(), EVENT_TYPE_KEY, event.getKeyCode());
  }

  public EventId toEventId(int motionEventSource) {
    return new EventId(getUptime(), EVENT_TYPE_MOTION_EVENT_SOURCE, motionEventSource);
  }

  public EventId toEventId(int displayId, MotionEvent motionEvent) {
    return new EventId(getUptime(), EVENT_TYPE_GESTURE_DETECTION, 0, displayId);
  }

  public EventId hintSubTypeToEventId(@TalkBackHint int subType) {
    return new EventId(getUptime(), EVENT_TYPE_TALKBACK_HINT, subType);
  }

  private void logGestureDetectionDuration(int gestureId) {
    synchronized (lockGestureDetectionToStats) {
      Statistics statistics = gestureDetectionToStats.get(gestureId);
      if (statistics == null) {
        statistics = new Statistics();
        gestureDetectionToStats.put(gestureId, statistics);
      }
      statistics.increment(getUptime() - timeInteractionStart);
    }
  }

  /**
   * Method to start tracking processing latency for a fingerprint gesture event. Uses event type as
   * statistics segmentation label.
   *
   * @param fingerprintGestureId A fingerprint gesture just recognized by TalkBack
   * @return An event id that can be used to track performance through later stages.
   */
  public EventId onFingerprintGestureEventReceived(int fingerprintGestureId) {
    EventId eventId =
        new EventId(getUptime(), EVENT_TYPE_FINGERPRINT_GESTURE, fingerprintGestureId);
    if (!trackEvents()) {
      return eventId;
    }

    // Segment events based on fingerprint gesture id.
    String[] labels = {
      AccessibilityServiceCompatUtils.fingerprintGestureIdToString(fingerprintGestureId)
    };

    onEventReceived(eventId, labels);
    return eventId;
  }

  public EventId onKeyComboEventReceived(int keyComboId) {
    EventId eventId = new EventId(getUptime(), EVENT_TYPE_KEY_COMBO, keyComboId);
    if (!trackEvents()) {
      return eventId;
    }

    // Segment events based on key combo id.
    String label = Integer.toString(keyComboId);
    String[] labels = {label};

    onEventReceived(eventId, labels);
    return eventId;
  }

  public EventId onVolumeKeyComboEventReceived(int keyComboId) {
    EventId eventId = new EventId(getUptime(), EVENT_TYPE_VOLUME_KEY_COMBO, keyComboId);
    if (!trackEvents()) {
      return eventId;
    }

    // Segment events based on key combo id.
    String label = Integer.toString(keyComboId);
    String[] labels = {label};

    onEventReceived(eventId, labels);
    return eventId;
  }

  public EventId onRotateEventReceived(int orientation) {
    EventId eventId = new EventId(getUptime(), EVENT_TYPE_ROTATE, orientation);
    if (!trackEvents()) {
      return eventId;
    }

    // Segment events based on orientation.
    String[] labels = {orientationToSymbolicName(orientation)};

    onEventReceived(eventId, labels);
    return eventId;
  }

  /**
   * Track event latency between receiving event, and finishing synchronous event handling.
   *
   * @param eventId Identity of an event just handled by TalkBack
   */
  public void onHandlerDone(@NonNull EventId eventId) {
    // LatencyTracker doesn't need this data, so we could skip it for this case.
    if (!computeStatsEnabled) {
      return;
    }

    // If recent event not found... then labels are not available to increment statistics.
    EventData eventData = getRecentEvent(eventId);
    if (eventData == null) {
      return;
    }
    // If time already collected for this event & stage... do not update.
    if (eventData.timeInlineHandled != -1) {
      return;
    }

    // Compute stage latency.
    long now = getTime();
    eventData.timeInlineHandled = now;

    long stageLatency = now - eventData.timeReceivedAtTalkback;

    // Increment the stage latency statistics for each event label.
    if (eventData.labels != null) {
      for (String label : eventData.labels) {
        Statistics stats = getOrCreateStatistics(label, STAGE_INLINE_HANDLING);
        stats.increment(stageLatency);
      }
    }
  }

  /**
   * Track event latency between receiving event, and the spoken feedback is composed.
   *
   * @param eventId Identity of an event handled by TalkBack
   * @param firstSpokenText The text of the first fragment.
   */
  public void onFeedbackComposed(@NonNull EventId eventId, CharSequence firstSpokenText) {
    if (!trackEvents()) {
      return;
    }

    // If recent event not found... then labels are not available to increment statistics.
    EventData eventData = getRecentEvent(eventId);
    if (eventData == null) {
      return;
    }

    long now = getTime();
    eventData.setFeedbackComposed(now);
    eventData.setFirstSpkenText(firstSpokenText);
    if (!computeStatsEnabled) {
      return;
    }

    long stageLatency = now - eventData.timeReceivedAtTalkback;

    // Increment the stage latency statistics for each event label.
    if (eventData.labels != null) {
      for (String label : eventData.labels) {
        Statistics stats = getOrCreateStatistics(label, STAGE_FEEDBACK_COMPOSED);
        stats.increment(stageLatency);

        if (stageLatency > FEEDBACK_COMPOSED_THRESHOLD_MS) {
          LogUtils.d(
              TAG,
              "Feedback composed latency exceeds %s ms : %s",
              FEEDBACK_COMPOSED_THRESHOLD_MS,
              stageLatency);
        }
      }
    }
  }

  /**
   * Track event latency between receiving event, and queueing first piece of spoken feedback.
   *
   * @param eventId Identity of an event handled by TalkBack
   * @param utteranceId Identity of a piece of spoken feedback, resulting from the event.
   * @param utteranceInfo Whether to flush the playback queue in TTS and interrupt current speaking
   *     utterance.
   */
  public void onFeedbackQueued(
      @NonNull EventId eventId, @NonNull String utteranceId, UtteranceInfoCombo utteranceInfo) {
    if (!trackEvents()) {
      return;
    }

    // If recent event not found... then labels are not available to increment statistics.
    EventData eventData = getRecentEvent(eventId);
    if (eventData == null) {
      return;
    }
    // If utterance already matched with this event... do not update.
    if (eventData.getUtteranceId() != null) {
      return;
    }

    // Compute stage latency.
    long now = getTime();
    eventData.setFeedbackQueued(now, utteranceId, utteranceInfo);
    indexRecentUtterance(utteranceId, eventId);
    if (!computeStatsEnabled) {
      return;
    }

    long stageLatency = now - eventData.timeReceivedAtTalkback;
    // Increment the stage latency statistics for each event label.
    if (eventData.labels != null) {
      for (String label : eventData.labels) {
        Statistics stats = getOrCreateStatistics(label, STAGE_FEEDBACK_QUEUED);
        stats.increment(stageLatency);

        if (stageLatency > FEEDBACK_QUEUED_THRESHOLD_MS) {
          LogUtils.d(
              TAG,
              "Feedback queued latency exceeds %s ms : %s",
              FEEDBACK_QUEUED_THRESHOLD_MS,
              stageLatency);
        }
      }
    }
  }

  /**
   * Tracks event latency between receiving event, and hearing audio feedback. We also track the
   * latency between queueing first piece of spoken feedback and hearing audio feedback.
   */
  public void onFeedbackOutput(@NonNull String utteranceId, boolean localCache) {
    if (!trackEvents()) {
      return;
    }

    // If recent event not found... then labels are not available to increment statistics.
    EventId eventId = getRecentUtterance(utteranceId);
    if (eventId == null) {
      return;
    }
    EventData eventData = getRecentEvent(eventId);
    if (eventData == null) {
      return;
    }
    if (clearRecentEventForInvalidData(eventId, utteranceId, eventData)) {
      return;
    }

    if (eventData.getTimeFeedbackOutput() <= 0) {
      // Compute stage latency.
      long now = getTime();
      eventData.setFeedbackOutput(now, localCache);
      notifyLatencyTracker(latencyTracker -> latencyTracker.onFeedbackOutput(eventData));
      if (computeStatsEnabled) {
        long stageLatency = now - eventData.timeReceivedAtTalkback;

        // Increment the stage latency statistics for each event label.
        if (eventData.labels != null) {
          for (String label : eventData.labels) {
            Statistics stats = getOrCreateStatistics(label, STAGE_FEEDBACK_HEARD);
            stats.increment(stageLatency);

            if (stageLatency > FEEDBACK_HEARD_THRESHOLD_MS) {
              LogUtils.d(
                  TAG,
                  "Feedback heard latency exceeds %s ms : %s",
                  FEEDBACK_HEARD_THRESHOLD_MS,
                  stageLatency);
            }

            // Track latency between stage queued and stage heard.
            long latency = now - eventData.getTimeFeedbackQueued();
            if (latency > TTS_LATENCY_THRESHOLD_MS) {
              LogUtils.d(
                  TAG,
                  "TTS latency of %s exceeds %s ms : %s",
                  utteranceId,
                  TTS_LATENCY_THRESHOLD_MS,
                  latency);
            }
            stats = getOrCreateStatistics(label, STAGE_BETWEEN_FEEDBACK_QUEUED_AND_FEEDBACK_HEARD);
            stats.increment(latency);
          }
        }
      }
    }

    // Clear the recent event, since we have no more use for it after tracking all stages.
    if (eventData.getTimeFeedbackReady() < 0) {
      LogUtils.w(TAG, "onFeedbackReady is not set, clear the event later ");
      return;
    }
    if (!TRACKING_RANGE_START) {
      // Range start is called after onFeedbackOutput, so we need to clear the event later.
      clearEvent(eventId, utteranceId, eventData);
    }
  }

  private boolean clearRecentEventForInvalidData(
      EventId eventId, String utteranceId, EventData eventData) {
    // If speech is not already matched with this event...
    // Somehow the utteranceId of eventData is null, so we check timeFeedbackQueued to avoid
    // logging such metrics.
    if (eventData.getTimeFeedbackQueued() < 0) {
      clearEvent(eventId, utteranceId, eventData);
      return true;
    }
    return false;
  }

  private void clearEvent(EventId eventId, String utteranceId, EventData eventData) {
    removeRecentEvent(eventId);
    removeRecentUtterance(utteranceId);
    collectMissingLatencies(eventData);
  }

  /**
   * Tracks event latency between receiving event, and the {@link
   * android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction} is performed audio
   * feedback. We also track the latency between queueing first piece of spoken feedback and hearing
   * audio feedback.
   */
  public void onAccessibilityActionPerformed(
      @NonNull EventId eventId,
      int actionId,
      long actionStartUpTime,
      boolean success,
      AccessibilityNode node) {
    if (!trackEvents()) {
      return;
    }

    // If recent event not found... then labels are not available to increment statistics.
    EventData eventData = getRecentEvent(eventId);
    if (eventData == null) {
      return;
    }
    // If the data is available collected for this event & stage... do not update.
    if (eventData.actionDetails != null) {
      return;
    }

    // Compute stage latency.
    long now = getUptime();

    eventData.actionDetails =
        new AccessibilityActionDetails(
            actionId,
            now - actionStartUpTime,
            now,
            success,
            node.isTextEntryKey(),
            AccessibilityNodeInfoUtils.isKeyboard(node.getCompat()),
            node.getCompat().hashCode(),
            Role.getRole(node.getCompat()));

    notifyLatencyTracker(
        latencyTracker -> latencyTracker.onAccessibilityActionPerformed(eventData));
    if (!computeStatsEnabled) {
      return;
    }

    long stageLatency = now - eventData.uptimeReceivedAtTalkback;
    // Increment the stage latency statistics for each event label.
    if (eventData.labels != null) {
      for (String label : eventData.labels) {
        Statistics stats = getOrCreateStatistics(label, STAGE_ACTION_PERFORMED);
        stats.increment(stageLatency);
      }
    }
  }

  public void onFeedbackRangeStarted(String utteranceId) {
    if (!TRACKING_RANGE_START) {
      return;
    }
    if (!trackEvents()) {
      return;
    }

    // If recent event not found... then labels are not available to increment statistics.
    EventId eventId = getRecentUtterance(utteranceId);
    if (eventId == null) {
      return;
    }
    EventData eventData = getRecentEvent(eventId);
    if (eventData == null) {
      return;
    }

    if (clearRecentEventForInvalidData(eventId, utteranceId, eventData)) {
      return;
    }
    long now = getTime();
    if (eventData.getFeedbackRangeStartLatencyMs() > 0 || eventData.getTimeFeedbackQueued() < 0) {
      return;
    }

    eventData.setFeedbackRangeStartLatencyMs(now - eventData.getTimeFeedbackQueued());
    notifyLatencyTracker(latencyTracker -> latencyTracker.onFeedbackRangeStart(eventData));
    clearEvent(eventId, utteranceId, eventData);
  }

  public void onFeedbackReady(String utteranceId) {
    if (!trackEvents()) {
      return;
    }

    // If recent event not found... then labels are not available to increment statistics.
    EventId eventId = getRecentUtterance(utteranceId);
    if (eventId == null) {
      return;
    }
    EventData eventData = getRecentEvent(eventId);
    if (eventData == null) {
      return;
    }

    if (clearRecentEventForInvalidData(eventId, utteranceId, eventData)) {
      return;
    }
    long now = getTime();
    eventData.setTimeFeedbackReady(now);
    notifyLatencyTracker(latencyTracker -> latencyTracker.onFeedbackReady(eventData));

    // Clear the recent event when all stages are completed. we need to ensure feedbackOutput is
    // valid because Sometimes feedbackOutput is invoked before feedbackReady.
    if (eventData.getTimeFeedbackOutput() > 0 && !TRACKING_RANGE_START) {
      clearEvent(eventId, utteranceId, eventData);
    }
  }

  /**
   * Adds {@link LatencyTracker} to track the latency.
   *
   * @param latencyTracker The callback invoked when the latency is measured.
   */
  public void addLatencyTracker(LatencyTracker latencyTracker) {
    synchronized (latencyTrackers) {
      latencyTrackers.add(latencyTracker);
    }
  }

  /**
   * Removes {@link LatencyTracker}.
   *
   * @param latencyTracker The callback invoked when the latency is measured.
   */
  public void removeLatencyTracker(LatencyTracker latencyTracker) {
    synchronized (latencyTrackers) {
      latencyTrackers.remove(latencyTracker);
    }
  }

  private void notifyLatencyTracker(Consumer<LatencyTracker> consumer) {
    List<LatencyTracker> trackers;
    synchronized (latencyTrackers) {
      if (latencyTrackers.isEmpty()) {
        return;
      }
      trackers = Collections.unmodifiableList(latencyTrackers);
    }

    for (LatencyTracker tracker : trackers) {
      tracker.getExecutor().execute(() -> consumer.accept(tracker));
    }
  }

  /** Pop recent events off the queue, and increment their statistics as "missing" */
  protected void trimRecentEvents(int targetSize) {
    while (getNumRecentEvents() > targetSize) {
      EventData eventData = popOldestRecentEvent();
      if (eventData != null) {
        collectMissingLatencies(eventData);
      }
    }
  }

  /** Increment statistics for missing stages. */
  private void collectMissingLatencies(@NonNull EventData eventData) {
    // For each label x unreached stage... collect latency=missing.
    if (eventData != null && eventData.labels != null) {
      for (String label : eventData.labels) {
        if (eventData.timeInlineHandled <= 0) {
          incrementNumMissing(label, STAGE_INLINE_HANDLING);
        }
        if (eventData.getTimeFeedbackQueued() <= 0) {
          incrementNumMissing(label, STAGE_FEEDBACK_QUEUED);
        }
        if (eventData.getTimeFeedbackOutput() <= 0) {
          incrementNumMissing(label, STAGE_FEEDBACK_HEARD);
        }
      }
    }
  }

  private void incrementNumMissing(@NonNull String label, @StageId int stageId) {
    Statistics stats = getStatistics(label, stageId);
    if (stats != null) {
      stats.incrementNumMissing();
    }
  }

  /** Make clock override-able for testing. */
  protected long getTime() {
    return System.currentTimeMillis();
  }

  protected long getUptime() {
    return SystemClock.uptimeMillis();
  }

  /////////////////////////////////////////////////////////////////////////////////////////////
  // Methods to access recent event collection

  protected void addRecentEvent(@NonNull EventId eventId, @NonNull EventData eventData) {
    synchronized (lockRecentEvents) {
      eventQueue.add(eventId);
      eventIndex.put(eventId, eventData);
    }
  }

  private void indexRecentUtterance(@NonNull String utteranceId, @NonNull EventId eventId) {
    synchronized (lockRecentEvents) {
      utteranceToEvent.put(utteranceId, eventId);
    }
  }

  protected EventData getRecentEvent(@NonNull EventId eventId) {
    synchronized (lockRecentEvents) {
      return eventIndex.get(eventId);
    }
  }

  protected EventId getRecentUtterance(@NonNull String utteranceId) {
    synchronized (lockRecentEvents) {
      return utteranceToEvent.get(utteranceId);
    }
  }

  protected int getNumRecentEvents() {
    synchronized (lockRecentEvents) {
      return eventQueue.size();
    }
  }

  protected @Nullable EventData popOldestRecentEvent() {
    synchronized (lockRecentEvents) {
      if (eventQueue.isEmpty()) {
        return null;
      }
      EventId eventId = eventQueue.remove();
      EventData eventData = eventIndex.remove(eventId);
      String utteranceId = (eventData == null) ? null : eventData.getUtteranceId();
      if (utteranceId != null) {
        utteranceToEvent.remove(eventData.getUtteranceId());
      }
      return eventData;
    }
  }

  protected void removeRecentEvent(@NonNull EventId eventId) {
    synchronized (lockRecentEvents) {
      eventIndex.remove(eventId);
      eventQueue.remove(eventId);
    }
  }

  public void clearRecentEvents() {
    synchronized (lockRecentEvents) {
      eventIndex.clear();
      eventQueue.clear();
    }
  }

  protected void removeRecentUtterance(@NonNull String utteranceId) {
    synchronized (lockRecentEvents) {
      utteranceToEvent.remove(utteranceId);
    }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////
  // Methods to access latency statistics collection

  /**
   * Looks up current latency statistics for a given label and stage.
   *
   * @param label The label used for events.
   * @param stage The talkback processing {@code @StageId}
   * @return The statistics for requested label & stage, or null if no such label & stage found.
   */
  public Statistics getStatistics(@NonNull String label, @StageId int stage) {
    synchronized (lockLabelToStats) {
      StatisticsKey statsKey = new StatisticsKey(label, stage);
      return labelToStats.get(statsKey);
    }
  }

  public void clearAllStatsAndRecords(Logger logger) {
    clearAllStats();
    clearRecentEvents();
    logger.log("performance statistic is cleared");
  }

  /** Records that one queued utterance had to wait behind an utterance of {@code blockerClass}. */
  public void recordSpeechWaitBlocker(@NonNull String blockerClass) {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      speechWaitBlockerCounts.merge(blockerClass, 1L, Long::sum);
    }
  }

  public long getSpeechWaitBlockerCount(@NonNull String blockerClass) {
    synchronized (lockLabelToStats) {
      return speechWaitBlockerCounts.getOrDefault(blockerClass, 0L);
    }
  }

  /** Counts one focus spoken from a prepared announcement. */
  public void onPreparedSpeechHit() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      preparedSpeechHits++;
    }
  }

  /** Counts one round of preparing swipe announcements, by what set it off. */
  public void onPreparationRound(int source) {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      if (source >= 0 && source < preparationRoundsBySource.length) {
        preparationRoundsBySource[source]++;
      }
    }
  }

  /** Counts where a used prepared announcement came from. */
  public void onPreparedSpeechHitSource(int source, boolean fromStaleOrder, int depth) {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      if (source >= 0 && source < preparedHitsBySource.length) {
        preparedHitsBySource[source]++;
      }
      if (fromStaleOrder) {
        preparedHitsFromStaleOrder++;
      }
      if (depth >= 2) {
        preparedHitsSecondRing++;
      }
    }
  }

  /** Counts one focus that was spoken without a prepared announcement to use. */
  public void onPreparedSpeechMiss() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      preparedSpeechMisses++;
    }
  }

  /** Counts one early speech that used the node from the saved reading order as it was. */
  public void onSpeakEarlyFromSavedOrder() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      speakEarlyFromSavedOrder++;
    }
  }

  /** Counts one early speech that read the focused node again from the app first. */
  public void onSpeakEarlyReRead() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      speakEarlyReRead++;
    }
  }

  /** Displays how often focus was spoken from a prepared announcement, and how often not. */
  public void displayPreparedSpeechStats(Logger logger) {
    synchronized (lockLabelToStats) {
      if (preparedSpeechHits + preparedSpeechMisses > 0) {
        logger.log(
            "Prepared speech: %d hits, %d misses", preparedSpeechHits, preparedSpeechMisses);
      }
      if (preparedSpeechHits + speakEarlyFromSavedOrder + speakEarlyReRead > 0) {
        logger.log(
            "Speak-early paths: %d prepared, %d from the saved order, %d re-read from the app",
            preparedSpeechHits, speakEarlyFromSavedOrder, speakEarlyReRead);
      }
      if (preparedKeptByRegion + preparedEvictedByRegion > 0) {
        logger.log(
            "Prepared speech region checks: %d kept, %d evicted",
            preparedKeptByRegion, preparedEvictedByRegion);
      }
      long preparationRounds =
          preparationRoundsBySource[0]
              + preparationRoundsBySource[1]
              + preparationRoundsBySource[2];
      if (preparationRounds > 0) {
        logger.log(
            "Preparation rounds and hits by source: focus %d/%d, settle %d/%d, reading control"
                + " %d/%d; %d hits from the previous order, %d hits two swipes ahead",
            preparationRoundsBySource[0],
            preparedHitsBySource[0],
            preparationRoundsBySource[1],
            preparedHitsBySource[1],
            preparationRoundsBySource[2],
            preparedHitsBySource[2],
            preparedHitsFromStaleOrder,
            preparedHitsSecondRing);
      }
    }
  }

  /** Counts prepared announcements a change's region spared, so their preparation was not wasted. */
  public void onPreparedEntriesKeptByRegion(int count) {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      preparedKeptByRegion += count;
    }
  }

  /** Counts prepared announcements a change's region touched, which were thrown away. */
  public void onPreparedEntriesEvictedByRegion(int count) {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      preparedEvictedByRegion += count;
    }
  }

  /** Records how long one queued utterance waited in the speech queue before it was spoken. */
  public void recordSpeechWaitDuration(long durationMs) {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      speechWaitStats.increment(durationMs);
    }
  }

  /**
   * Profiles one long speech wait, naming what was speaking and what had to wait, so an export
   * can say which utterance was the blocker instead of leaving it to guesswork. Waits shorter
   * than {@link #SPEECH_WAIT_PROFILE_MIN_MS} are counted in the statistics but not profiled.
   */
  public void recordSpeechWaitProfile(
      long durationMs, @NonNull String blockerLabel, @NonNull String waiterLabel) {
    if (!computeStatsEnabled || durationMs < SPEECH_WAIT_PROFILE_MIN_MS) {
      return;
    }
    synchronized (lockLabelToStats) {
      speechWaitProfiles.add(new SpeechWaitProfile(durationMs, blockerLabel, waiterLabel));
      speechWaitProfiles.sort((a, b) -> Long.compare(b.durationMs, a.durationMs));
      while (speechWaitProfiles.size() > MAX_SPEECH_WAIT_PROFILES) {
        speechWaitProfiles.remove(speechWaitProfiles.size() - 1);
      }
    }
  }

  /** The profiled long speech waits, formatted as they are displayed, longest first. */
  public List<String> speechWaitProfileLines() {
    ArrayList<String> lines = new ArrayList<>();
    synchronized (lockLabelToStats) {
      for (SpeechWaitProfile profile : speechWaitProfiles) {
        lines.add(
            String.format(
                Locale.US,
                "Speech wait %dms: %s waited behind %s",
                profile.durationMs, profile.waiterLabel, profile.blockerLabel));
      }
    }
    return lines;
  }

  /** Counts one utterance sent to the engine in pieces, cut at its first separator. */
  public void onUtteranceSplit(int pieces) {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      utterancesSplit++;
      splitPieces += pieces;
    }
  }

  /** Counts one utterance played from the speech cache instead of the engine making its sound. */
  public void onSpeechCachePlay() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      speechCachePlays++;
    }
  }

  /** Counts one prepared announcement whose sound was made in advance into the speech cache. */
  public void onPreparedSpeechSynthesized() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      preparedSpeechSyntheses++;
    }
  }

  /** Counts one announcement cut short by navigation speech that was not allowed to wait. */
  public void onNavigationSpeechInterrupt() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      navigationSpeechInterrupts++;
    }
  }

  public long getNavigationSpeechInterruptCount() {
    synchronized (lockLabelToStats) {
      return navigationSpeechInterrupts;
    }
  }

  /** Counts one announcement cut short by window-change or focus speech. */
  public void onWindowFocusSpeechInterrupt() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      windowFocusSpeechInterrupts++;
    }
  }

  public long getWindowFocusSpeechInterruptCount() {
    synchronized (lockLabelToStats) {
      return windowFocusSpeechInterrupts;
    }
  }

  /** Counts one utterance force-flushed by the speech wait cap. */
  public void onSpeechWaitCapFlush() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      speechWaitCapFlushes++;
    }
  }

  public long getSpeechWaitCapFlushCount() {
    synchronized (lockLabelToStats) {
      return speechWaitCapFlushes;
    }
  }

  /** Counts one wait the cap deliberately did not cut because the blocker was exempt speech. */
  public void onSpeechWaitCapHeld() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      speechWaitCapHeld++;
    }
  }

  public long getSpeechWaitCapHeldCount() {
    synchronized (lockLabelToStats) {
      return speechWaitCapHeld;
    }
  }

  /** Displays how long queued utterances waited, and how often navigation speech cut a wait. */
  public void displaySpeechWaitStats(Logger logger) {
    synchronized (lockLabelToStats) {
      if (speechWaitStats.getCount() > 0) {
        logger.log(
            "Speech waits: count=%d, median=%dms, 90th percentile=%dms",
            speechWaitStats.getCount(),
            speechWaitStats.getPercentile(50),
            speechWaitStats.getPercentile(90));
      }
      if (navigationSpeechInterrupts > 0) {
        logger.log(
            "Navigation speech interrupts of uninterruptible announcements: %d",
            navigationSpeechInterrupts);
      }
      if (windowFocusSpeechInterrupts > 0) {
        logger.log(
            "Window and focus speech interrupts of uninterruptible announcements: %d",
            windowFocusSpeechInterrupts);
      }
      if (speechWaitCapFlushes > 0 || speechWaitCapHeld > 0) {
        logger.log(
            "Speech wait cap: %d waits cut short, %d held for exempt speech",
            speechWaitCapFlushes, speechWaitCapHeld);
      }
      if (utterancesSplit > 0) {
        logger.log(
            "Split utterances: %d sent in %d pieces at the first separator",
            utterancesSplit, splitPieces);
      }
      if (speechCachePlays > 0 || preparedSpeechSyntheses > 0) {
        logger.log(
            "Speech cache: %d plays, %d prepared announcements made into sound ahead",
            speechCachePlays, preparedSpeechSyntheses);
      }
    }
    for (String line : speechWaitProfileLines()) {
      logger.log(line);
    }
  }

  /** Records how long one named segment of a swipe took. */
  public void recordSwipeSegment(@NonNull String name, long durationMs) {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      Statistics stats = swipeSegmentStats.get(name);
      if (stats == null) {
        stats = new Statistics();
        swipeSegmentStats.put(name, stats);
      }
      stats.increment(durationMs);
      if (activeSwipeSegments != null) {
        activeSwipeSegments.put(name, durationMs);
      }
    }
  }

  /** Starts profiling the swipe now being navigated, until {@link #endSwipeProfile}. */
  public void beginSwipeProfile() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      activeSwipeSegments = new HashMap<>();
      activeSwipeOrderPath = "not recorded";
      profilePreparedHitsBase = preparedSpeechHits;
      profileSavedOrderBase = speakEarlyFromSavedOrder;
      profileReReadBase = speakEarlyReRead;
    }
  }

  /** Notes which order the swipe being profiled used: the saved one, a stale one, or a new build. */
  public void setSwipeProfileOrderPath(@NonNull String orderPath) {
    synchronized (lockLabelToStats) {
      if (activeSwipeSegments != null) {
        activeSwipeOrderPath = orderPath;
      }
    }
  }

  /**
   * Ends the profile of a swipe that took {@code totalMs} through navigation. Only slow swipes are
   * kept, slowest first, so an export shows what the worst swipes were made of: their segment
   * durations, the order they used, and the path their speech took.
   */
  public void endSwipeProfile(long totalMs) {
    synchronized (lockLabelToStats) {
      if (activeSwipeSegments == null) {
        return;
      }
      HashMap<String, Long> segments = activeSwipeSegments;
      String orderPath = activeSwipeOrderPath;
      activeSwipeSegments = null;
      activeSwipeOrderPath = null;
      if (totalMs < SLOW_SWIPE_THRESHOLD_MS) {
        return;
      }
      String speakPath;
      if (preparedSpeechHits > profilePreparedHitsBase) {
        speakPath = "prepared";
      } else if (speakEarlyFromSavedOrder > profileSavedOrderBase) {
        speakPath = "saved order";
      } else if (speakEarlyReRead > profileReReadBase) {
        speakPath = "re-read";
      } else {
        speakPath = "composed on the spot";
      }
      slowSwipeProfiles.add(new SlowSwipeProfile(totalMs, segments, orderPath, speakPath));
      slowSwipeProfiles.sort((a, b) -> Long.compare(b.totalMs, a.totalMs));
      while (slowSwipeProfiles.size() > MAX_SLOW_SWIPE_PROFILES) {
        slowSwipeProfiles.remove(slowSwipeProfiles.size() - 1);
      }
    }
  }

  /** Displays the profiles of the slowest swipes, slowest first. */
  public void displaySlowSwipeProfiles(Logger logger) {
    ArrayList<SlowSwipeProfile> profiles;
    synchronized (lockLabelToStats) {
      profiles = new ArrayList<>(slowSwipeProfiles);
    }
    for (SlowSwipeProfile profile : profiles) {
      StringBuilder segments = new StringBuilder();
      for (Map.Entry<String, Long> entry : new TreeMap<>(profile.segments).entrySet()) {
        if (segments.length() > 0) {
          segments.append(", ");
        }
        segments.append(entry.getKey()).append('=').append(entry.getValue());
      }
      logger.log(
          "Slow swipe %dms: %s | order: %s | speech: %s",
          profile.totalMs, segments, profile.orderPath, profile.speakPath);
    }
  }

  /** Displays the recorded swipe segment durations, one line per segment. */
  public void displaySwipeSegmentStats(Logger logger) {
    HashMap<String, Statistics> segments;
    synchronized (lockLabelToStats) {
      segments = new HashMap<>(swipeSegmentStats);
    }
    for (Map.Entry<String, Statistics> entry : new TreeMap<>(segments).entrySet()) {
      Statistics stats = entry.getValue();
      logger.log(
          "Swipe segment %s: count=%d, mean=%dms, median=%dms, 90th percentile=%dms",
          entry.getKey(),
          stats.getCount(),
          stats.getMean(),
          stats.getPercentile(50),
          stats.getPercentile(90));
    }
  }

  /** Counts one reading order built on the swiping thread because no saved order could be used. */
  public void onOrderBuildOnSwipePath() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      orderBuildsOnSwipePath++;
    }
  }

  /** Counts one reading order built in the background and saved for the next swipe. */
  public void onOrderSnapshotPublished() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      orderSnapshotsPublished++;
    }
  }

  /** Counts one time the saved reading order could not be used, by the reason it missed. */
  public void onOrderCacheMiss(@NonNull String reason) {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      orderCacheMissCounts.merge(reason, 1L, Long::sum);
    }
  }

  public long getOrderCacheMissCount(@NonNull String reason) {
    synchronized (lockLabelToStats) {
      return orderCacheMissCounts.getOrDefault(reason, 0L);
    }
  }

  /** Counts one background reading-order build that finished after its window had moved on. */
  public void onOrderBuildDied() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      orderBuildsDiedUnpublished++;
    }
  }

  public long getOrderBuildDiedCount() {
    synchronized (lockLabelToStats) {
      return orderBuildsDiedUnpublished;
    }
  }

  /** Counts one swipe served by the previous reading order while a new one was being built. */
  public void onStaleOrderServed() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      staleOrdersServed++;
    }
  }

  public long getStaleOrderServedCount() {
    synchronized (lockLabelToStats) {
      return staleOrdersServed;
    }
  }

  /** Counts one target from the previous reading order that failed its checks and was refused. */
  public void onStaleTargetRejected() {
    if (!computeStatsEnabled) {
      return;
    }
    synchronized (lockLabelToStats) {
      staleTargetsRejected++;
    }
  }

  /** Displays how many reading orders were built on the swipe path, and how many in the background. */
  public void displayOrderBuildStats(Logger logger) {
    synchronized (lockLabelToStats) {
      if (orderBuildsOnSwipePath + orderSnapshotsPublished > 0) {
        logger.log(
            "Reading order: %d builds on the swipe path, %d background builds saved",
            orderBuildsOnSwipePath, orderSnapshotsPublished);
      }
      if (!orderCacheMissCounts.isEmpty()) {
        StringBuilder misses = new StringBuilder();
        for (Map.Entry<String, Long> entry : new TreeMap<>(orderCacheMissCounts).entrySet()) {
          if (misses.length() > 0) {
            misses.append(", ");
          }
          misses.append(entry.getKey()).append('=').append(entry.getValue());
        }
        logger.log("Reading order misses: %s", misses);
      }
      if (orderBuildsDiedUnpublished > 0) {
        logger.log("Background builds that died unpublished: %d", orderBuildsDiedUnpublished);
      }
      if (staleOrdersServed + staleTargetsRejected > 0) {
        logger.log(
            "Stale orders: %d served, %d targets rejected",
            staleOrdersServed, staleTargetsRejected);
      }
    }
  }

  public void clearAllStats() {
    synchronized (lockLabelToStats) {
      labelToStats.clear();
      speechWaitBlockerCounts.clear();
      preparedSpeechHits = 0;
      preparedSpeechMisses = 0;
      Arrays.fill(preparationRoundsBySource, 0);
      Arrays.fill(preparedHitsBySource, 0);
      preparedHitsFromStaleOrder = 0;
      preparedHitsSecondRing = 0;
      swipeSegmentStats.clear();
      speakEarlyFromSavedOrder = 0;
      speakEarlyReRead = 0;
      orderBuildsOnSwipePath = 0;
      orderSnapshotsPublished = 0;
      orderCacheMissCounts.clear();
      orderBuildsDiedUnpublished = 0;
      staleOrdersServed = 0;
      staleTargetsRejected = 0;
      navigationSpeechInterrupts = 0;
      windowFocusSpeechInterrupts = 0;
      speechWaitCapFlushes = 0;
      speechWaitCapHeld = 0;
      preparedKeptByRegion = 0;
      preparedEvictedByRegion = 0;
      utterancesSplit = 0;
      splitPieces = 0;
      speechCachePlays = 0;
      preparedSpeechSyntheses = 0;
      speechWaitStats.clear();
      speechWaitProfiles.clear();
      slowSwipeProfiles.clear();
      activeSwipeSegments = null;
      activeSwipeOrderPath = null;
    }
    allEventStats.clear();

    synchronized (lockGestureDetectionToStats) {
      gestureDetectionToStats.clear();
    }
  }

  protected Statistics getOrCreateStatistics(@NonNull String label, @StageId int stage) {
    synchronized (lockLabelToStats) {
      StatisticsKey statsKey = new StatisticsKey(label, stage);
      Statistics stats = labelToStats.get(statsKey);
      if (stats == null) {
        stats = new Statistics();
        labelToStats.put(statsKey, stats);
      }
      return stats;
    }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////
  // Methods to display results

  /** Displays label-vs-label comparisons for each summary statistic. */
  public void displayStatToLabelCompare() {
    displayStatToLabelCompare(DEFAULT_LOGGER);
  }

  /** Displays label-vs-label comparisons for each summary statistic. */
  public void displayStatToLabelCompare(Logger logger) {
    display(logger, "displayStatToLabelCompare()");

    StatisticsKey[] labelsSorted = new StatisticsKey[labelToStats.size()];
    labelsSorted = labelToStats.keySet().toArray(labelsSorted);
    Arrays.sort(labelsSorted);

    ArrayList<BarInfo> barsMissing = new ArrayList<>(labelsSorted.length);
    ArrayList<BarInfo> barsCount = new ArrayList<BarInfo>(labelsSorted.length);
    ArrayList<BarInfo> barsMean = new ArrayList<BarInfo>(labelsSorted.length);
    ArrayList<BarInfo> barsMedian = new ArrayList<BarInfo>(labelsSorted.length);
    ArrayList<BarInfo> barsStdDev = new ArrayList<BarInfo>(labelsSorted.length);

    // For each label... collect summary statistics.
    for (StatisticsKey label : labelsSorted) {
      Statistics stats = labelToStats.get(label);
      barsMissing.add(new BarInfo(label.toString(), stats.getNumMissing()));
      barsCount.add(new BarInfo(label.toString(), stats.getCount()));
      barsMean.add(new BarInfo(label.toString(), stats.getMean()));
      barsMedian.add(
          new BarInfo(
              label.toString(), stats.getMedianBinStart(), (2 * stats.getMedianBinStart())));
      barsStdDev.add(new BarInfo(label.toString(), (float) stats.getStdDev()));
    }

    // For each summary statistic... display comparison bar graph.
    displayBarGraph(logger, "  ", "missing", barsMissing, /* barUnits= */ "");
    displayBarGraph(logger, "  ", "count", barsCount, /* barUnits= */ "");
    displayBarGraph(logger, "  ", "mean", barsMean, /* barUnits= */ "ms");
    displayBarGraph(logger, "  ", "median", barsMedian, /* barUnits= */ "ms");
    displayBarGraph(logger, "  ", "stddev", barsStdDev, /* barUnits= */ "ms");
  }

  /** Displays latency statistics for each label. */
  public void displayLabelToStats() {
    displayLabelToStats(DEFAULT_LOGGER);
  }

  /** Displays latency statistics for each label. */
  public void displayLabelToStats(Logger logger) {
    display(logger, "displayLabelToStats()");

    // For each label...
    StatisticsKey[] labelsSorted = new StatisticsKey[labelToStats.size()];
    labelsSorted = labelToStats.keySet().toArray(labelsSorted);
    Arrays.sort(labelsSorted);
    for (StatisticsKey labelAndStage : labelsSorted) {
      Statistics stats = labelToStats.get(labelAndStage);
      display(logger, "  %s", labelAndStage);
      displayStatistics(logger, stats);
    }

    HashMap<String, Long> waitCounts;
    synchronized (lockLabelToStats) {
      waitCounts = new HashMap<>(speechWaitBlockerCounts);
    }
    if (!waitCounts.isEmpty()) {
      display(logger, "  displaySpeechWaitBlockerCounts()");
      for (Map.Entry<String, Long> entry : new TreeMap<>(waitCounts).entrySet()) {
        display(logger, "    %s count=%s", entry.getKey(), entry.getValue());
      }
    }
  }

  public void dump(Logger logger) {
    if (!getComputeStatsEnabled()) {
      logger.log("performance statistic is not enabled");
      return;
    }
    displayLabelToStats(logger);
    displayStatToLabelCompare(logger);
    displayAllEventStats(logger);
    displayGestureDetectionStats(logger);
  }

  public void displayAllEventStats() {
    displayAllEventStats(DEFAULT_LOGGER);
  }

  public void displayAllEventStats(Logger logger) {
    display(logger, "displayAllEventStats()");
    displayStatistics(logger, allEventStats);
  }

  private void displayGestureDetectionStats(Logger logger) {
    display(logger, "displayGestureDetectionStats()");
    synchronized (lockGestureDetectionToStats) {
      for (int i = 0; i < gestureDetectionToStats.size(); ++i) {
        int gestureId = gestureDetectionToStats.keyAt(i);
        display(logger, AccessibilityServiceCompatUtils.gestureIdToString(gestureId));
        displayStatistics(logger, gestureDetectionToStats.get(gestureId));
      }
    }
  }

  @VisibleForTesting
  public boolean trackEvents() {
    return computeStatsEnabled || !latencyTrackers.isEmpty();
  }

  public static void displayStatistics(Statistics stats) {
    displayStatistics(DEFAULT_LOGGER, stats);
  }

  public static void displayStatistics(Logger logger, Statistics stats) {
    // Display summary statistics.
    display(
        logger,
        "    missing=%s, count=%s, mean=%sms, stdDev=%sms, median=%sms, 90th percentile=%sms, 99th"
            + " percentile=%sms",
        stats.getNumMissing(),
        stats.getCount(),
        stats.getMean(),
        stats.getStdDev(),
        stats.getPercentile(50),
        stats.getPercentile(90),
        stats.getPercentile(99));

    // Display latency distribution.
    ArrayList<BarInfo> bars = new ArrayList<>(stats.histogram.size());
    for (int bin = 0; bin < stats.histogram.size(); ++bin) {
      long binStart = stats.histogramBinToStartValue(bin);
      bars.add(
          new BarInfo(
              "" + binStart + "-" + (2 * binStart) + "ms", stats.histogram.get(bin).longValue()));
    }
    displayBarGraph(logger, "      ", "distribution=", bars, "count");
  }

  /**
   * Displays a bar graph.
   *
   * @param prefix Indentation to prepend to each bar line
   * @param title Title of graph
   * @param bars Series of bar labels & sizes
   * @param barUnits Units to append to each bar value
   */
  private static void displayBarGraph(
      Logger logger, String prefix, String title, ArrayList<BarInfo> bars, String barUnits) {
    if (!TextUtils.isEmpty(title)) {
      display(logger, "  %s", title);
    }

    // Find multiplier to scale bars.
    float maxValue = 0.0f;
    for (BarInfo barInfo : bars) {
      maxValue = Math.max(maxValue, barInfo.value);
    }
    float maxBarLength = 40.0f;
    float barScale = maxBarLength / maxValue;

    // For each bar... scale bar size, display bar.
    String barCharacter = "#";
    for (BarInfo barInfo : bars) {
      int barLength = (int) ((float) barInfo.value * barScale);
      String bar = repeat(barCharacter, barLength + 1);
      StringBuilder line = new StringBuilder();
      line.append(prefix + bar + " " + floatToString(barInfo.value));
      if (barInfo.rangeEnd != -1) {
        line.append("-" + floatToString(barInfo.rangeEnd));
      }
      line.append(barUnits + " for " + barInfo.label);
      display(logger, line.toString());
    }
    display(logger, "");
  }

  private static String floatToString(float value) {
    // If float is an integer... do not show fractional part of number.
    return ((int) value == value) ? formatString("%d", (int) value) : formatString("%f", value);
  }

  private static String formatString(String format, Object... args) {
    return String.format(Locale.getDefault(), format, args);
  }

  public void displayRecentEvents() {
    display("perf.mEventQueue=");
    for (EventId i : eventQueue) {
      display("\t" + i);
    }
    display("perf.mEventIndex=");
    for (EventId i : eventIndex.keySet()) {
      display("\t" + i + ":" + eventIndex.get(i));
    }
  }

  static void display(Logger logger, String format, Object... args) {
    logger.log(format, args);
  }

  private static void display(String format, Object... args) {
    LogUtils.i(TAG, format, args);
  }

  protected static String repeat(String string, int repetitions) {
    StringBuilder repeated = new StringBuilder(string.length() * repetitions);
    for (int r = 0; r < repetitions; ++r) {
      repeated.append(string);
    }
    return repeated.toString();
  }

  public @Nullable Statistics getStatisticsByLabelAndStageId(String label, @StageId int stageId) {
    StatisticsKey[] labelsSorted = new StatisticsKey[labelToStats.size()];
    labelsSorted = labelToStats.keySet().toArray(labelsSorted);

    for (StatisticsKey labelAndStage : labelsSorted) {
      if (TextUtils.equals(labelAndStage.label, label) && labelAndStage.stage == stageId) {
        return labelToStats.get(labelAndStage);
      }
    }

    return null;
  }

  /////////////////////////////////////////////////////////////////////////////////////////////
  // Inner classes for recent events

  /** Key for looking up EventData in HashMap. */
  public static class EventId {
    private final long eventTimeMs;
    @EventTypeId private final int eventType;

    /**
     * The actual event type. Could be {@link AccessibilityEvent#getEventType()} or {@link
     * AccessibilityGestureEvent#getGestureId()}.
     */
    private final int eventSubtype;

    private final int displayId;

    public EventId(long time, @EventTypeId int type, int subtype) {
      this(time, type, subtype, Display.DEFAULT_DISPLAY);
    }

    /**
     * Create a small event identifier for tracking event through processing stages.
     *
     * @param time Time in milliseconds.
     * @param type Event object type.
     * @param subtype Event object subtype from {@link AccessibilityEvent#getEventType()} or {@link
     *     AccessibilityGestureEvent#getGestureId()}.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    public EventId(long time, @EventTypeId int type, int subtype, int displayId) {
      eventTimeMs = time; // Event creation times use system uptime.
      eventType = type;
      eventSubtype = subtype;
      this.displayId = displayId;
    }

    public long getEventTimeMs() {
      return eventTimeMs;
    }

    @EventTypeId
    public int getEventType() {
      return eventType;
    }

    public int getEventSubtype() {
      return eventSubtype;
    }

    @Override
    public boolean equals(@Nullable Object otherObj) {
      if (this == otherObj) {
        return true;
      }
      if (!(otherObj instanceof EventId)) {
        return false;
      }
      EventId other = (EventId) otherObj;
      return this.eventTimeMs == other.eventTimeMs
          && this.eventType == other.eventType
          && this.eventSubtype == other.eventSubtype
          && this.displayId == other.displayId;
    }

    @Override
    public int hashCode() {
      return Objects.hash(eventTimeMs, eventType, eventSubtype);
    }

    @Override
    @SuppressLint("SwitchIntDef") // pre-existing logic
    public String toString() {
      String subtypeString =
          switch (eventType) {
            case EVENT_TYPE_ACCESSIBILITY -> AccessibilityEventUtils.typeToString(eventSubtype);
            case EVENT_TYPE_KEY -> KeyEvent.keyCodeToString(eventSubtype);
            case EVENT_TYPE_GESTURE ->
                AccessibilityServiceCompatUtils.gestureIdToString(eventSubtype);
            case EVENT_TYPE_FINGERPRINT_GESTURE ->
                AccessibilityServiceCompatUtils.fingerprintGestureIdToString(eventSubtype);
            case EVENT_TYPE_KEY_COMBO ->
                String.format(Locale.getDefault(Category.FORMAT), "KEY_COMBO_%d", eventSubtype);
            case EVENT_TYPE_ROTATE -> orientationToSymbolicName(eventSubtype);
            case EVENT_TYPE_VOLUME_KEY_COMBO ->
                String.format(
                    Locale.getDefault(Category.FORMAT), "VOLUME_KEY_COMBO_%d", eventSubtype);
            case EVENT_TYPE_MOTION_EVENT_SOURCE ->
                String.format(
                    Locale.getDefault(Category.FORMAT), "MOTION_EVENT_SOURCE_%d", eventSubtype);
            case EVENT_TYPE_TALKBACK_HINT -> TALKBACK_HINT_SUB_TYPE_NAMES.get(eventSubtype);
            default -> Integer.toString(eventSubtype);
          };
      return "type:"
          + EVENT_TYPE_NAMES.get(eventType)
          + " subtype:"
          + subtypeString
          + " displayId:"
          + displayId
          + " time:"
          + eventTimeMs;
    }
  }

  private static String orientationToSymbolicName(int orientation) {
    return switch (orientation) {
      case ORIENTATION_UNDEFINED -> "ORIENTATION_UNDEFINED";
      case ORIENTATION_LANDSCAPE -> "ORIENTATION_LANDSCAPE";
      case ORIENTATION_PORTRAIT -> "ORIENTATION_PORTRAIT";
      default -> "ORIENTATION_" + orientation;
    };
  }

  /**
   * Tracking the stage information of gesture detection. All time stamps are recorded based on
   * milli-second/
   */
  public static final class GestureEventData {
    int displayId = -1;
    int gestureId = 0;
    // The 1st event which causes the gesture detector's' state transition from idle to detecting.
    long gestureDetectionStartedTime = -1;

    /**
     * The {@link MotionEvent#getEventTime()}. The event time of the last MotionEvent that
     * contributed to the recognized gesture.
     */
    long lastMotionEventTime = -1;

    /**
     * The timestamp, in milliseconds, used to determine if a gesture should be considered complete.
     * For hold gestures, this marks the start of a potential timeout period.
     */
    long gestureDecisionTime = -1;

    /**
     * After the last Motion Event arrives, the gesture detector holds a period of time until the
     * gesture is correctly identified. For example, in the double-tap-and-hold gesture, the
     * detector has to wait ViewConfiguration#getLongPressTimeout to make sure user held his
     * finger(s) long enough.
     */
    long gestureDetectedTime = -1;

    public int getDisplayId() {
      return displayId;
    }

    public int getGestureId() {
      return gestureId;
    }

    public long getGestureDetectionStartedTime() {
      return gestureDetectionStartedTime;
    }

    public long getLastMotionEventTime() {
      return lastMotionEventTime;
    }

    public long getGestureDecisionTime() {
      return gestureDecisionTime;
    }

    public long getGestureDetectedTime() {
      return gestureDetectedTime;
    }

    @Override
    public String toString() {
      StringBuilder stringBuilder = new StringBuilder("GestureEventData {");
      stringBuilder.append("\n\tdisplayId:").append(displayId);
      stringBuilder.append("\n\tgestureId:").append(gestureId);
      stringBuilder.append("\n\tgestureDetectionStartedTime:").append(gestureDetectionStartedTime);
      stringBuilder.append("\n\tlastMotionEventTime:").append(lastMotionEventTime);
      stringBuilder.append("\n\tgestureDecisionTime:").append(gestureDecisionTime);
      stringBuilder.append("\n\tgestureDetectedTime:").append(gestureDetectedTime);
      stringBuilder.append("\n}");
      return stringBuilder.toString();
    }
  }

  void onGestureDetectionStarted(EventId eventId, long gestureDetectionStartedTime) {
    @Nullable EventData eventData = getRecentEvent(eventId);
    if (eventData == null) {
      return;
    }
    if (!trackEvents()) {
      return;
    }

    GestureEventData gestureEventData = new GestureEventData();
    gestureEventData.displayId = eventId.displayId;
    gestureEventData.gestureDetectionStartedTime = gestureDetectionStartedTime;
    eventData.gestureEventData = gestureEventData;
  }

  // Keep event-time and current time to compute latency of the framework->service transmission.
  public void onGestureLastMotionEventTime(EventId eventId, long eventTime) {
    @Nullable EventData eventData = getRecentEvent(eventId);
    if (eventData == null) {
      return;
    }
    if (!trackEvents()) {
      return;
    }
    GestureEventData gestureEventData = eventData.gestureEventData;
    gestureEventData.lastMotionEventTime = eventTime;
    gestureEventData.gestureDecisionTime = getUptime();
  }

  // Keep gestureId and current time(as onGestureDetectedTime & compute
  // targetGestureTimeout)
  public void onGestureRecognized(EventId eventId, int gestureId) {
    EventData eventData = getRecentEvent(eventId);
    if (eventData == null) {
      return;
    }
    if (!trackEvents()) {
      return;
    }
    GestureEventData gestureEventData = eventData.gestureEventData;
    gestureEventData.gestureId = gestureId;
    gestureEventData.gestureDetectedTime = getUptime();
    if (gestureEventData.lastMotionEventTime != -1
        && gestureEventData.gestureDecisionTime != -1) {
      notifyLatencyTracker(latencyTracker -> latencyTracker.onGestureRecognized(gestureEventData));
    }
    removeRecentEvent(eventId);
  }

  public void onGestureDetectionStopped(EventId eventId) {
    removeRecentEvent(eventId);
  }

  /**
   * Invoked when performing the corresponding action of the event. The action name is determined by
   * the caller.
   */
  public void onPerformAction(EventId eventId, String action) {
    @Nullable EventData eventData = getRecentEvent(eventId);
    if (eventData == null) {
      return;
    }
    if (!trackEvents()) {
      return;
    }
    eventData.setAction(action);
  }

  /** The action of changing locale. */
  @IntDef({
    CHANGE_LOCALE_UNDEFINED,
    CHANGE_LOCALE_NONE,
    CHANGE_LOCALE_SAME,
    CHANGE_LOCALE_DIFFERENT,
    CHANGE_LOCALE_SAME_LANGUAGE,
  })
  public @interface ChangeLocaleAction {}

  public static final int CHANGE_LOCALE_UNDEFINED = 0;
  public static final int CHANGE_LOCALE_NONE = 1;

  /** Changed locale is as same as the previous one */
  public static final int CHANGE_LOCALE_SAME = 2;

  /** Changed locale is different from the previous one */
  public static final int CHANGE_LOCALE_DIFFERENT = 3;

  /** Changed locale has same language with the previous one */
  public static final int CHANGE_LOCALE_SAME_LANGUAGE = 4;

  /** Tracking the stage start times for an event. */
  public static final class EventData {

    // Members set when event is received at TalkBack.
    final String[] labels;
    public final EventId eventId; // This EventData's key in mEventIndex.

    /** The timestamp retrieved from {@link System#currentTimeMillis()} when receiving the event. */
    public final long timeReceivedAtTalkback;

    /** The timestamp retrieved from {@link SystemClock#uptimeMillis()} when receiving the event. */
    public final long uptimeReceivedAtTalkback;

    long timeInlineHandled = -1;

    private long timeFeedbackComposed = -1;

    private @Nullable AccessibilityActionDetails actionDetails;
    private @Nullable GestureEventData gestureEventData;
    private @Nullable AccessibilityAttributes accessibilityAttributes;

    // Members set when feedback is queued.
    private long timeFeedbackQueued = -1;
    private String utteranceId; // Updates may come from TalkBack or TextToSpeech threads.
    private UtteranceInfoCombo utteranceInfo;
    private boolean changeToSameLocale;
    private long timeFeedbackReady = -1;
    // The latency from onFeedbackQueued to onRangeStart is called first.
    private long feedbackRangeStartLatencyMs = -1;
    private long timeFeedbackOutput = -1;
    private String action;
    private CharSequence aggregatedText;

    private boolean localCache = false;

    private EventData(
        long timeReceivedAtTalkback,
        long uptimeReceivedAtTalkback,
        String[] labels,
        EventId eventId) {
      this.labels = labels;
      this.eventId = eventId;
      this.timeReceivedAtTalkback = timeReceivedAtTalkback;
      this.uptimeReceivedAtTalkback = uptimeReceivedAtTalkback;
    }

    // Synchronized because this method may be called from a separate audio handling thread.
    synchronized void setFeedbackComposed(long timeFeedbackComposed) {
      this.timeFeedbackComposed = timeFeedbackComposed;
    }

    // Synchronized because this method may be called from a separate audio handling thread.
    synchronized void setFeedbackQueued(
        long timeFeedbackQueued, String utteranceId, UtteranceInfoCombo utteranceInfo) {
      this.timeFeedbackQueued = timeFeedbackQueued;
      this.utteranceId = utteranceId;
      this.utteranceInfo = utteranceInfo;
      this.changeToSameLocale = utteranceInfo.changeLocaleAction() == CHANGE_LOCALE_SAME;
    }

    // Synchronized because this method may be called from a separate audio handling thread.
    synchronized void setFeedbackOutput(long timeFeedbackOutput, boolean localCache) {
      this.timeFeedbackOutput = timeFeedbackOutput;
      this.localCache = localCache;
    }

    // Synchronized because this method may be called from a separate audio handling thread.
    synchronized void setTimeFeedbackReady(long timeFeedbackReady) {
      this.timeFeedbackReady = timeFeedbackReady;
    }

    // Synchronized because this method may be called from a separate audio handling thread.
    synchronized void setFeedbackRangeStartLatencyMs(long feedbackRangeStartLatencyMs) {
      this.feedbackRangeStartLatencyMs = feedbackRangeStartLatencyMs;
    }

    synchronized void setAction(String action) {
      this.action = action;
    }

    public synchronized long getTimeFeedbackComposed() {
      return timeFeedbackComposed;
    }

    public synchronized long getTimeFeedbackQueued() {
      return timeFeedbackQueued;
    }

    public synchronized String getUtteranceId() {
      return utteranceId;
    }

    public synchronized long getTimeFeedbackOutput() {
      return timeFeedbackOutput;
    }

    public synchronized UtteranceInfoCombo getUtteranceInfo() {
      return utteranceInfo;
    }

    public synchronized boolean getChangeToSameLocale() {
      return changeToSameLocale;
    }

    public synchronized AccessibilityActionDetails getActionDetails() {
      return actionDetails;
    }

    public synchronized AccessibilityAttributes getAccessibilityAttributes() {
      return accessibilityAttributes;
    }

    public synchronized @Nullable String getAction() {
      return action;
    }

    public synchronized long getTimeFeedbackReady() {
      return timeFeedbackReady;
    }

    public synchronized long getFeedbackRangeStartLatencyMs() {
      return feedbackRangeStartLatencyMs;
    }

    public synchronized int getChangeLocaleAction() {
      return utteranceInfo.changeLocaleAction();
    }

    public synchronized CharSequence getAggregatedText() {
      return aggregatedText;
    }

    public synchronized boolean isLocalCache() {
      return localCache;
    }

    @Override
    public String toString() {
      return " labels="
          + TextUtils.join(",", labels)
          + " eventId="
          + eventId
          + " uptimeReceivedAtTalkback="
          + uptimeReceivedAtTalkback
          + " timeReceivedAtTalkback="
          + timeReceivedAtTalkback
          + " mTimeFeedbackQueued="
          + timeFeedbackQueued
          + " timeFeedbackComposed="
          + timeFeedbackComposed
          + " timeFeedbackReady="
          + timeFeedbackReady
          + " timeFeedbackOutput="
          + timeFeedbackOutput
          + " feedbackRangeStartLatency="
          + feedbackRangeStartLatencyMs
          + " timeInlineHandled="
          + timeInlineHandled
          + " utteranceInfo="
          + utteranceInfo
          + String.format(" mUtteranceId=%s", utteranceId)
          + " actionDetails="
          + actionDetails
          + " action="
          + action
          + " accessibilityEventAttributes="
          + accessibilityAttributes
          + " localCache="
          + localCache;
    }

    private void recordAccessibilityEventAttributes(AccessibilityEvent event) {
      int movementGranularity = 0;
      int fromIndex = -1;
      int toIndex = -1;
      int itemCount = -1;
      if ((event.getEventType() == TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY)) {
        movementGranularity = event.getMovementGranularity();
      } else if ((event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)) {
        fromIndex = event.getFromIndex();
        toIndex = event.getToIndex();
        itemCount = event.getItemCount();
      }

      AccessibilityNodeInfo nodeInfo = event.getSource();
      if (nodeInfo != null) {
        AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.wrap(nodeInfo);
        accessibilityAttributes =
            new AccessibilityAttributes(
                fromIndex,
                toIndex,
                itemCount,
                movementGranularity,
                node.isTextEntryKey(),
                node.isFocused(),
                AccessibilityNodeInfoUtils.isKeyboard(node),
                node.isEditable(),
                node.hashCode());
      }
    }

    public synchronized void setFirstSpkenText(CharSequence aggregatedText) {
      this.aggregatedText = aggregatedText;
    }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////
  // Inner classes for latency statistics

  /** Key for looking up Statistics by event label & stage. */
  // REFERTO
  @SuppressWarnings("ComparableType")
  public static class StatisticsKey implements Comparable<Object> {
    private final String label;
    @StageId private final int stage;

    public StatisticsKey(String label, @StageId int stage) {
      this.label = label;
      this.stage = stage;
    }

    public String getLabel() {
      return label;
    }

    public int getStage() {
      return stage;
    }

    @Override
    public int compareTo(Object otherObj) {
      if (!(otherObj instanceof StatisticsKey)) {
        return 1;
      }
      if (this == otherObj) {
        return 0;
      }
      StatisticsKey other = (StatisticsKey) otherObj;
      // Compare stage.
      int stageCompare = stage - other.getStage();
      if (stageCompare != 0) {
        return stageCompare;
      }
      // Compare label.
      return label.compareTo(other.getLabel());
    }

    @Override
    public boolean equals(@Nullable Object otherObj) {
      if (!(otherObj instanceof StatisticsKey)) {
        return false;
      }
      if (this == otherObj) {
        return true;
      }
      StatisticsKey other = (StatisticsKey) otherObj;
      return this.stage == other.stage && this.label.equals(other.getLabel());
    }

    @Override
    public int hashCode() {
      return Objects.hash(label, stage);
    }

    @Override
    public String toString() {
      return label + "-" + STAGE_NAMES.get(stage);
    }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////
  // Inner classes

  /** Holds data for one bar in a bar graph. */
  protected static class BarInfo {
    public String label = "";
    public float value = 0;
    public float rangeEnd = -1;

    public BarInfo(String labelArg, float valueArg) {
      label = labelArg;
      value = valueArg;
    }

    public BarInfo(String labelArg, float valueArg, float rangeEndArg) {
      label = labelArg;
      value = valueArg;
      rangeEnd = rangeEndArg;
    }
  }

  /** A message object with a corresponding EventId, for use by deferred event handlers. */
  public static class EventIdAnd<T> {
    public final T object;
    public final @Nullable EventId eventId;

    public EventIdAnd(T objectArg, @Nullable EventId eventIdArg) {
      object = objectArg;
      eventId = eventIdArg;
    }
  }
}
