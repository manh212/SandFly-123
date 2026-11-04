package com.sandfly.offlinelicenser;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;

public final class SupportLogDownloadTest {
  private static final class Store implements SupportLogDownload.Store {
    String encryptedRecord = "{}";
    @Override public JSONObject read() throws Exception { return new JSONObject(encryptedRecord); }
    @Override public void write(JSONObject value) { encryptedRecord = value.toString(); }
  }

  @Test public void selectedDocumentReceivesExactUtf8LogFromPersistedPendingRecord() throws Exception {
    Store store = new Store(); String log = "Meridian\nNhật ký lỗi tiếng Việt\n";
    SupportLogDownload.prepare(store, log, 1000);
    Store recreated = new Store(); recreated.encryptedRecord = store.encryptedRecord;
    ByteArrayOutputStream destination = new ByteArrayOutputStream();
    assertTrue(SupportLogDownload.save(recreated, () -> destination, 1100));
    assertEquals(log, new String(destination.toByteArray(), StandardCharsets.UTF_8));
    assertEquals(0, recreated.read().length());
  }

  @Test public void cancelledAndExpiredPickersCannotWriteAnyLog() throws Exception {
    Store store = new Store(); SupportLogDownload.prepare(store, "log", 1000);
    assertFalse(SupportLogDownload.save(store, null, 1100));
    assertEquals(0, store.read().length());
    SupportLogDownload.prepare(store, "log", 1000);
    ByteArrayOutputStream destination = new ByteArrayOutputStream();
    assertThrows(IllegalStateException.class, () -> SupportLogDownload.save(store, () -> destination, 601001));
    assertEquals(0, destination.size()); assertEquals(0, store.read().length());
  }

  @Test public void failedWriteClearsPendingCopyAndRequiresAnExplicitRetry() throws Exception {
    Store store = new Store(); SupportLogDownload.prepare(store, "log", 1000);
    OutputStream full = new OutputStream() {
      @Override public void write(int value) throws IOException { throw new IOException("full"); }
    };
    assertThrows(IOException.class, () -> SupportLogDownload.save(store, () -> full, 1001));
    assertEquals(0, store.read().length());
    assertThrows(IllegalStateException.class, () -> SupportLogDownload.save(store, ByteArrayOutputStream::new, 1002));
  }
}
