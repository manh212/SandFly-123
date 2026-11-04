package com.sandfly.offlinelicenser;

import static org.junit.Assert.*;

import io.meridian.licensing.SupportAttachment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;

public final class SupportLogDownloadTest {
  private static final class Store implements SupportLogDownload.Store {
    String record = "{}";
    @Override public JSONObject read() { return new JSONObject(record); }
    @Override public void write(JSONObject value) { record = value.toString(); }
  }

  private JSONObject remoteEntry(byte[] bytes) throws Exception {
    JSONObject info = SupportAttachment.inspect(() -> new ByteArrayInputStream(bytes));
    return new JSONObject().put("logBytes", bytes.length).put("logSha256", info.getString("sha256"))
        .put("name", "Sender").put("text", "Unrelated message content");
  }

  private void prepare(Store store, String text) throws Exception {
    SupportLogDownload.prepare(store, "ticket", "message", new JSONObject().put("log", text), 1000);
  }

  private void remote(Store store, byte[] bytes) throws Exception {
    SupportLogDownload.prepare(store, "ticket", "message", remoteEntry(bytes), 1000);
  }

  @Test public void inlineLogIsSavedAsPlainUtf8WithoutAnEncryptedTemporaryFile() throws Exception {
    Store store = new Store(); String log = "Nhật ký tiếng Việt\nNguyên bản\r\n";
    prepare(store, log);
    assertEquals(log, store.read().getString("text"));
    Store recreated = new Store(); recreated.record = store.record;
    ByteArrayOutputStream destination = new ByteArrayOutputStream();
    assertTrue(SupportLogDownload.save(recreated, () -> destination, 1100, null));
    assertArrayEquals(log.getBytes(StandardCharsets.UTF_8), destination.toByteArray());
    assertEquals(0, recreated.read().length());
  }

  @Test public void remoteLogWaitsForDocumentSelectionAndSurvivesRecreation() throws Exception {
    Store store = new Store(); byte[] log = "Nhật ký nguyên bản\n".getBytes(StandardCharsets.UTF_8);
    remote(store, log);
    assertFalse(store.record.contains("Unrelated message content"));
    assertFalse(store.read().has("file")); assertFalse(store.read().has("text"));
    Store recreated = new Store(); recreated.record = store.record;
    ByteArrayOutputStream destination = new ByteArrayOutputStream();
    assertTrue(SupportLogDownload.save(recreated, () -> destination, 1100, (ticket, message, entry) -> {
      assertEquals("ticket", ticket); assertEquals("message", message);
      assertEquals(log.length, entry.getInt("logBytes")); return log;
    }));
    assertArrayEquals(log, destination.toByteArray()); assertEquals(0, recreated.read().length());
  }

  @Test public void tenMegabyteLogIsCopiedWithoutEncryptionOrStoringContentsInThePendingRecord() throws Exception {
    byte[] log = new byte[SupportAttachment.MAX_BYTES]; java.util.Arrays.fill(log, (byte) 'x');
    Store store = new Store(); remote(store, log); assertTrue(store.record.length() < 400);
    ByteArrayOutputStream destination = new ByteArrayOutputStream();
    assertTrue(SupportLogDownload.save(store, () -> destination, 1100, (a, b, c) -> log));
    assertArrayEquals(log, destination.toByteArray()); assertEquals(0, store.read().length());
  }

  @Test public void cancellationAndExpiryNeverDownloadAnAttachment() throws Exception {
    Store store = new Store(); byte[] log = {65};
    SupportLogDownload.Loader unexpected = (a, b, c) -> { throw new AssertionError("Unexpected network request"); };
    remote(store, log); assertFalse(SupportLogDownload.save(store, null, 1100, unexpected));
    assertEquals(0, store.read().length());
    remote(store, log);
    assertThrows(IllegalStateException.class,
        () -> SupportLogDownload.save(store, ByteArrayOutputStream::new, 601001, unexpected));
    assertEquals(0, store.read().length());
  }

  @Test public void failedDownloadDiscardsTheSelectedDocumentAndAllowsRetry() throws Exception {
    Store store = new Store(); remote(store, new byte[]{65}); boolean[] discarded = {false};
    SupportLogDownload.Target target = new SupportLogDownload.Target() {
      @Override public OutputStream open() { throw new AssertionError("Incomplete data must not be written"); }
      @Override public void discard() { discarded[0] = true; }
    };
    assertThrows(IOException.class, () -> SupportLogDownload.save(store, target, 1100,
        (a, b, c) -> { throw new IOException("Network unavailable"); }));
    assertTrue(discarded[0]); assertEquals(0, store.read().length());
    prepare(store, "retry");
    assertTrue(SupportLogDownload.save(store, ByteArrayOutputStream::new, 1100, null));
  }

  @Test public void wrongLengthAndChecksumCannotWriteAFile() throws Exception {
    Store store = new Store(); byte[] log = {65, 66};
    SupportLogDownload.Target unopened = () -> { throw new AssertionError("Invalid log must not be written"); };
    remote(store, log);
    assertThrows(IllegalStateException.class, () -> SupportLogDownload.save(store, unopened, 1100, (a, b, c) -> new byte[]{65}));
    remote(store, log);
    assertThrows(IllegalStateException.class, () -> SupportLogDownload.save(store, unopened, 1100, (a, b, c) -> new byte[]{66, 65}));
    assertEquals(0, store.read().length());
  }

  @Test public void failedWriteDiscardsThePartialFile() throws Exception {
    Store store = new Store(); prepare(store, "log"); boolean[] discarded = {false};
    SupportLogDownload.Target target = new SupportLogDownload.Target() {
      @Override public OutputStream open() { return new OutputStream() {
        @Override public void write(int value) throws IOException { throw new IOException("full"); }
      }; }
      @Override public void discard() { discarded[0] = true; }
    };
    assertThrows(IOException.class, () -> SupportLogDownload.save(store, target, 1100, null));
    assertTrue(discarded[0]); assertEquals(0, store.read().length());
  }

  @Test public void abandonedDownloadCannotClearANewerPendingSave() throws Exception {
    Store store = new Store(); remote(store, new byte[]{65});
    assertThrows(IOException.class, () -> SupportLogDownload.save(store, ByteArrayOutputStream::new, 1100, (a, b, c) -> {
      prepare(store, "new request"); throw new IOException("old request stopped");
    }));
    assertEquals("new request", store.read().getString("text"));
  }

  @Test public void invalidMetadataCannotReplaceThePendingSave() throws Exception {
    Store store = new Store(); prepare(store, "existing log"); String previous = store.record;
    JSONObject entry = new JSONObject().put("logBytes", SupportAttachment.MAX_BYTES + 1L).put("logSha256", "0".repeat(64));
    assertThrows(IllegalStateException.class, () -> SupportLogDownload.prepare(store, "ticket", "message", entry, 1100));
    assertEquals(previous, store.record);
  }
}
