package com.google.android.accessibility.utils.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import com.google.android.accessibility.utils.LatencyExperiments;
import com.google.android.accessibility.utils.Performance;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HeadsUpToastFilterTest {
  public static class RecordingService extends AccessibilityService {
    int reads;
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
      reads++;
      return new SparseArray<>();
    }
    @Override public List<AccessibilityWindowInfo> getWindows() {
      reads++;
      return Collections.emptyList();
    }
  }

  @After
  public void reset() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.TOAST_FILTER, false);
  }

  @Test
  public void toastSkipsTreeLookupButRealNotificationStillUsesIt() {
    RecordingService service = new RecordingService();
    HeadsUpNotificationEventInterpreter interpreter = new HeadsUpNotificationEventInterpreter(service);
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.TOAST_FILTER, true);
    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
    interpreter.onAccessibilityEvent(event, Performance.EVENT_ID_UNTRACKED);
    assertEquals(0, service.reads);
    event.setParcelableData(new Notification());
    interpreter.onAccessibilityEvent(event, Performance.EVENT_ID_UNTRACKED);
    assertTrue(service.reads > 0);
    event.recycle();
  }
}
