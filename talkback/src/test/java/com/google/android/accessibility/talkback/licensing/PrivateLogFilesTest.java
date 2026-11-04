package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;

import io.meridian.licensing.PrivateLogFiles;
import io.meridian.licensing.SupportAttachment;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class PrivateLogFilesTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");

  @Test public void fullExportStaysEncryptedAndSurvivesRecreationWithoutChangingAnyBytes() throws Exception {
    File directory = temporary.newFolder(); PrivateLogFiles files = new PrivateLogFiles(directory, key);
    String raw = "\n  Bearer literal-test-value\r\napi_key=literal-test-key\u0000\nVăn bản đã đọc 😀\r\n";
    String id = files.write(raw);
    assertFalse(new String(Files.readAllBytes(new File(directory, id + ".bin").toPath()), StandardCharsets.UTF_8).contains("literal-test-value"));
    PrivateLogFiles recreated = new PrivateLogFiles(directory, key);
    try (InputStream input = recreated.source(id).open()) { assertArrayEquals(raw.getBytes(StandardCharsets.UTF_8), input.readAllBytes()); }
    assertEquals(raw, recreated.page(id, 0));
  }

  @Test public void tenMegabytesAreAcceptedAndOneExtraByteLeavesNoPartialFile() throws Exception {
    File directory = temporary.newFolder(); PrivateLogFiles files = new PrivateLogFiles(directory, key);
    byte[] bytes = new byte[SupportAttachment.MAX_BYTES]; Arrays.fill(bytes, (byte) 'a');
    String id = files.write(output -> output.write(bytes));
    assertEquals(SupportAttachment.MAX_BYTES, SupportAttachment.inspect(files.source(id)).getInt("bytes"));
    try (InputStream input = files.source(id).open()) { assertArrayEquals(bytes, input.readAllBytes()); }
    int count = directory.list().length;
    assertThrows(IllegalArgumentException.class, () -> files.write(output -> { output.write(bytes); output.write(1); }));
    assertEquals(count, directory.list().length);
    assertThrows(IllegalArgumentException.class, () -> files.source("../foreign"));
  }

  @Test public void changedCiphertextAndMissingCompletionRecordCannotBeSentAsValidLogs() throws Exception {
    File directory = temporary.newFolder(); PrivateLogFiles files = new PrivateLogFiles(directory, key);
    String first = files.write("private log");
    try (RandomAccessFile file = new RandomAccessFile(new File(directory, first + ".bin"), "rw")) {
      file.seek(20); int value = file.read(); file.seek(20); file.write(value ^ 1);
    }
    assertThrows(IOException.class, () -> SupportAttachment.inspect(files.source(first)));
    String second = files.write("complete log");
    try (RandomAccessFile file = new RandomAccessFile(new File(directory, second + ".bin"), "rw")) { file.setLength(file.length() - 4); }
    assertThrows(IOException.class, () -> SupportAttachment.inspect(files.source(second)));
  }

  @Test public void cleanupPreservesQueuedAndActiveFilesWhileRemovingAbandonedOldLogs() throws Exception {
    File directory = temporary.newFolder(); PrivateLogFiles files = new PrivateLogFiles(directory, key);
    String queued = files.write("queued"), orphan = files.write("abandoned"), recent = files.write("recent");
    assertTrue(new File(directory, queued + ".bin").setLastModified(1000));
    assertTrue(new File(directory, orphan + ".bin").setLastModified(1000));
    files.prune(java.util.Set.of(queued), 2000);
    assertTrue(files.exists(queued)); assertTrue(files.exists(recent)); assertFalse(files.exists(orphan));
  }
}
