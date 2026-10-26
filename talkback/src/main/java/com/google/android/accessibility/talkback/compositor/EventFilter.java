/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.google.android.accessibility.talkback.compositor;

import static com.google.android.accessibility.talkback.compositor.CompositorConfigs.shouldEarlyAnnounceForLiftToType;

import android.app.Notification;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.style.TtsSpan;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.compositor.rule.EventTypeViewAccessibilityFocusedFeedbackRule;
import com.google.android.accessibility.talkback.flags.FeatureFlagReader;
import com.google.android.accessibility.talkback.focusmanagement.ColumnWrap;
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget;
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget.TargetType;
import com.google.android.accessibility.talkback.focusmanagement.TraversalTreeCache;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import com.google.android.accessibility.talkback.notifications.NotificationReader;
import com.google.android.accessibility.talkback.notifications.Outcome;
import com.google.android.accessibility.utils.AccessibilityEventUtils;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.SettingsUtils;
import com.google.android.accessibility.utils.TelegramChatCell;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import com.google.android.accessibility.utils.WindowUtils;
import com.google.android.accessibility.utils.input.TextEventInterpreter;
import com.google.android.accessibility.utils.monitor.TouchMonitor;
import com.google.android.accessibility.utils.monitor.VoiceActionDelegate;
import com.google.android.accessibility.utils.output.SpeechCacheManager.LoadSpeechResultNotifier;
import com.google.android.accessibility.utils.output.SpeechCachePlayer.SpeechInfo;
import com.google.android.accessibility.utils.traversal.TraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategy.SearchDirection;
import com.google.android.accessibility.utils.traversal.TraversalStrategyUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Determines whether events should be passed on to the compositor. Also interprets events into more
 * specific event types, and extracts data from events.
 */
public class EventFilter {

  private static final String TAG = "EventFilter";

  ///////////////////////////////////////////////////////////////////////////////////
  // Member variables
  private final Context context;
  private final Compositor compositor;
  private VoiceActionDelegate voiceActionDelegate;

  private AccessibilityFocusEventInterpreter accessibilityFocusEventInterpreter;
  private final GlobalVariables globalVariables;

  private final @NonNull TouchMonitor touchMonitor;
  private AccessibilityNodeInfoCompat lastHoverEnteredNode = null;
  private final EarlyFocusSpeech earlyFocusSpeech;

  /**
   * Makes sound for an announcement ahead of time, into the speech cache, and takes it out
   * again. Wired to the speech controller, whose cache the speaking path looks in first: an
   * announcement whose sound was made ahead is played from memory instead of waiting for the
   * engine to make it.
   */
  public interface PreparedSpeechSynthesizer {
    /** Asks for {@code text} at {@code pitch} to be made into sound in the speech cache. */
    void synthesize(CharSequence text, float pitch, LoadSpeechResultNotifier notifier);

    /** Takes a sound made by {@link #synthesize} out of the speech cache again. */
    void remove(SpeechInfo speechInfo);
  }

  /** Works out which node a swipe from the focused node would most likely reach. */
  public interface TargetPredictor {
    @Nullable AccessibilityNodeInfoCompat predictTarget(
        AccessibilityNodeInfoCompat pivot, @SearchDirection int searchDirection);

    /**
     * Works out which node a swipe from the focused node at the reading control now in effect
     * would most likely reach.
     */
    @Nullable AccessibilityNodeInfoCompat predictTarget(
        AccessibilityNodeInfoCompat pivot,
        @SearchDirection int searchDirection,
        @TargetType int targetType);

    /** The target type of the reading control now in effect. */
    @TargetType int currentTargetType();

    /** Whether the target last predicted came from the previous reading order. */
    default boolean lastPredictionFromStaleOrder() {
      return false;
    }
  }

  private @Nullable TargetPredictor targetPredictor;
  private @Nullable PreparedSpeechSynthesizer preparedSpeechSynthesizer;

  /**
   * Whether the announcements prepared for the next swipe also have their sound made ahead of
   * time, from the latency setting.
   */
  private static volatile boolean presynthesizePreparedSpeech = true;

  /** The most prepared announcements whose sound is kept made ahead at once. */
  private static final int MAX_SYNTHESIZED_PREPARED = 20;

  /** Sounds made ahead for prepared announcements, by their text, oldest first. */
  private final LinkedHashMap<String, SpeechInfo> synthesizedPrepared = new LinkedHashMap<>();

  /** Texts whose sound is being made now, so a preparation round does not ask twice. */
  private final Set<String> synthesesInFlight = new HashSet<>();

  /** The notifier for one sound being made ahead, learning its outcome under its text. */
  private LoadSpeechResultNotifier synthesisNotifierFor(
      String key,
      int depth,
      boolean settleSource,
      AccessibilityNodeInfoCompat node,
      boolean pipelined,
      boolean chained,
      int logicalDirection) {
    return new LoadSpeechResultNotifier() {
      @Override
      public void onFinished(@Nullable SpeechInfo speechInfo, Boolean success) {
        @Nullable SpeechInfo eldest = null;
        synchronized (synthesizedPrepared) {
          synthesesInFlight.remove(key);
          if (!Boolean.TRUE.equals(success) || speechInfo == null) {
            return;
          }
          synthesizedPrepared.remove(key);
          synthesizedPrepared.put(key, speechInfo);
          if (synthesizedPrepared.size() > MAX_SYNTHESIZED_PREPARED) {
            eldest = synthesizedPrepared.values().iterator().next();
            synthesizedPrepared.remove(eldest.text);
          }
        }
        Performance performance = Performance.getInstance();
        performance.onPreparedSpeechSynthesized(depth, settleSource);
        performance.registerPreparedSound(key, depth, pipelined, chained);
        if (!PreparedFocusSpeech.has(node)) {
          // The announcement this sound was made for has already been used or thrown away:
          // the sound arrived too late to be the thing that makes a swipe speak at once.
          performance.onPresynthCompletedLate();
        }
        if (eldest != null) {
          final SpeechInfo toRemove = eldest;
          performance.unregisterPreparedSound(toRemove.text);
          handler.post(
              () -> {
                if (preparedSpeechSynthesizer != null) {
                  preparedSpeechSynthesizer.remove(toRemove);
                }
              });
        }
        if (pipelined && speechPipelineChainEnabled && logicalDirection != 0) {
          // A pipelined sound landed: the chain keeps going one step further in the swipe's
          // direction, so a run of swipes keeps finding its sound already made. Posted to
          // the main thread, where the prediction machinery this uses lives.
          handler.post(() -> maybeChainNextSound(node, logicalDirection));
        }
      }
    };
  }
  // Whether a finger is on the screen now.
  private BooleanSupplier fingerDown = () -> false;
  // The node that accessibility focus last moved to, as far as this filter knows.
  private @Nullable AccessibilityNodeInfoCompat lastFocusedNode;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable prepareSwipeTargets = this::prepareSwipeTargets;
  private final Runnable settlePrepare = this::settlePrepareSwipeTargets;
  private final Runnable prepareSecondRing = this::prepareSecondRing;
  private final Runnable burstRetryPrepare = this::retryPrepareAfterBurst;

  /** What set off the preparation round now being scheduled; see PreparedFocusSpeech. */
  private int prepareRoundSource = PreparedFocusSpeech.SOURCE_FOCUS;

  /**
   * Whether the focus now rested on has already had its one retry of a round that planned
   * nothing; reset whenever focus lands or the reading control changes.
   */
  private boolean emptyRoundRetried;

  /** The pivot the second ring is prepared around, and the source of the round that asked. */
  private @Nullable AccessibilityNodeInfoCompat secondRingPivot;
  private int secondRingSource = PreparedFocusSpeech.SOURCE_FOCUS;

  /**
   * Whether swipe targets are prepared ahead at all: the master switch of the preparation
   * family. Every round — focus, settle, reading-control, second ring — is posted and run
   * under this flag alone; the family's other switches only change how a round that runs
   * is done. When false, no round is posted, no slice runs, and nothing of the family is
   * left running. Set from the latency settings; see {@link #setPrepareAheadEnabled}.
   */
  private static volatile boolean prepareAheadEnabled = true;

  /**
   * Whether preparation is also offered after the window goes quiet following a change, for the
   * focused node it is already at. Set from the latency settings; see {@link
   * #setPrepareOnSettle}.
   */
  private static volatile boolean prepareOnSettle = true;

  /**
   * Whether preparation reaches further, preparing the nodes two swipes away as well. Set
   * from the latency settings; see {@link #setPrepareWider}. Keeping the announcements a
   * swipe did not use is a separate mechanism with its own switch, on {@link
   * PreparedFocusSpeech}.
   */
  private static volatile boolean prepareWider = true;

