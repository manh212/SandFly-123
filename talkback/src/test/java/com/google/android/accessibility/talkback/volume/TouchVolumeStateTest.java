package com.google.android.accessibility.talkback.volume;

import static android.view.accessibility.AccessibilityEvent.TYPE_TOUCH_INTERACTION_START;
import static android.view.accessibility.AccessibilityEvent.TYPE_TOUCH_INTERACTION_END;
import static android.view.accessibility.AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START;
import static android.view.accessibility.AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TouchVolumeStateTest {
  @Test
  public void explorationCanIdentifyTouchWithoutInteractionStart() {
    TouchVolumeState state = new TouchVolumeState();
    state.onEvent(TYPE_TOUCH_EXPLORATION_GESTURE_START);
    assertTrue(state.isTouching());
    state.onEvent(TYPE_TOUCH_EXPLORATION_GESTURE_END);
    assertFalse(state.isTouching());
  }

  @Test
  public void endingExplorationDoesNotEndPhysicalTouch() {
    TouchVolumeState state = new TouchVolumeState();
    state.onEvent(TYPE_TOUCH_INTERACTION_START);
    state.onEvent(TYPE_TOUCH_EXPLORATION_GESTURE_START);
    state.onEvent(TYPE_TOUCH_EXPLORATION_GESTURE_END);
    assertTrue(state.isTouching());
    state.onEvent(TYPE_TOUCH_INTERACTION_END);
    assertFalse(state.isTouching());
  }

  @Test
  public void interactionEndClearsAnUnfinishedExploration() {
    TouchVolumeState state = new TouchVolumeState();
    state.onEvent(TYPE_TOUCH_EXPLORATION_GESTURE_START);
    state.onEvent(TYPE_TOUCH_INTERACTION_END);
    assertFalse(state.isTouching());
    state.onEvent(TYPE_TOUCH_INTERACTION_START);
    state.reset();
    assertFalse(state.isTouching());
  }
}
