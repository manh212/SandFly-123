package com.google.android.libraries.accessibility.utils.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AsyncLogFileWriterTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final List<IOException> errors = Collections.synchronizedList(new ArrayList<>());
  private ScheduledExecutorService executor;
  private AsyncLogFileWriter writer;
  private File file;

  @Before
  public void setUp() {
    executor = Executors.newSingleThreadScheduledExecutor();
    writer = new AsyncLogFileWriter(1024 * 1024, executor, errors::add);
    file = new File(temporaryFolder.getRoot(), "debug-log.txt");
    writer.setFile(file);
    writer.setEnabled(true);
    assertTrue(writer.flush());
  }

  @After
  public void tearDown() throws InterruptedException {
    writer.setEnabled(false);
    writer.flush();
    executor.shutdownNow();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test(timeout = 10000)
  public void appendDoesNotWaitForTheFileWorker() throws Exception {
    CountDownLatch workerEntered = new CountDownLatch(1);
    CountDownLatch releaseWorker = new CountDownLatch(1);
    executor.execute(
        () -> {
          workerEntered.countDown();
          try {
            releaseWorker.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
    try {
      writer.append(123, 'D', "Navigation", "queued while the worker is blocked");
      assertFalse(file.exists());
    } finally {
      releaseWorker.countDown();
    }
    assertTrue(writer.flush());
    assertTrue(read(file).contains("queued while the worker is blocked"));
    assertTrue(errors.isEmpty());
  }

  @Test
  public void flushIncludesEveryEarlierLineInOrderWithItsCapturedTime() throws Exception {
    long timestamp = 123456789L;
    for (int i = 0; i < 300; i++) {
      writer.append(timestamp + i, 'V', "Focus", "line " + i);
    }

    assertTrue(writer.flush());

    List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    assertEquals(300, lines.size());
    SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
    for (int i = 0; i < lines.size(); i++) {
      assertEquals(
          format.format(new Date(timestamp + i)) + " V/Focus: line " + i, lines.get(i));
    }
    assertTrue(errors.isEmpty());
  }

  @Test
  public void switchingFilesKeepsEarlierAndLaterLinesInTheirOwnFiles() throws Exception {
    File next = new File(temporaryFolder.getRoot(), "next.txt");
    writer.append(1, 'I', "Log", "before switch");
    writer.setFile(next);
    writer.append(2, 'I', "Log", "after switch");

    assertEquals(next, writer.getFile());
    assertTrue(writer.flush());
    assertTrue(read(file).contains("before switch"));
    assertFalse(read(file).contains("after switch"));
    assertTrue(read(next).contains("after switch"));
    assertFalse(read(next).contains("before switch"));
  }

  @Test
  public void disablingDrainsEarlierLinesAndRejectsNewOnes() throws Exception {
    writer.append(1, 'D', "Log", "accepted before disabling");
    writer.setEnabled(false);
    writer.append(2, 'D', "Log", "rejected while disabled");

    assertTrue(writer.flush());
    String text = read(file);
    assertTrue(text.contains("accepted before disabling"));
    assertFalse(text.contains("rejected while disabled"));

    writer.setEnabled(true);
    writer.append(3, 'D', "Log", "accepted after enabling");
    assertTrue(writer.flush());
    assertTrue(read(file).contains("accepted after enabling"));
  }

  @Test
  public void clearingRemovesQueuedEarlierLinesButKeepsLaterOnes() throws Exception {
    writer.append(1, 'D', "Log", "before clear");

    assertTrue(writer.clear());
    assertFalse(file.exists());
    writer.append(2, 'D', "Log", "after clear");
    assertTrue(writer.flush());

    assertFalse(read(file).contains("before clear"));
    assertTrue(read(file).contains("after clear"));
  }

  @Test
  public void rotationKeepsThePreviousFileAndClearDeletesBoth() throws Exception {
    writer = new AsyncLogFileWriter(32, executor, errors::add);
    writer.setFile(file);
    writer.setEnabled(true);
    writer.append(1, 'D', "Log", "first line exceeds the rotation limit");
    writer.append(2, 'D', "Log", "second line exceeds the rotation limit");

    assertTrue(writer.flush());
    File old = writer.getOldFile();
    assertTrue(read(old).contains("first line"));
    assertFalse(read(old).contains("second line"));
    assertTrue(read(file).contains("second line"));
    assertFalse(read(file).contains("first line"));

    assertTrue(writer.clear());
    assertFalse(file.exists());
    assertFalse(old.exists());
  }

  @Test
  public void rotationUsesTheSizeOfAnExistingFile() throws Exception {
    Files.write(file.toPath(), new byte[33]);
    writer = new AsyncLogFileWriter(32, executor, errors::add);
    writer.setFile(file);
    writer.setEnabled(true);
    writer.append(1, 'D', "Log", "Tiếng Việt");

    assertTrue(writer.flush());
    assertEquals(33, writer.getOldFile().length());
    assertTrue(read(file).contains("Tiếng Việt"));
  }

  @Test
  public void nullFileStopsNewLinesWithoutDiscardingEarlierOnes() throws Exception {
    writer.append(1, 'D', "Log", "before removing the file");
    writer.setFile(null);
    writer.append(2, 'D', "Log", "after removing the file");

    assertTrue(writer.flush());
    assertTrue(read(file).contains("before removing the file"));
    assertFalse(read(file).contains("after removing the file"));
  }

  @Test
  public void ioFailureDoesNotStopTheWorkerFromWritingToANewFile() throws Exception {
    File blockedDirectory = temporaryFolder.newFile("not-a-directory");
    writer.setFile(new File(blockedDirectory, "debug-log.txt"));
    writer.append(1, 'E', "Log", "cannot be written");
    writer.flush();
    assertEquals(1, errors.size());

    writer.setFile(file);
    writer.append(2, 'D', "Log", "recovered");
    assertTrue(writer.flush());
    assertTrue(read(file).contains("recovered"));
  }

  @Test
  public void clearingMissingFilesReturnsFalse() {
    assertFalse(writer.clear());
  }

  private static String read(File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }
}