  /**
   * How long after focus moves the announcements of the next and previous nodes are prepared: after
   * the app's focus event has been handled, and usually before the next swipe starts.
   */
  private static final long PREPARE_DELAY_MS = 30;

  /**
   * How long the window must stay quiet after a change before its swipe targets are prepared
   * again around the focused node: long enough that a burst of changes in a busy window arms
   * one round, not one per change.
   */
  private static final long SETTLE_DELAY_MS = 300;

  /**
   * How long after a preparation round the nodes two swipes away are prepared: outside the
   * window of the swipe that is about to come, so the cost never lands on it.
   */
  private static final long SECOND_RING_DELAY_MS = 200;

  /**
   * Whether a swipe's target that is only partly on screen is spoken before the swipe has scrolled
   * it into view, rather than from the app's focus event after it has, as TalkBack does.
   */
  private static volatile boolean speakItemsBeforeScroll = false;

  /**
   * Whether a preparation round runs in slices, yielding to the event loop between the
   * announcements it prepares, and is put off to settle time when it would run between two
   * swipes of a burst. When false, a round runs in one piece on the main thread, as before.
   */
  private static volatile boolean yieldingPrepare = true;

  /**
   * Whether the sound of the next announcement is set going as soon as the announcement before
   * it plays from a sound made ahead, instead of waiting for the next preparation round.
   */
  private static volatile boolean speechPipelineEnabled = true;

  /**
   * Whether a pipelined sound, when its own making lands, starts the sound one step further in
   * the same direction in turn — a chain that keeps the engine one item ahead of the focus for
   * as long as the swiping continues, instead of the pipeline's single step per play. The
   * chain only continues while focus is still landing (see {@link #CHAIN_FRESH_MS}), so a
   * sound nobody is about to hear is never made for a user who has stopped.
   */
  private static volatile boolean speechPipelineChainEnabled = true;

  /** How long after focus last landed the chain still counts the swiping as going on. */
  private static final long CHAIN_FRESH_MS = 1500;

  /**
   * Whether the focus a navigation action is about to set is spoken on that prediction, before
   * the action is even fired, and checked against the focus event when it comes back.
   */
  private static volatile boolean speculativeSpeechEnabled = true;

  /**
   * How soon after a focus landed a preparation round still counts as running inside the
   * landing's own handling: a round is posted {@link #PREPARE_DELAY_MS} after the focus
   * lands, so one attempted sooner than that was set off by something else while the landing
   * was still being worked through, and it waits for settle time. A round attempted later
   * runs in slices that yield the event loop between items, so it is never in the next
   * swipe's way — measured on the owner's phone, the wider window this used to be put every
   * focus round off to settle time, and focus rounds never ran at all.
   */
  private static final long BURST_DEFER_MS = PREPARE_DELAY_MS;

  /** The first wait before a round the burst governor put off is tried again. */
  private static final long BURST_RETRY_FIRST_MS = 300;

  /** The longest wait between burst-governor retries; the wait doubles up to this. */
  private static final long BURST_RETRY_MAX_MS = 1200;

  /** How long a speculation waits for the focus event that confirms or corrects it. */
  private static final long SPECULATION_WINDOW_MS = 1000;

  /** speakFocusEarlyInternal outcomes: the focus was not spoken or remembered here. */
  private static final int EARLY_NOT_HANDLED = 0;

  /** speakFocusEarlyInternal outcomes: only the focus state was updated, nothing was spoken. */
  private static final int EARLY_STATE_ONLY = 1;

  /** speakFocusEarlyInternal outcomes: the focus was spoken. */
  private static final int EARLY_SPOKEN = 2;

  /** When accessibility focus last landed, in {@link SystemClock#uptimeMillis()} time. */
  private long lastFocusMoveMs;

  /** When the focus landing before the last one happened, for cadence bucketing. */
  private long previousFocusMoveMs;

  /** The cadence bucket of the preparation round now running; see the Performance buckets. */
  private int activeRoundBucket = Performance.SWIPE_BUCKET_SINGLE;

  /** How many times in a row the burst governor has put the current round off. */
  private int burstDeferrals;

  /** The node a speculation spoke for, and when its focus action started; null when none waits. */
  private @Nullable AccessibilityNodeInfoCompat speculatedNode;
  private long speculatedActionTime;
  private boolean speculationSubmittedSpeech;

  /** One announcement a preparation round owes, worked out when the round is planned. */
  private static final class PrepareItem {
    final @Nullable AccessibilityNodeInfoCompat node;
    final @TargetType int targetType;
    final boolean fromStaleOrder;
    final int depth;
    /** Whether running this item only arms the second ring instead of preparing a node. */
    final boolean armSecondRing;

    PrepareItem(
        AccessibilityNodeInfoCompat node,
        @TargetType int targetType,
        boolean fromStaleOrder,
        int depth) {
      this.node = node;
      this.targetType = targetType;
      this.fromStaleOrder = fromStaleOrder;
      this.depth = depth;
      this.armSecondRing = false;
    }

    private PrepareItem() {
      this.node = null;
      this.targetType = NavigationTarget.TARGET_DEFAULT;
      this.fromStaleOrder = false;
      this.depth = 1;
      this.armSecondRing = true;
    }

    static PrepareItem armSecondRing() {
      return new PrepareItem();
    }
  }

  /** A preparation round as a plan of items, so it can run a slice at a time. */
  private static final class PreparePlan {
    final AccessibilityNodeInfoCompat pivot;
    final int source;
    final List<PrepareItem> items;
    int nextIndex;
    int slices;

    PreparePlan(AccessibilityNodeInfoCompat pivot, int source, List<PrepareItem> items) {
      this.pivot = pivot;
      this.source = source;
      this.items = items;
    }
  }

  /** The preparation plan still running in slices, if any; a newer round replaces it. */
  private @Nullable PreparePlan activePreparePlan;

  private final Runnable preparePlanContinuation = this::continuePreparePlan;

  /** The directions of the swipes whose targets are prepared: to the next and previous node. */
  private static final int[] SWIPE_DIRECTIONS = {
    TraversalStrategy.SEARCH_FOCUS_FORWARD, TraversalStrategy.SEARCH_FOCUS_BACKWARD
  };

  // /////////////////////////////////////////////////////////////////////////////////
  // Construction

  public EventFilter(
      Context context,
      Compositor compositor,
      @NonNull TouchMonitor touchMonitor,
      GlobalVariables globalVariables,
      EarlyFocusSpeech earlyFocusSpeech) {
    this.context = context;
    this.compositor = compositor;
    this.touchMonitor = touchMonitor;
    this.globalVariables = globalVariables;
    this.earlyFocusSpeech = earlyFocusSpeech;
    // A busy window can throw every preparation away between two focus moves; when it goes
    // quiet, preparation is offered again around the node the focus is at.
    PreparedFocusSpeech.setSettleListener(this::armSettlePrepare);
  }

  ///////////////////////////////////////////////////////////////////////////////////
  // Methods

  public void setVoiceActionDelegate(VoiceActionDelegate delegate) {
    voiceActionDelegate = delegate;
  }

  public void setTargetPredictor(@Nullable TargetPredictor predictor) {
    targetPredictor = predictor;
  }

  /** Sets who makes sound for prepared announcements ahead of time, if anyone. */
  public void setPreparedSpeechSynthesizer(@Nullable PreparedSpeechSynthesizer synthesizer) {
    preparedSpeechSynthesizer = synthesizer;
  }

  /** Sets whether prepared announcements also have their sound made ahead; see the field. */
  public static void setPresynthesizePreparedSpeech(boolean synthesize) {
    presynthesizePreparedSpeech = synthesize;
  }

  /** Sets whether swipe targets are prepared ahead at all; see the field. */
  public static void setPrepareAheadEnabled(boolean enabled) {
    prepareAheadEnabled = enabled;
  }

  /** Whether swipe targets are prepared ahead at all; see the field. */
  static boolean isPrepareAheadEnabled() {
    return prepareAheadEnabled;
  }

  /** Sets whether preparation is offered again when the window goes quiet; see the field. */
  public static void setPrepareOnSettle(boolean settle) {
    prepareOnSettle = settle;
  }

  /** Sets whether preparation reaches further, to two swipes away; see the field. */
  public static void setPrepareWider(boolean wider) {
    prepareWider = wider;
  }

  /** Sets how to tell whether a finger is on the screen, so that a swipe never waits for this. */
  public void setFingerDownSupplier(BooleanSupplier supplier) {
    fingerDown = supplier;
  }

