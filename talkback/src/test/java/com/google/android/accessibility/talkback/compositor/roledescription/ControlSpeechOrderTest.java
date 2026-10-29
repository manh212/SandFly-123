package com.google.android.accessibility.talkback.compositor.roledescription;

import static org.junit.Assert.assertEquals;
import com.google.android.accessibility.talkback.compositor.CompositorUtils;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ControlSpeechOrderTest {
  @After
  public void tearDown() {
    RoleDescriptionExtractor.setSplitControlSpeechEnabled(false);
  }

  private String describe() {
    return RoleDescriptionExtractor.joinInOrder(
        RoleDescriptionExtractor.DESC_ORDER_NAME_ROLE_STATE_POSITION,
        "Wi-Fi", "Công tắc", "Bật").toString();
  }

  @Test
  public void togglingSplitOrderPreservesTheConfiguredOrderWhenOff() {
    String separator = CompositorUtils.getSeparator();
    RoleDescriptionExtractor.setSplitControlSpeechEnabled(false);
    assertEquals(String.join(separator, "Wi-Fi", "Công tắc", "Bật"), describe());
    RoleDescriptionExtractor.setSplitControlSpeechEnabled(true);
    assertEquals(String.join(separator, "Công tắc", "Bật", "Wi-Fi"), describe());
    RoleDescriptionExtractor.setSplitControlSpeechEnabled(false);
    assertEquals(String.join(separator, "Wi-Fi", "Công tắc", "Bật"), describe());
  }
}
