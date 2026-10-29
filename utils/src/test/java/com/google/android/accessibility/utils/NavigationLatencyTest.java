package com.google.android.accessibility.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import com.google.android.accessibility.utils.Performance.EventId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NavigationLatencyTest {
  private final AtomicLong now = new AtomicLong(1000);
  private final NavigationLatency latency = new NavigationLatency(now::get);
  private final EventId gesture = new EventId(1000, Performance.EVENT_TYPE_GESTURE,
      AccessibilityService.GESTURE_SWIPE_RIGHT);

  private String report() {
    StringBuilder out = new StringBuilder();
    latency.display((format, args) -> out.append(String.format(format, args)).append('\n'));
    return out.toString();
  }

  @Test
  public void asynchronousScrollAndFocus_keepTheOriginalGestureStart() {
    latency.start(gesture);
    EventId scroll = new EventId(1700, Performance.EVENT_TYPE_ACCESSIBILITY,
        AccessibilityEvent.TYPE_VIEW_SCROLLED);
    EventId focus = new EventId(1800, Performance.EVENT_TYPE_ACCESSIBILITY,
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    now.set(1700);
    latency.link(scroll, gesture);
    now.set(1800);
    latency.link(focus, scroll);
    now.set(1880);
    latency.composed(focus);
    latency.queued(focus, "speech");
    latency.audio("speech", NavigationLatency.Stage.FIRST_PCM, 1900);
    latency.audio("speech", NavigationLatency.Stage.AUDIO_SUBMITTED, 1930);
    String output = report();
    assertTrue(output.contains("NAVIGATION_GESTURE_SWIPE_RIGHT/FIRST_PCM\n    missing=0, count=1, mean=900ms"));
    assertTrue(output.contains("NAVIGATION_GESTURE_SWIPE_RIGHT/AUDIO_SUBMITTED\n    missing=0, count=1, mean=930ms"));
  }

  @Test
  public void repeatedCallbacksAndLaterFragments_doNotCountTwice() {
    latency.start(gesture);
    latency.queued(gesture, "first");
    latency.queued(gesture, "second");
    latency.audio("first", NavigationLatency.Stage.FIRST_PCM, 1040);
    latency.audio("first", NavigationLatency.Stage.FIRST_PCM, 1050);
    latency.audio("second", NavigationLatency.Stage.FIRST_PCM, 1100);
    assertTrue(report().contains("FIRST_PCM\n    missing=0, count=1, mean=40ms"));
  }

  @Test
  public void unrelatedSpeechAndClearedLinks_areNotAttributedToTheLatestSwipe() {
    latency.start(gesture);
    EventId unrelated = new EventId(1100, Performance.EVENT_TYPE_ACCESSIBILITY,
        AccessibilityEvent.TYPE_ANNOUNCEMENT);
    latency.queued(unrelated, "announcement");
    latency.audio("announcement", NavigationLatency.Stage.FIRST_PCM, 1200);
    assertFalse(report().contains("FIRST_PCM"));
    latency.queued(gesture, "swipe");
    latency.clearLinks();
    latency.audio("swipe", NavigationLatency.Stage.FIRST_PCM, 1300);
    assertFalse(report().contains("FIRST_PCM"));
    latency.reset();
    assertFalse(report().contains("NAVIGATION_GESTURE"));
  }

  @Test
  public void deliberateGateWaitIsProcessingTimeNotFrameworkDeliveryTime() {
    now.set(1500);
    Performance performance = new Performance() {
      @Override protected long getUptime() { return now.get(); }
      @Override protected long getTime() { return now.get(); }
    };
    performance.setComputeStatsEnabled(true);
    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    event.setEventTime(900);
    EventId id = performance.onEventReceived(event, 1000);
    performance.onEventReceivedIfAbsent(event);
    assertEquals(1, performance.getAllEventStats().getCount());
    assertEquals(100, performance.getAllEventStats().getMean());
    performance.onHandlerDone(id);
    assertEquals(500, performance.getStatistics(
        AccessibilityEventUtils.typeToString(event.getEventType()), Performance.STAGE_INLINE_HANDLING).getMean());
    event.recycle();
  }
}