  public void setAccessibilityFocusEventInterpreter(
      AccessibilityFocusEventInterpreter interpreter) {
    accessibilityFocusEventInterpreter = interpreter;
  }

  public void sendEvent(AccessibilityEvent event, @Nullable EventId eventId) {
    verifySpeculation(event);
    EarlyFocusMatch earlyFocus = earlyFocusSpeech.consume(event);
    if (earlyFocus == EarlyFocusMatch.SPOKEN) {
      // Spoken as soon as the focus was set, so this event only goes to the interpreter, which
      // passes it on for other uses, such as image captions.
      lastHoverEnteredNode = null;
      if (accessibilityFocusEventInterpreter != null) {
        accessibilityFocusEventInterpreter.interpret(event);
      }
      return;
    }
    if (earlyFocus == EarlyFocusMatch.STALE) {
      // Focus moved on, and was spoken, after this event was sent. Speaking it now would cut off
      // the newer focus, and its node's captions and state no longer matter.
      LogUtils.d(TAG, "Drop focus event older than the focus spoken early: %s", event);
      return;
    }

    if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
      // The focus moved without being spoken early, so the prepared announcements are of no use.
      PreparedFocusSpeech.clear();
      @Nullable AccessibilityNodeInfoCompat focused = AccessibilityEventUtils.sourceCompat(event);
      if (focused != null) {
        onFocusMoved(focused);
      }
    }

    // Update persistent state, unless the focus spoken early already did, but failed to speak.
    if (earlyFocus != EarlyFocusMatch.STATE_UPDATED) {
      globalVariables.updateStateFromEvent(event);
    }

    // Interpret event more specifically, and extract data from event.
    EventInterpretation eventInterpreted =
        new EventInterpretation(Compositor.toCompositorEvent(event));
    AccessibilityFocusEventInterpretation a11yFocusEventInterpreted =
        (accessibilityFocusEventInterpreter == null)
            ? null
            : accessibilityFocusEventInterpreter.interpret(event);
    if (a11yFocusEventInterpreted != null) {
      eventInterpreted.setEvent(a11yFocusEventInterpreted.getEvent());
      a11yFocusEventInterpreted.setIsEqualsToLastHoverEnterKeyboardEventNode(
          isEqualsToLastHoverEnteredKeyboardNode(event));
      eventInterpreted.setAccessibilityFocusInterpretation(a11yFocusEventInterpreted);
    }

    eventInterpreted.setReadOnly();

