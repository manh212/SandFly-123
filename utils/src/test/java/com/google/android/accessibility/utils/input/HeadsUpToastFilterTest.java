package com.google.android.accessibility.utils.input;

import static org.junit.Assert.assertEquals;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.LatencyExperiments;
import com.google.android.accessibility.utils.Performance;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HeadsUpToastFilterTest {
  private static final class RecordingService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
  }

  private static final class RecordingInterpreter extends HeadsUpNotificationEventInterpreter {
    int reads;

    RecordingInterpreter() { super(new RecordingService()); }

    @Override AccessibilityNodeInfoCompat findHeadsUpNotification(int windowId, int displayId) {
      reads++;
      return null;
    }
  }

  @After
  public void reset() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.TOAST_FILTER, false);
  }

  @Test
  public void toastSkipsTreeLookupButRealNotificationStillUsesIt() {
    RecordingInterpreter interpreter = new RecordingInterpreter();
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.TOAST_FILTER, true);
    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
    interpreter.onAccessibilityEvent(event, Performance.EVENT_ID_UNTRACKED);
    assertEquals(0, interpreter.reads);
    event.setParcelableData(new Notification());
    interpreter.onAccessibilityEvent(event, Performance.EVENT_ID_UNTRACKED);
    assertEquals(1, interpreter.reads);
    event.recycle();
  }

  @Test
  public void disabledFilterKeepsTheExistingToastLookupPath() {
    RecordingInterpreter interpreter = new RecordingInterpreter();
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.TOAST_FILTER, false);
    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
    interpreter.onAccessibilityEvent(event, Performance.EVENT_ID_UNTRACKED);
    assertEquals(1, interpreter.reads);
    event.recycle();
  }
}
