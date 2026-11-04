package com.sandfly.offlinelicenser;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import io.meridian.licensing.PrivateLogFiles;
import io.meridian.licensing.SupportAttachment;
import javax.crypto.spec.SecretKeySpec;

public final class SupportLogDownloadTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private PrivateLogFiles files() { return new PrivateLogFiles(temporary.getRoot(), new SecretKeySpec(new byte[32], "AES")); }
  private void prepare(Store store, String log, long now) throws Exception {
    SupportLogDownload.prepare(store, files(), files().write(log), now);
    assertFalse(store.encryptedRecord.contains(log));
  }
  private boolean save(Store store, SupportLogDownload.Target target, long now) throws Exception {
    return SupportLogDownload.save(store, files(), target, now);
  }
  private static final class Store implements SupportLogDownload.Store {
    String encryptedRecord = "{}";
    @Override public JSONObject read() throws Exception { return new JSONObject(encryptedRecord); }
    @Override public void write(JSONObject value) { encryptedRecord = value.toString(); }
  }

  @Test public void selectedDocumentReceivesExactUtf8LogFromPersistedPendingRecord() throws Exception {
    Store store = new Store(); String log = "Meridian\nNhật ký lỗi tiếng Việt\n";
    prepare(store, log, 1000);
    Store recreated = new Store(); recreated.encryptedRecord = store.encryptedRecord;
    ByteArrayOutputStream destination = new ByteArrayOutputStream();
    assertTrue(save(recreated, () -> destination, 1100));
    assertEquals(log, new String(destination.toByteArray(), StandardCharsets.UTF_8));
    assertEquals(0, recreated.read().length());
  }

  @Test public void tenMegabyteLogCanBeSavedWithoutStoringItsContentsInThePendingRecord() throws Exception {
    Store store = new Store(); byte[] raw = new byte[SupportAttachment.MAX_BYTES]; java.util.Arrays.fill(raw, (byte) 'x');
    raw[0] = '\n'; raw[raw.length - 1] = '\r';
    PrivateLogFiles files = files(); String file = files.write(output -> output.write(raw));
    SupportLogDownload.prepare(store, files, file, 1000); assertTrue(store.encryptedRecord.length() < 200);
    ByteArrayOutputStream destination = new ByteArrayOutputStream();
    assertTrue(save(store, () -> destination, 1001)); assertArrayEquals(raw, destination.toByteArray());
    assertFalse(files.exists(file));
  }

  @Test public void cancelledAndExpiredPickersCannotWriteAnyLog() throws Exception {
    Store store = new Store(); prepare(store, "log", 1000);
    assertFalse(save(store, null, 1100));
    assertEquals(0, store.read().length());
    prepare(store, "log", 1000);
    ByteArrayOutputStream destination = new ByteArrayOutputStream();
    assertThrows(IllegalStateException.class, () -> save(store, () -> destination, 601001));
    assertEquals(0, destination.size()); assertEquals(0, store.read().length());
  }

  @Test public void failedWriteClearsPendingCopyAndRequiresAnExplicitRetry() throws Exception {
    Store store = new Store(); prepare(store, "log", 1000);
    OutputStream full = new OutputStream() {
      @Override public void write(int value) throws IOException { throw new IOException("full"); }
    };
    assertThrows(IOException.class, () -> save(store, () -> full, 1001));
    assertEquals(0, store.read().length());
    assertThrows(IllegalStateException.class, () -> save(store, ByteArrayOutputStream::new, 1002));
  }
}