    int eventType = eventInterpreted.getEvent();
    if (eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
      // Drop accessibility-focus events based on EventState.
      // TODO: Remove this when focus management is done.
      {
        lastHoverEnteredNode = null;
        if (globalVariables.resettingSkipFocusProcessing()) {
          return;
        }
        if ((a11yFocusEventInterpreted != null)
            && a11yFocusEventInterpreted.getShouldMuteFeedback()) {
          return;
        }
      }
    } else if (eventType == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER) {
      if (globalVariables.isTapToType()
          && AccessibilityEventFeedbackUtils.isEventRelatedToKeyboardKey(event)) {
        // The key is typed without being spoken.
        return;
      }
      AccessibilityNodeInfoCompat node = AccessibilityEventUtils.sourceCompat(event);
      if (shouldEarlyAnnounceForLiftToType(context)
          && AccessibilityEventFeedbackUtils.isEventRelatedToKeyboardKey(event)
          && !Objects.equals(node, lastHoverEnteredNode)) {
        lastHoverEnteredNode = node;
      } else if (node != null) {
        lastHoverEnteredNode = node;
        // For focus fallback events, drop events with a source node.
        return;
      }
    } else if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
      // Event notification
      // REFERTO. If the user is touching on screen, skip event.
      // For toast events, the notification parcel is null. (Use event text instead.)
      Notification notification = AccessibilityEventUtils.extractNotification(event);
      // While the device is locked, a private notification's event has only its public version,
      // or none, and the notification listener reads the whole notification instead.
      if (notification == null
          && Notification.class.getName().contentEquals(String.valueOf(event.getClassName()))
          && NotificationReader.readsFromListener()) {
        LogUtils.d(TAG, "Notification without content while locked: read from the listener");
        return;
      }
      boolean isCall =
          (notification != null) && Notification.CATEGORY_CALL.equals(notification.category);
      // Read now: the state kept in GlobalVariables changes only when a setting does.
      boolean dnd = SettingsUtils.getDoNotDisturbState(context) > 0;
      boolean touching = touchMonitor.isUserTouchingScreen();
      // Do Not Disturb silences calls it doesn't let through. Toasts still answer what the user
      // just did.
      if (isCall && dnd && !NotificationReader.callAllowedDuringDnd(event, notification)) {
        LogUtils.d(TAG, "Do not announce call: DND is enabled");
        return;
      }
      if (isCall) {
        NotificationReader.noteCall(event, notification);
      }
      // Incoming calls are still announced, so that the user hears who is calling.
      if ((notification != null) && !isCall && !globalVariables.getSpeakNotifications()) {
        LogUtils.d(TAG, "Do not announce notification: disabled in settings");
        NotificationReader.noteUnread(event, notification, Outcome.SPEECH_OFF);
        return;
      }
      // Notifications are read as the notification settings say, Do Not Disturb included, and
      // those that arrive while the screen is touched are read after the touch.
      if ((notification != null) && NotificationReader.handle(event, notification, touching, dnd)) {
        return;
      }
      if ((notification != null) && (touching || (dnd && !isCall))) {
        LogUtils.d(TAG, "Do not announce notification: DND is enabled or the screen is touched");
        return;
      }
      if ((voiceActionDelegate != null)
          && voiceActionDelegate.isVoiceRecognitionActive()
          && (Role.getSourceRole(event) == Role.ROLE_TOAST)) {
        LogUtils.d(TAG, "Do not announce the toast: Voice recognition is active.");
        return;
      }
    } else if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        || eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        || eventType == AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY) {
      return; // Text-events only travel through TextEventInterpreter.
    } else if (eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
      LogUtils.d(
          TAG,
          "Do not handle TYPE_VIEW_SELECTED by ProcessEventQueue. "
              + "Handled in SelectionEventInterpreter.");
      return;
    } else {
      // Let event go to compositor.
    }

    compositor.handleEvent(event, eventId, eventInterpreted);
  }

  /**
   * Told when Backtalk has set accessibility focus on {@code node}, before the app's focus event
   * for it arrives. Speaks the focus at once if it can, see {@link #speakFocusEarly}; otherwise
   * makes sure the app's event for it is spoken.
   *
   * @param actionTime when the focus action started, in {@link SystemClock#uptimeMillis()} time
   * @param continuousReading whether continuous reading moved the focus. Its focus is not spoken
   *     early: continuous reading moves on when the speech for a node ends, and gains little from
   *     speaking sooner, so it keeps waiting for the app's event.
   */
  public void onAccessibilityFocusSet(
      AccessibilityNodeInfoCompat node,
      FocusActionInfo info,
      @Nullable EventId eventId,
      long actionTime,
      boolean continuousReading) {
    if (speculatedNode != null
        && speculatedActionTime == actionTime
        && speculatedNode.equals(node)) {
      // This focus was already spoken, or its state already updated, when its action was
      // fired, on the prediction the speculation made. Doing it again here would update the
      // focus state a second time, which the speaking path must never do. The speculation
      // stays pending: the app's focus event still has to confirm or correct it.
      return;
    }
    if (continuousReading || speakFocusEarly(node, info, eventId, actionTime) == EARLY_NOT_HANDLED) {
      // A node spoken early before, whose event never came here, must not hide this focus.
      earlyFocusSpeech.forget(node);
    }
  }

  /**
   * Speaks the focus a navigation action is about to set on {@code node}, before the action is
   * fired: the node a swipe will reach is known from the saved order while the gesture is being
   * handled, so its announcement need not wait for the action, the app's move, and the focus
   * event's round trip. When the event comes back it confirms the speculation, or the speech
   * is corrected by the event's own speaking, which interrupts it as any newer speech does.
   * Only orders this filter can vouch for are speculated from: the saved order must hold the
   * node, or its announcement must already be prepared.
   */
  public void speculateFocusSpeech(
      AccessibilityNodeInfoCompat node,
      FocusActionInfo info,
      @Nullable EventId eventId,
      long actionTime) {
    clearSpeculation();
    if (!speculationAllowed(
        speculativeSpeechEnabled,
        shouldSpeakFocusEarly(info),
        // The destination of a swipe the machinery had to scroll for is the app's choice, made
        // while scrolling; predicting it from the order is not to be trusted.
        info.navigationAction != null ? info.navigationAction.autoScrollAttempt : 0,
        TraversalTreeCache.holds(node) || PreparedFocusSpeech.has(node))) {
      return;
    }
    int outcome = speakFocusEarly(node, info, eventId, actionTime);
    if (outcome == EARLY_NOT_HANDLED) {
      return;
    }
    speculatedNode = node;
    speculatedActionTime = actionTime;
    speculationSubmittedSpeech = outcome == EARLY_SPOKEN;
    if (speculationSubmittedSpeech) {
      Performance.getInstance().onSpeculativeSpeechSubmitted();
    }
  }

  /**
   * Told that the focus action a speculation spoke for was refused by the app, so the focus
   * never moved: the speech submitted for it is wrong where it stands. Returns whether speech
   * had been submitted, so the caller can interrupt it.
   */
  public boolean onSpeculativeSpeechActionFailed(
      AccessibilityNodeInfoCompat node, long actionTime) {
    if (speculatedNode == null
        || speculatedActionTime != actionTime
        || !speculatedNode.equals(node)) {
      return false;
    }
    boolean submitted = speculationSubmittedSpeech;
    clearSpeculation();
    if (submitted) {
      Performance.getInstance().onSpeculativeSpeechCorrected();
    }
    return submitted;
  }

  /** Checks a waiting speculation against a focus event that has come back. */
  private void verifySpeculation(AccessibilityEvent event) {
    if (speculatedNode == null
        || event.getEventType() != AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
      return;
    }
    if (SystemClock.uptimeMillis() - speculatedActionTime > SPECULATION_WINDOW_MS) {
      // No event came back for it in time; whatever this event is, it is not its verdict.
      clearSpeculation();
      return;
    }
    long eventTime = event.getEventTime();
    if (eventTime > 0 && eventTime < speculatedActionTime) {
      // An event from before the action was fired is not its verdict either.
      return;
    }
    AccessibilityNodeInfoCompat source = AccessibilityEventUtils.sourceCompat(event);
    if (source == null) {
      return;
    }
    int verdict = speculationVerdict(speculationSubmittedSpeech, source.equals(speculatedNode));
    clearSpeculation();
    if (verdict == SPECULATION_CONFIRMED) {
      Performance.getInstance().onSpeculativeSpeechConfirmed();
    } else if (verdict == SPECULATION_CORRECTED) {
      Performance.getInstance().onSpeculativeSpeechCorrected();
    }
  }

  private void clearSpeculation() {
    speculatedNode = null;
    speculationSubmittedSpeech = false;
  }

  /**
   * Speaks the accessibility focus that a swipe or other user navigation just set on {@code node},
   * without waiting for the app's focus event, which takes a round trip to the app and every event
   * listener before it would be spoken. The focus event, when it arrives, is then not spoken again.
   *
   * <p>Only focus that stays in the same window is spoken early, so that window changes are still
   * announced from the event. Nodes whose descriptions need details only the event has, such as
   * keyboard keys, sliders, pages and web content, wait for their event.
   *
   * @return what was done for the focus: one of the EARLY outcomes — spoken, state updated
   *     only, or not handled, in which case the app's focus event for it is handled as usual
   */
  private int speakFocusEarly(
      AccessibilityNodeInfoCompat focusedNode,
      FocusActionInfo info,
      @Nullable EventId eventId,
      long actionTime) {
    long speakEarlyStart = SystemClock.uptimeMillis();
    int result = speakFocusEarlyInternal(focusedNode, info, eventId, actionTime);
    Performance.getInstance()
        .recordSwipeSegment("speak early", SystemClock.uptimeMillis() - speakEarlyStart);
    return result;
  }

  private int speakFocusEarlyInternal(
      AccessibilityNodeInfoCompat focusedNode,
      FocusActionInfo info,
      @Nullable EventId eventId,
      long actionTime) {
    if (!shouldSpeakFocusEarly(info)) {
      // Not spoken early, so the prepared announcement is not spent either: taking it here
      // would throw away a preparation the event path never asked this filter to make.
      return EARLY_NOT_HANDLED;
    }
    // The focus is moving, so the prepared announcements are used now or not at all.
    PreparedFocusSpeech.@Nullable Prepared prepared = PreparedFocusSpeech.take(focusedNode);
    AccessibilityNodeInfoCompat node;
    @Nullable EventFeedback preparedFeedback;
    @Nullable CharSequence preparedContainerTitle = null;
    if (prepared != null && info.columnWrap == ColumnWrap.NONE) {
      // Prepared from the node read again from the app, and thrown away had anything in its
      // window changed since, even its text or state. So it needs no second read, which would
      // wait for the app to finish drawing the focus it was just given.
      node = prepared.getNode();
      preparedFeedback = prepared.getFeedback();
      preparedContainerTitle = prepared.getContainerTitle();
    } else if (TraversalTreeCache.holdsCurrent(focusedNode)) {
      // Read from the app with the saved order, and nothing in its window has changed since, so a
      // second read would only wait for the app, which on a watch is often busy scrolling.
      Performance.getInstance().onSpeakEarlyFromSavedOrder();
      node = focusedNode;
      preparedFeedback = null;
    } else {
      // The node may come from the saved reading order, which keeps nodes for a while after their
      // text or state changes, such as a progress label or a switch the app turned on. Read it
      // again from the app, as its focus event would. If it is gone, leave it to the event.
      long reReadStart = SystemClock.uptimeMillis();
      @SuppressWarnings("deprecation") // obtain(): copy, so that the saved node is left as it was.
      AccessibilityNodeInfoCompat copy = AccessibilityNodeInfoCompat.obtain(focusedNode);
      boolean refreshed = copy.refresh();
      Performance.getInstance()
          .recordSwipeSegment("speak re-read", SystemClock.uptimeMillis() - reReadStart);
      if (!refreshed) {
        return EARLY_NOT_HANDLED;
      }
      Performance.getInstance().onSpeakEarlyReRead();
      node = copy;
      preparedFeedback = null;
    }
    if (!shouldSpeakFocusEarly(node)) {
      return EARLY_NOT_HANDLED;
    }
    AccessibilityEvent event = focusEventFor(node);
    EventInterpretation eventInterpreted = focusInterpretation(info);

    // The speech is composed from the state that follows focus, so it is updated first, and the
    // app's event must then not update it a second time, whether or not the speech succeeds:
    // moving the collection state twice would lose "list, N items" or "in table".
    globalVariables.updateStateFromFocusedNode(node);
    globalVariables.updateCollectionStateFromFocusedNode(node, event);
    lastHoverEnteredNode = null;
    try {
      EventFeedback feedback;
      if (preparedFeedback != null) {
        feedback = preparedFeedback;
        Performance.getInstance().onPreparedSpeechHit(currentSwipeBucket());
        Performance.getInstance()
            .onPreparedSpeechHitSource(
                prepared.getSource(), prepared.getFromStaleOrder(), prepared.getDepth());
        LogUtils.v(TAG, "Prepared speech hit: %s", node);
        // Working the feedback out would have moved the container title to the node's, as
        // preparing it did before putting it back.
        EventTypeViewAccessibilityFocusedFeedbackRule.currentContainerTitle =
            preparedContainerTitle;
      } else {
        Performance.getInstance().onPreparedSpeechMiss(currentSwipeBucket());
        // No node in this line: formatting it walks the node's properties on the swipe path.
        LogUtils.v(TAG, "Prepared speech miss");
        long composeStart = SystemClock.uptimeMillis();
        try {
          feedback = getSwipeTargetFeedback(event, node, eventInterpreted);
        } finally {
          Performance.getInstance()
              .recordSwipeSegment("compose", SystemClock.uptimeMillis() - composeStart);
        }
      }
      if (!hasSpeech(feedback)) {
        // Some nodes only show their content once focused, such as the second notification of a
        // collapsed group, whose text is hidden until then. Nothing to say yet means the app's
        // event, which comes after it shows the content, should say it.
        earlyFocusSpeech.addStateUpdated(node, actionTime);
        onFocusMoved(node);
        return EARLY_STATE_ONLY;
      }
      compositor.handleEvent(event, node, eventId, eventInterpreted, feedback);
      if (prepared != null
          && prepared.getDepth() == 1
          && prepared.getTargetType() == NavigationTarget.TARGET_DEFAULT) {
        // The announcement just played was a prepared one: the swipe after this one is
        // already aimed, so its sound can be set going now, while this one plays.
        maybePipelineNextSound(node, info, feedback);
      }
    } catch (RuntimeException e) {
      // Something on the way to speech needed more than a Backtalk-made event has. Leave the
      // focus to be spoken from the app's event rather than stop Backtalk.
      LogUtils.e(TAG, "Cannot speak focus early: %s", e);
      earlyFocusSpeech.addStateUpdated(node, actionTime);
      onFocusMoved(node);
      return EARLY_STATE_ONLY;
    }
    earlyFocusSpeech.addSpoken(node, actionTime);
    onFocusMoved(node);
    return EARLY_SPOKEN;
  }

  /** Sets whether items are spoken before a swipe scrolls them into view; see the field. */
  public static void setSpeakItemsBeforeScroll(boolean speak) {
    speakItemsBeforeScroll = speak;
  }

  /** Sets whether preparation rounds yield and defer in bursts; see the field. */
  public static void setYieldingPrepare(boolean yielding) {
    yieldingPrepare = yielding;
  }

  /** Whether preparation rounds yield between items and defer in bursts; see the field. */
  static boolean isYieldingPrepare() {
    return yieldingPrepare;
  }

  /** Sets whether the next sound is pipelined as the current one plays; see the field. */
  public static void setSpeechPipelineEnabled(boolean enabled) {
    speechPipelineEnabled = enabled;
  }

  /** Sets whether pipelined sounds chain one step further as they land; see the field. */
  public static void setSpeechPipelineChainEnabled(boolean enabled) {
    speechPipelineChainEnabled = enabled;
  }

  /** Sets whether focus is spoken on prediction ahead of its action; see the field. */
  public static void setSpeculativeSpeechEnabled(boolean enabled) {
    speculativeSpeechEnabled = enabled;
  }

  /**
   * Whether a speculation may be made for a focus action with this info: the same gates that
   * let a set focus be spoken early, and no scroll attempt, whose destination the app chooses
   * while scrolling rather than the order deciding it.
   */
  static boolean speculationAllowed(
      boolean enabled, boolean speakableInfo, int autoScrollAttempt, boolean orderHoldsNode) {
    return enabled && speakableInfo && autoScrollAttempt == 0 && orderHoldsNode;
  }

  /** speculationVerdict outcomes: the speculation submitted no speech, so nothing is judged. */
  static final int SPECULATION_NO_VERDICT = 0;

  /** speculationVerdict outcomes: the focus event landed on the node spoken for. */
  static final int SPECULATION_CONFIRMED = 1;

  /** speculationVerdict outcomes: the focus event landed elsewhere; the speech was wrong. */
  static final int SPECULATION_CORRECTED = 2;

  /**
   * The verdict of a focus event on a waiting speculation. A speculation that submitted no
   * speech — it only updated the focus state — has nothing to judge: the event's own speaking
   * is the only speech there is, right or wrong.
   */
  static int speculationVerdict(boolean submittedSpeech, boolean eventNodeIsSpeculated) {
    if (!submittedSpeech) {
      return SPECULATION_NO_VERDICT;
    }
    return eventNodeIsSpeculated ? SPECULATION_CONFIRMED : SPECULATION_CORRECTED;
  }

  /**
   * Whether a round starting at {@code now} would run inside the handling of the focus
   * landing that set it off: only a round the focus itself set off can be in the way, and
   * only before its own posting delay has passed; settle and reading-control rounds run when
   * nothing is landing.
   */
  static boolean shouldDeferRound(
      boolean yielding, int roundSource, long lastFocusMoveMs, long now) {
    return yielding
        && roundSource == PreparedFocusSpeech.SOURCE_FOCUS
        && lastFocusMoveMs > 0
        && now - lastFocusMoveMs < BURST_DEFER_MS;
  }

  /**
   * Whether a round that planned to its end and still planned nothing is retried once at
   * settle time: planning finds nothing when no reading order is saved yet — the predictions
   * all come from it — and the background build asked for when the screen changed lands
   * after the round, so without the retry the neighbours of the focus stay unprepared until
   * some later change happens to arm a round. A round whose planning failed partway is not
   * retried: its failure is not an order that has not landed yet.
   */
  static boolean shouldRetryEmptyRound(
      boolean planComplete, boolean planEmpty, boolean alreadyRetried) {
    return planComplete && planEmpty && !alreadyRetried;
  }

  /**
   * Whether the burst governor holds a round or a slice back right now: while
   * navigation is arriving in a burst, a slice run between two swipes is main-thread
   * work the next swipe waits behind — which is what made "on" slower than "off" on
   * the owner's phone. The governor only exists in the yielding mode that runs rounds
   * in slices; with yielding off, a round runs in one piece exactly as it always did.
   * The burst input is the navigation cadence itself, so the answer holds whatever set
   * the round off.
   */
  static boolean governorDefersRound(boolean yielding, boolean inBurst) {
    return yielding && inBurst;
  }

  /** The wait before retrying a round the burst governor put off, doubling per put-off. */
  static long burstRetryDelayMs(int deferrals) {
    long delay = BURST_RETRY_FIRST_MS;
    for (int i = 1; i < deferrals && delay < BURST_RETRY_MAX_MS; i++) {
      delay *= 2;
    }
    return Math.min(delay, BURST_RETRY_MAX_MS);
  }

  /**
   * The end (exclusive) of the slice of a plan of {@code size} items starting at {@code from}:
   * one item when yielding, so the loop turns between items; the whole rest when not, so the
   * round runs in one piece exactly as it did before plans.
   */
  static int sliceEnd(boolean yielding, int from, int size) {
    return yielding ? Math.min(from + 1, size) : size;
  }

  /**
   * Works out what focusing {@code node} by a swipe says. This comes before the swipe has scrolled
   * the node into view, so its children still off screen are described as they will be once it has.
   */
  private EventFeedback getSwipeTargetFeedback(
      AccessibilityEvent event, AccessibilityNodeInfoCompat node, EventInterpretation interpreted) {
    globalVariables.setDescribingSwipeTarget(speakItemsBeforeScroll);
    try {
      return compositor.getFeedback(event, node, interpreted);
    } finally {
      globalVariables.setDescribingSwipeTarget(false);
    }
  }

  /** The interpretation of the focus event for focus that user navigation set with {@code info}. */
  private static EventInterpretation focusInterpretation(FocusActionInfo info) {
    AccessibilityFocusEventInterpretation focusInterpretation =
        new AccessibilityFocusEventInterpretation(
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    focusInterpretation.setForceFeedbackEvenIfAudioPlaybackActive(
        info.forceFeedbackEvenIfAudioPlaybackActive());
    focusInterpretation.setForceFeedbackEvenIfMicrophoneActive(
        info.forceFeedbackEvenIfMicrophoneActive());
    focusInterpretation.setForceFeedbackEvenIfSsbActive(info.forceFeedbackEvenIfSsbActive());
    focusInterpretation.setIsNavigateByUser(true);
    focusInterpretation.setColumnWrap(info.columnWrap);
    EventInterpretation eventInterpreted =
        new EventInterpretation(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    eventInterpreted.setAccessibilityFocusInterpretation(focusInterpretation);
    eventInterpreted.setReadOnly();
    return eventInterpreted;
  }

  /** Whether focus set with {@code info} may be spoken early, before looking at its node. */
  private boolean shouldSpeakFocusEarly(FocusActionInfo info) {
    return info.sourceAction == FocusActionInfo.LOGICAL_NAVIGATION
        && info.navigationAction != null
        && !info.forceMuteFeedback
        && !globalVariables.hasSkipFocusProcessing();
  }

  /** Whether focus on {@code node} may be spoken early. */
  private boolean shouldSpeakFocusEarly(AccessibilityNodeInfoCompat node) {
    if (node.getWindowId() != globalVariables.getCurrentWindowId()) {
      return false;
    }
    int role = Role.getRole(node);
    if (role == Role.ROLE_TEXT_ENTRY_KEY
        || role == Role.ROLE_KEYBOARD_KEY
        || role == Role.ROLE_SEEK_CONTROL
        || role == Role.ROLE_PROGRESS_BAR
        || AccessibilityNodeInfoUtils.isPage(node)
        || WebInterfaceUtils.supportsWebActions(node)) {
      return false;
    }
    // A Telegram chat list row may still describe the conversation it showed before it was reused,
    // until the app fills in its focus event.
    if (TelegramChatCell.isDescribedOnFocus(node)) {
      return false;
    }
    AccessibilityWindowInfo window = AccessibilityNodeInfoUtils.getWindow(node.unwrap());
    if (AccessibilityWindowInfoUtils.getType(window)
        == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
      return false;
    }
    // An item only partly on screen, which the swipe is about to scroll into view, can have all
    // its text in children still off screen, which descriptions leave out unless they are spoken
    // as they will be after the scroll. Otherwise the app's event, after the scroll, says it.
    return speakItemsBeforeScroll || !hasChildOffScreen(node);
  }

  /** Whether a child of {@code node} is off screen. */
  private static boolean hasChildOffScreen(AccessibilityNodeInfoCompat node) {
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null && !child.isVisibleToUser()) {
        return true;
      }
    }
    return false;
  }

  /** Prepares the announcements of the nodes next to {@code node}, which focus just moved to. */
  private void onFocusMoved(AccessibilityNodeInfoCompat node) {
    lastFocusedNode = node;
    previousFocusMoveMs = lastFocusMoveMs;
    lastFocusMoveMs = SystemClock.uptimeMillis();
    emptyRoundRetried = false;
    // The saved order is told where the focus landed, so a navigation that starts from here
    // need not ask the app where the focus is.
    TraversalTreeCache.rememberFocus(node);
    prepareRoundSource = PreparedFocusSpeech.SOURCE_FOCUS;
    cancelPreparePlan();
    handler.removeCallbacks(prepareSwipeTargets);
    handler.removeCallbacks(settlePrepare);
    handler.removeCallbacks(prepareSecondRing);
    handler.removeCallbacks(burstRetryPrepare);
    burstDeferrals = 0;
    if (targetPredictor != null && prepareAheadEnabled) {
      handler.postDelayed(prepareSwipeTargets, PREPARE_DELAY_MS);
    }
  }

  /**
   * Offers a preparation round once the window has been quiet for a while after a change:
   * changes in a busy window throw preparations away faster than focus moves refill them, and
   * this is what refills them while the focus stays where it is. Called by {@link
   * PreparedFocusSpeech} on any event that may have changed the prepared window, so it only
   * arms the round; the delay is what makes a burst of changes arm one round, not one each.
   */
  private void armSettlePrepare() {
    scheduleSettlePrepare();
  }

  /**
   * Posts the settle round, when both the settle switch and the family's master switch
   * allow. Every path that wants a settle round — the quiet-window listener, the round
   * put off to settle time, the empty-round retry, a round the burst governor stopped
   * mid-plan — goes through here, so neither switch can be bypassed by posting the round
   * directly.
   */
  private void scheduleSettlePrepare() {
    if (!prepareAheadEnabled
        || !prepareOnSettle
        || targetPredictor == null
        || lastFocusedNode == null) {
      return;
    }
    handler.removeCallbacks(settlePrepare);
    handler.postDelayed(settlePrepare, SETTLE_DELAY_MS);
  }

  /** A round the burst governor put off, tried again; the governor judges it afresh. */
  private void retryPrepareAfterBurst() {
    prepareSwipeTargets();
  }

  /** The cadence bucket of a round starting now, from the focus landings' cadence. */
  private int currentSwipeBucket() {
    return Performance.bucketSinceLastSwipe(previousFocusMoveMs, lastFocusMoveMs);
  }

  /** The preparation round a quiet window asked for; the round carries the settle source. */
  private void settlePrepareSwipeTargets() {
    int roundSource = prepareRoundSource;
    prepareRoundSource = PreparedFocusSpeech.SOURCE_SETTLE;
    try {
      prepareSwipeTargets();
    } finally {
      prepareRoundSource = roundSource;
    }
  }

  /**
   * Works out the announcements of the nodes that a swipe forward or back from the focused node
   * would most likely reach, while the user listens to the focused node, so that the swipe can
   * speak one at once. Finding them also fetches them from the app, so that the swipe's own search
   * finds them already fetched.
   */
  private void prepareSwipeTargets() {
    @Nullable AccessibilityNodeInfoCompat pivot = lastFocusedNode;
    if (pivot == null || targetPredictor == null || fingerDown.getAsBoolean()) {
      // A swipe may be starting, and must not wait for this.
      return;
    }
    if (!prepareAheadEnabled) {
      // The family's master switch is off: no round runs, whatever posted this.
      return;
    }
    if (!PreparedFocusSpeech.shouldPrepare(pivot)) {
      // This window keeps throwing preparations away unused, so preparation is paused for it.
      return;
    }
    int roundSource = prepareRoundSource;
    if (governorDefersRound(yieldingPrepare, TraversalTreeCache.inNavigationBurst())) {
      // Navigation is arriving in a burst, so any slice of this round would run on the
      // main thread between two swipes, in the next swipe's way. Put the whole round off
      // and try again when the input settles, waiting longer each time the burst outlasts
      // the wait. A round only ever runs once the cadence has calmed.
      Performance.getInstance().onPrepareRoundDeferred();
      burstDeferrals++;
      handler.removeCallbacks(burstRetryPrepare);
      handler.postDelayed(burstRetryPrepare, burstRetryDelayMs(burstDeferrals));
      return;
    }
    burstDeferrals = 0;
    activeRoundBucket = currentSwipeBucket();
    if (shouldDeferRound(
        yieldingPrepare, roundSource, lastFocusMoveMs, SystemClock.uptimeMillis())) {
      // A swipe has just landed, so this round would run between two swipes of a burst and
      // make the next one wait for it. Put it off to when the window settles instead; a round
      // the settle path runs is never put off a second time.
      Performance.getInstance().onPrepareRoundDeferred();
      scheduleSettlePrepare();
      return;
    }
    Performance.getInstance().onPreparationRound(roundSource, activeRoundBucket);
    List<PrepareItem> items = new ArrayList<>();
    boolean planComplete = planFirstRingItems(pivot, items);
    if (shouldRetryEmptyRound(planComplete, items.isEmpty(), emptyRoundRetried)) {
      // The round planned nothing, most likely because no reading order is saved yet: the
      // predictions all come from it, and its background build lands after this round. Try
      // once more when the window settles, by which time the order should be there.
      emptyRoundRetried = true;
      Performance.getInstance().onPrepareRoundRetried();
      scheduleSettlePrepare();
    }
    if (planComplete && prepareWider) {
      // The nodes two swipes away are prepared a little later, off the window of the swipe
      // that is about to come; keeping the announcements this round did not use is what makes
      // them worth preparing at all.
      items.add(PrepareItem.armSecondRing());
    }
    runPreparePlan(new PreparePlan(pivot, roundSource, items));
  }

  /**
   * Works out the round around {@code pivot} as a plan of items, in the order the round has
   * always prepared them: the plain targets first, then the reading control's. Predicting is
   * the cheap half of a round; preparing is the half that can wait a turn of the event loop.
   * Returns whether the plan is whole: a prediction failing stops the planning where the
   * round itself used to stop.
   */
  private boolean planFirstRingItems(AccessibilityNodeInfoCompat pivot, List<PrepareItem> items) {
    for (int direction : SWIPE_DIRECTIONS) {
      @Nullable AccessibilityNodeInfoCompat target;
      boolean fromStaleOrder;
      try {
        target = targetPredictor.predictTarget(pivot, direction);
        fromStaleOrder = targetPredictor.lastPredictionFromStaleOrder();
      } catch (RuntimeException e) {
        LogUtils.e(TAG, "Cannot predict swipe target: %s", e);
        return false;
      }
      if (target != null && !PreparedFocusSpeech.has(target)) {
        items.add(new PrepareItem(target, NavigationTarget.TARGET_DEFAULT, fromStaleOrder, 1));
      }
    }
    // A swipe at the reading control now in effect reaches a different node than a plain swipe
    // (the next heading, say, rather than the next node), so its targets are prepared too.
    @TargetType int targetType = targetPredictor.currentTargetType();
    if (targetType != NavigationTarget.TARGET_DEFAULT) {
      for (int direction : SWIPE_DIRECTIONS) {
        @Nullable AccessibilityNodeInfoCompat target;
        boolean fromStaleOrder;
        try {
          target = targetPredictor.predictTarget(pivot, direction, targetType);
          fromStaleOrder = targetPredictor.lastPredictionFromStaleOrder();
        } catch (RuntimeException e) {
          LogUtils.e(TAG, "Cannot predict reading-control swipe target: %s", e);
          return false;
        }
        if (target != null && !PreparedFocusSpeech.has(target, targetType)) {
          items.add(new PrepareItem(target, targetType, fromStaleOrder, 1));
        }
      }
    }
    return true;
  }

  /** Starts {@code plan} as the round now running, replacing any round still in slices. */
  private void runPreparePlan(PreparePlan plan) {
    cancelPreparePlan();
    activePreparePlan = plan;
    stepPreparePlan();
  }

  /** Forgets the round still running in slices, if any: the focus it served has moved on. */
  private void cancelPreparePlan() {
    handler.removeCallbacks(preparePlanContinuation);
    activePreparePlan = null;
  }

  private void continuePreparePlan() {
    stepPreparePlan();
  }

  /**
   * Runs the next slice of the round now planned. With yielding on, a slice is one item, and
   * the rest of the round is posted to a later turn of the event loop, so a gesture or a focus
   * action waiting on this thread is never behind more than one item of preparation. With
   * yielding off the whole round runs here, in one piece, as it did before plans existed.
   * Each slice is timed as its own stretch of round: that is the stretch a swipe would wait
   * behind, which is what the round's timing exists to show.
   */
  private void stepPreparePlan() {
    PreparePlan plan = activePreparePlan;
    if (plan == null) {
      return;
    }
    if (plan.pivot != lastFocusedNode) {
      // The focus the round was planned around has moved on; its remaining items would
      // prepare announcements around a focus the user has already left.
      activePreparePlan = null;
      return;
    }
    if (!prepareAheadEnabled) {
      // Preparation was switched off mid-round: no further slice runs.
      activePreparePlan = null;
      return;
    }
    if (governorDefersRound(yieldingPrepare, TraversalTreeCache.inNavigationBurst())) {
      // A burst caught up with a round already in slices: stop here rather than run a
      // slice between two swipes. The settle path offers the round again once the
      // window is quiet, if its switch allows; the announcements already kept stay kept.
      activePreparePlan = null;
      scheduleSettlePrepare();
      return;
    }
    plan.slices++;
    long sliceStart = SystemClock.uptimeMillis();
    try {
      int sliceEnd = sliceEnd(yieldingPrepare, plan.nextIndex, plan.items.size());
      while (plan.nextIndex < sliceEnd) {
        PrepareItem item = plan.items.get(plan.nextIndex++);
        if (item.armSecondRing) {
          secondRingPivot = plan.pivot;
          secondRingSource = plan.source;
          handler.removeCallbacks(prepareSecondRing);
          handler.postDelayed(prepareSecondRing, SECOND_RING_DELAY_MS);
        } else {
          prepareRoundSource = plan.source;
          prepareFocusSpeech(item.node, item.targetType, item.fromStaleOrder, item.depth);
        }
      }
    } finally {
      Performance.getInstance()
          .onPrepareRoundDuration(SystemClock.uptimeMillis() - sliceStart, activeRoundBucket);
    }
    if (plan.nextIndex >= plan.items.size()) {
      activePreparePlan = null;
      if (yieldingPrepare && plan.slices > 1) {
        Performance.getInstance().onPrepareRoundChunked(plan.slices);
      }
      return;
    }
    handler.post(preparePlanContinuation);
  }

  /**
   * Prepares the announcements of the nodes two swipes away from the focused node, in both
   * directions and at the reading control in effect, by predicting twice in a row. Runs only
   * while the focus is still at the node the first round was prepared around.
   */
  private void prepareSecondRing() {
    @Nullable AccessibilityNodeInfoCompat pivot = secondRingPivot;
    if (pivot == null
        || pivot != lastFocusedNode
        || targetPredictor == null
        || fingerDown.getAsBoolean()
        || !prepareAheadEnabled
        || !PreparedFocusSpeech.shouldPrepare(pivot)) {
      return;
    }
    List<PrepareItem> items = new ArrayList<>();
    for (int direction : SWIPE_DIRECTIONS) {
      planSecondRingItem(pivot, direction, NavigationTarget.TARGET_DEFAULT, items);
    }
    @TargetType int targetType = targetPredictor.currentTargetType();
    if (targetType != NavigationTarget.TARGET_DEFAULT) {
      for (int direction : SWIPE_DIRECTIONS) {
        planSecondRingItem(pivot, direction, targetType, items);
      }
    }
    if (!items.isEmpty()) {
      runPreparePlan(new PreparePlan(pivot, secondRingSource, items));
    }
  }

  /** Plans the node two swipes from {@code pivot} in {@code direction}, if it is not kept. */
  private void planSecondRingItem(
      AccessibilityNodeInfoCompat pivot,
      int direction,
      @TargetType int targetType,
      List<PrepareItem> items) {
    @Nullable AccessibilityNodeInfoCompat first;
    @Nullable AccessibilityNodeInfoCompat second;
    boolean fromStaleOrder;
    try {
      first = targetPredictor.predictTarget(pivot, direction, targetType);
      if (first == null) {
        return;
      }
      second = targetPredictor.predictTarget(first, direction, targetType);
      fromStaleOrder = targetPredictor.lastPredictionFromStaleOrder();
    } catch (RuntimeException e) {
      LogUtils.e(TAG, "Cannot predict second swipe target: %s", e);
      return;
    }
    if (second != null && !PreparedFocusSpeech.has(second, targetType)) {
      items.add(new PrepareItem(second, targetType, fromStaleOrder, 2));
    }
  }

  /**
   * The reading control changed while the focus stayed where it was, so the targets the next
   * swipe would reach changed with it: prepare them around the focused node again.
   */
  public void onGranularityChanged() {
    prepareRoundSource = PreparedFocusSpeech.SOURCE_GRANULARITY;
    emptyRoundRetried = false;
    cancelPreparePlan();
    handler.removeCallbacks(prepareSwipeTargets);
    handler.removeCallbacks(settlePrepare);
    handler.removeCallbacks(prepareSecondRing);
    if (lastFocusedNode != null && targetPredictor != null && prepareAheadEnabled) {
      handler.postDelayed(prepareSwipeTargets, PREPARE_DELAY_MS);
    }
  }

  /**
   * Works out and keeps what focusing {@code focusedNode} by a swipe at a reading control whose
   * target type is {@code targetType} would say. {@code fromStaleOrder} says the node was
   * predicted from the previous reading order, and {@code depth} how many swipes away it was.
   */
  private void prepareFocusSpeech(
      AccessibilityNodeInfoCompat focusedNode,
      @TargetType int targetType,
      boolean fromStaleOrder,
      int depth) {
    // Read the node again from the app, as a swipe to it would, rather than prepare from a copy
    // that the saved reading order may have kept since before its text or state changed —
    // unless the saved order itself vouches for the node: nothing in its window has changed
    // since the order read it, not even text or state, which is the same word the speaking
    // path takes to speak from the order's node without a second read. A read the cache
    // vouches against would only fetch the values the copy already carries, and preparation
    // pays it for every target of every round.
    @SuppressWarnings("deprecation") // obtain(): copy, so that the saved node is left as it was.
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.obtain(focusedNode);
    if (!TraversalTreeCache.holdsCurrent(focusedNode) && !node.refresh()) {
      return;
    }
    if (globalVariables.hasSkipFocusProcessing() || !shouldSpeakFocusEarly(node)) {
      return;
    }
    AccessibilityEvent event = focusEventFor(node);
    FocusActionInfo info =
        FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build();
    // Working it out moves the focus state to the node, as a swipe would, so put it back after.
    GlobalVariables.SavedFocusState saved = globalVariables.saveFocusState();
    EventFeedback feedback;
    CharSequence containerTitle;
    try {
      globalVariables.updateStateFromFocusedNode(node);
      globalVariables.updateCollectionStateFromFocusedNode(node, event);
      feedback = getSwipeTargetFeedback(event, node, focusInterpretation(info));
      containerTitle = EventTypeViewAccessibilityFocusedFeedbackRule.currentContainerTitle;
    } catch (RuntimeException e) {
      LogUtils.e(TAG, "Cannot prepare focus speech: %s", e);
      return;
    } finally {
      globalVariables.restoreFocusState(saved);
    }
    if (hasSpeech(feedback)) {
      PreparedFocusSpeech.put(
          node, feedback, containerTitle, targetType, prepareRoundSource, fromStaleOrder, depth);
      maybeSynthesizePrepared(feedback, targetType, depth, node);
    }
  }

  /**
   * Asks for the sound of a prepared announcement to be made ahead of time, so a swipe to it
   * plays at once. Only plain swipe targets one swipe away: announcements for a reading control
   * are prepared too, but their sound is not made ahead, as they are reached less often and
   * every sound kept costs memory. The announcements two swipes away keep their prepared text
   * but no longer get sound made: measured on the owner's phone, the sounds made for them were
   * almost never the ones played, while each one held the speech engine for as long as a sound
   * one swipe away. Preparation rounds that run when the screen settles come through here as
   * well, so their targets get sound too. A sound is keyed by its text as the speaking path
   * will look for it, so nothing is asked for twice while it is kept or being made.
   */
  /** Whether a prepared announcement at {@code depth} is stable enough to make sound for. */
  static boolean shouldSynthesizePrepared(
      boolean enabled, @TargetType int targetType, int depth) {
    return enabled && targetType == NavigationTarget.TARGET_DEFAULT && depth == 1;
  }

  private void maybeSynthesizePrepared(
      EventFeedback feedback,
      @TargetType int targetType,
      int depth,
      AccessibilityNodeInfoCompat node) {
    if (!shouldSynthesizePrepared(presynthesizePreparedSpeech, targetType, depth)
        || preparedSpeechSynthesizer == null) {
      return;
    }
    synthesizePreparedSound(
        feedback,
        depth,
        prepareRoundSource == PreparedFocusSpeech.SOURCE_SETTLE,
        node,
        /* pipelined= */ false,
        /* chained= */ false,
        /* logicalDirection= */ 0);
  }

  /**
   * Asks for the sound of {@code feedback} to be made ahead, under its text. A sound already
   * kept or already being made is not asked for twice, and the in-flight cap is the same one
   * the kept sounds live under, so asking never queues more engine work than the cache holds.
   */
  private void synthesizePreparedSound(
      EventFeedback feedback,
      int depth,
      boolean settleSource,
      AccessibilityNodeInfoCompat node,
      boolean pipelined,
      boolean chained,
      int logicalDirection) {
    if (preparedSpeechSynthesizer == null) {
      return;
    }
    CharSequence text = feedback.ttsOutput().orElse(null);
    if (TextUtils.isEmpty(text) || hasTtsSpan(text)) {
      return;
    }
    String key = text.toString();
    synchronized (synthesizedPrepared) {
      if (synthesizedPrepared.containsKey(key)
          || synthesesInFlight.contains(key)
          || synthesesInFlight.size() >= MAX_SYNTHESIZED_PREPARED) {
        return;
      }
      synthesesInFlight.add(key);
    }
    if (chained) {
      Performance.getInstance().onSpeechPipelineChainedStarted();
    } else if (pipelined) {
      Performance.getInstance().onSpeechPipelineStarted();
    }
    preparedSpeechSynthesizer.synthesize(
        text,
        (float) feedback.ttsPitch(),
        synthesisNotifierFor(key, depth, settleSource, node, pipelined, chained, logicalDirection));
  }

  /**
   * Sets the sound of the next announcement going while the current one plays. The current
   * announcement only counts when its own sound was made ahead — that is the case where the
   * speech engine is free right now, its serial queue holding nothing of this swipe — and the
   * next announcement only counts when its text is already prepared, so all that is missing
   * is its sound. The neighbor is the target one more swipe in the direction just swiped
   * would reach, the same prediction the preparation rounds make.
   */
  private void maybePipelineNextSound(
      AccessibilityNodeInfoCompat focusedNode, FocusActionInfo info, EventFeedback playedFeedback) {
    if (!speechPipelineEnabled || preparedSpeechSynthesizer == null || targetPredictor == null) {
      return;
    }
    CharSequence playedText = playedFeedback.ttsOutput().orElse(null);
    if (TextUtils.isEmpty(playedText) || hasTtsSpan(playedText)) {
      return;
    }
    synchronized (synthesizedPrepared) {
      if (!synthesizedPrepared.containsKey(playedText.toString())) {
        // The current announcement is being made by the engine as it plays; starting another
        // sound now would only queue behind it.
        return;
      }
    }
    if (info.navigationAction == null) {
      return;
    }
    int logicalDirection =
        TraversalStrategyUtils.getLogicalDirection(
            info.navigationAction.searchDirection, WindowUtils.isScreenLayoutRTL(context));
    if (logicalDirection != TraversalStrategy.SEARCH_FOCUS_FORWARD
        && logicalDirection != TraversalStrategy.SEARCH_FOCUS_BACKWARD) {
      return;
    }
    @Nullable AccessibilityNodeInfoCompat next;
    try {
      next = targetPredictor.predictTarget(focusedNode, logicalDirection);
    } catch (RuntimeException e) {
      LogUtils.e(TAG, "Cannot predict pipelined swipe target: %s", e);
      return;
    }
    if (next == null) {
      return;
    }
    PreparedFocusSpeech.@Nullable Prepared neighbor =
        PreparedFocusSpeech.peek(next, NavigationTarget.TARGET_DEFAULT);
    if (neighbor == null) {
      return;
    }
    synthesizePreparedSound(
        neighbor.getFeedback(),
        1,
        false,
        next,
        /* pipelined= */ true,
        /* chained= */ false,
        logicalDirection);
  }

  /**
   * Starts the sound one step further than a pipelined sound that just landed, in the same
   * direction, while the swiping is still going on: the pipelined sound was made for the item
   * after the one playing, and this one is for the item after that, so the engine stays ahead
   * of the focus through a run of swipes instead of for a single step. The freshness gate is
   * the chain's brake, besides the kept-sounds cap and the same-text checks every synthesis
   * passes: once focus has stopped landing, no further sound is started.
   */
  private void maybeChainNextSound(
      AccessibilityNodeInfoCompat fromNode, int logicalDirection) {
    if (!speechPipelineChainEnabled
        || preparedSpeechSynthesizer == null
        || targetPredictor == null) {
      return;
    }
    if (lastFocusMoveMs <= 0
        || SystemClock.uptimeMillis() - lastFocusMoveMs > CHAIN_FRESH_MS) {
      return;
    }
    @Nullable AccessibilityNodeInfoCompat next;
    try {
      next = targetPredictor.predictTarget(fromNode, logicalDirection);
    } catch (RuntimeException e) {
      LogUtils.e(TAG, "Cannot predict chained swipe target: %s", e);
      return;
    }
    if (next == null) {
      return;
    }
    PreparedFocusSpeech.@Nullable Prepared neighbor =
        PreparedFocusSpeech.peek(next, NavigationTarget.TARGET_DEFAULT);
    if (neighbor == null) {
      return;
    }
    synthesizePreparedSound(
        neighbor.getFeedback(),
        1,
        false,
        next,
        /* pipelined= */ true,
        /* chained= */ true,
        logicalDirection);
  }

  /** Whether {@code text} carries a TtsSpan, whose sound the speech cache cannot make. */
  private static boolean hasTtsSpan(CharSequence text) {
    return text instanceof Spannable spannable
        && spannable.getSpans(0, text.length(), TtsSpan.class).length > 0;
  }

  /** Whether {@code feedback} says anything. */
  private static boolean hasSpeech(EventFeedback feedback) {
    return !TextUtils.isEmpty(feedback.ttsOutput().orElse(null));
  }

  /** Makes the focus event the app would send for {@code node}, without its source. */
  @SuppressWarnings("deprecation") // AccessibilityEvent(int) needs Android 11.
  private static AccessibilityEvent focusEventFor(AccessibilityNodeInfoCompat node) {
    AccessibilityEvent event =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    event.setEventTime(SystemClock.uptimeMillis());
    event.setClassName(node.getClassName());
    event.setPackageName(node.getPackageName());
    event.setContentDescription(node.getContentDescription());
    if (node.getText() != null) {
      event.getText().add(node.getText());
    }
    event.setEnabled(node.isEnabled());
    event.setChecked(node.isChecked());
    event.setPassword(node.isPassword());
    event.setScrollable(node.isScrollable());
    return event;
  }

  /** Passes text-event-interpretation to compositor. */
  public void accept(TextEventInterpreter.Interpretation textEventInterpreted) {
    AccessibilityEvent event = textEventInterpreted.event;
    globalVariables.setLastTextEditIsPassword(event.isPassword());

    int eventType =
        (textEventInterpreted.interpretation == null)
            ? Compositor.toCompositorEvent(event)
            : Compositor.toCompositorEvent(textEventInterpreted.interpretation.getEvent());
    EventInterpretation eventInterpreted = new EventInterpretation(eventType);
    eventInterpreted.setTextEventInterpretation(textEventInterpreted.interpretation);
    eventInterpreted.setPackageName(event.getPackageName());
    eventInterpreted.setReadOnly();

    compositor.handleEvent(event, textEventInterpreted.eventId, eventInterpreted);
  }

  private boolean isEqualsToLastHoverEnteredKeyboardNode(@Nullable AccessibilityEvent event) {
    if (!FeatureFlagReader.enableEarlyAnnounceForLiftToType(context)
        || !AccessibilityEventFeedbackUtils.isEventRelatedToKeyboardKey(event)) {
      return false;
    }
    return Objects.equals(lastHoverEnteredNode, AccessibilityEventUtils.sourceCompat(event));
  }
}
