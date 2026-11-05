package com.google.android.accessibility.talkback.speech;

import static com.google.android.accessibility.talkback.compositor.Compositor.EVENT_SYNTHESIZE_SPEECH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Interpretation;
import com.google.android.accessibility.talkback.Interpretation.CompositorID;
import com.google.android.accessibility.talkback.Pipeline.InterpretationReceiver;
import com.google.android.accessibility.utils.output.SpeechCacheManager.LoadSpeechResultNotifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class SpeechCacheControllerDispatchTest {
  @Test
  public void keyboardSynthesisFromWorkerReachesPipelineOnMainThread() throws Exception {
    Handler mainHandler = new Handler(Looper.getMainLooper());
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setText("Keyboard key");
    AccessibilityNodeInfoCompat key = AccessibilityNodeInfoCompat.wrap(node);
    LoadSpeechResultNotifier notifier = (speechInfo, success) -> {};
    AtomicReference<Thread> pipelineThread = new AtomicReference<>();
    AtomicReference<Interpretation> interpretation = new AtomicReference<>();
    AtomicReference<AccessibilityNodeInfoCompat> source = new AtomicReference<>();
    InterpretationReceiver pipeline =
        (eventId, event, value, eventSourceNode) -> {
          pipelineThread.set(Thread.currentThread());
          interpretation.set(value);
          source.set(eventSourceNode);
          return true;
        };

    Thread worker =
        new Thread(
            () -> SpeechCacheController.dispatchSynthesis(mainHandler, pipeline, key, null, notifier));
    worker.start();
    worker.join();

    assertNull(pipelineThread.get());
    Shadows.shadowOf(Looper.getMainLooper()).idle();

    assertSame(Looper.getMainLooper().getThread(), pipelineThread.get());
    assertSame(key, source.get());
    assertTrue(interpretation.get() instanceof CompositorID);
    CompositorID compositorId = (CompositorID) interpretation.get();
    assertEquals(EVENT_SYNTHESIZE_SPEECH, compositorId.value);
    assertSame(notifier, compositorId.getResultNotifier());
  }

  @Test
  public void pendingKeyboardSynthesisCanBeCancelled() {
    Handler mainHandler = new Handler(Looper.getMainLooper());
    AtomicBoolean pipelineCalled = new AtomicBoolean(false);
    InterpretationReceiver pipeline =
        (eventId, event, interpretation, node) -> {
          pipelineCalled.set(true);
          return true;
        };
    AccessibilityNodeInfoCompat key = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain());

    SpeechCacheController.dispatchSynthesis(mainHandler, pipeline, key, null, null);
    mainHandler.removeCallbacksAndMessages(null);
    Shadows.shadowOf(Looper.getMainLooper()).idle();

    assertFalse(pipelineCalled.get());
  }
}
