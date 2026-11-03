package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityService;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.view.accessibility.AccessibilityEvent;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ActivationReminderDirectBootTest {
  public static final class ReminderService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
  }

  private ReminderService service;
  private UserManager users;

  @Before
  public void setUp() {
    service = Robolectric.buildService(ReminderService.class).create().get();
    users = service.getSystemService(UserManager.class);
    ActivationReminder.stopService(service);
  }

  @After
  public void tearDown() {
    ActivationReminder.stopService(service);
    shadowOf(users).setUserUnlocked(true);
  }

  @Test
  public void lockedBootDoesNotStartTrialOrExpireBeforeTheUserCanUnlock() throws Exception {
    shadowOf(users).setUserUnlocked(false);

    ActivationReminder.startService(service);
    assertEquals(0L, deadline());
    assertFalse(scheduled());
    shadowOf(Looper.getMainLooper()).idleFor(65, TimeUnit.SECONDS);

    assertEquals(0L, deadline());
    assertFalse(scheduled());

    shadowOf(users).setUserUnlocked(true);
    long unlockedAt = SystemClock.elapsedRealtime();
    ActivationReminder.startService(service);

    assertTrue(scheduled());
    assertEquals(unlockedAt + 60_000L, deadline());
  }

  @Test
  public void lockedStorageCancelsAnExistingDeadlineInsteadOfDisablingTheService() throws Exception {
    shadowOf(users).setUserUnlocked(true);
    ActivationReminder.startService(service);
    assertTrue(scheduled());

    shadowOf(users).setUserUnlocked(false);
    shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);

    assertEquals(0L, deadline());
    assertFalse(scheduled());
  }

  private static long deadline() throws Exception {
    Field field = ActivationReminder.class.getDeclaredField("deadlineElapsed");
    field.setAccessible(true);
    return field.getLong(null);
  }

  private static boolean scheduled() throws Exception {
    Field field = ActivationReminder.class.getDeclaredField("scheduled");
    field.setAccessible(true);
    return field.getBoolean(null);
  }
}
