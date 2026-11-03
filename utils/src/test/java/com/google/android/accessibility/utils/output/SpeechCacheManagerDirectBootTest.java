package com.google.android.accessibility.utils.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.UserManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SpeechCacheManagerDirectBootTest {
  private UserManager users;
  private HandlerThread worker;
  private SpeechCacheManager cache;
  private RecordingTts tts;
  private int fileAccesses;
  private boolean storageUnavailable;
  private final List<Boolean> results = new ArrayList<>();

  private static final class RecordingTts extends FailoverTextToSpeech {
    File synthesizedFile;

    RecordingTts(Context context) {
      super(context, false);
    }

    @Override
    protected boolean synthesizeToFile(
        CharSequence text, Locale locale, Bundle parameters, File file, String utteranceId) {
      synthesizedFile = file;
      return false;
    }
  }

  @Before
  public void setUp() {
    Context app = RuntimeEnvironment.getApplication();
    users = app.getSystemService(UserManager.class);
    shadowOf(users).setUserUnlocked(false);
    Context context = new ContextWrapper(app) {
      @Override
      public File getFilesDir() {
        if (!users.isUserUnlocked()) {
          throw new AssertionError("Credential storage accessed before unlock");
        }
        fileAccesses++;
        if (storageUnavailable) {
          throw new IllegalStateException("Credential storage is unavailable");
        }
        return super.getFilesDir();
      }
    };
    worker = new HandlerThread("direct-boot-cache-test");
    worker.start();
    cache = new SpeechCacheManager(context, worker.getLooper());
    tts = new RecordingTts(app);
  }

  @After
  public void tearDown() {
    cache.shutDown();
    tts.shutdown();
    shadowOf(users).setUserUnlocked(true);
  }

  @Test
  public void lockedStartupSkipsCredentialFilesAndCachingRecoversAfterUnlock() {
    requestCache();

    assertEquals(0, fileAccesses);
    assertEquals(List.of(false), results);
    assertNull(tts.synthesizedFile);

    shadowOf(users).setUserUnlocked(true);
    requestCache();

    assertTrue(fileAccesses > 0);
    assertNotNull(tts.synthesizedFile);
    assertTrue(tts.synthesizedFile.getParentFile().isDirectory());
    assertEquals(List.of(false, false), results);
  }

  @Test
  public void unavailableStorageFailsOnlyTheCacheAndCanRetryWithoutRestarting() {
    shadowOf(users).setUserUnlocked(true);
    storageUnavailable = true;
    requestCache();

    assertEquals(List.of(false), results);
    assertNull(tts.synthesizedFile);

    storageUnavailable = false;
    requestCache();

    assertNotNull(tts.synthesizedFile);
    assertFalse(results.get(1));
  }

  private void requestCache() {
    cache.addSpeechCache(
        tts, "Lock screen", Locale.ENGLISH, (speech, success) -> results.add(success),
        new Bundle(), "test.engine");
    shadowOf(worker.getLooper()).idle();
  }
}
