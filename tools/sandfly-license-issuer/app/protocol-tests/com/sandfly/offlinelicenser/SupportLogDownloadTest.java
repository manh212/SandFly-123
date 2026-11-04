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

  private JSONObject remoteEntry(String log) throws Exception {
    JSONObject info = SupportAttachment.inspect(() -> new java.io.ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8)));
    return new JSONObject().put("logBytes", info.getInt("bytes")).put("logSha256", info.getString("sha256"))
        .put("text", "Unrelated message content").put("name", "Sender");
  }

  @Test public void remoteDownloadWaitsForDocumentSelectionAndSurvivesActivityRecreation() throws Exception {
    Store store = new Store(); String log = "Nhật ký nguyên bản\nTiếng Việt\n";
    JSONObject entry = remoteEntry(log);
    SupportLogDownload.prepareRemote(store, files(), "ticket", "message", entry, 1000);
    assertFalse(store.encryptedRecord.contains(log));
    assertFalse(store.encryptedRecord.contains("Unrelated message content"));
    assertFalse(store.read().has("file"));
    Store recreated = new Store(); recreated.encryptedRecord = store.encryptedRecord;
    ByteArrayOutputStream destination = new ByteArrayOutputStream();
    String[] downloaded = {null};
    assertTrue(SupportLogDownload.save(recreated, files(), () -> destination, 1100,
        (ticket, message, metadata) -> {
          assertEquals("ticket", ticket); assertEquals("message", message);
          assertEquals(entry.getLong("logBytes"), metadata.getLong("logBytes"));
          assertEquals(entry.getString("logSha256"), metadata.getString("logSha256"));
          downloaded[0] = files().write(log); return downloaded[0];
        }));
    assertArrayEquals(log.getBytes(StandardCharsets.UTF_8), destination.toByteArray());
    assertEquals(0, recreated.read().length());
    assertFalse(files().exists(downloaded[0]));
  }

  @Test public void cancelledAndExpiredRemoteRequestsNeverFetchAttachments() throws Exception {
    Store store = new Store();
    SupportLogDownload.Loader unexpected = (ticket, message, metadata) -> {
      throw new AssertionError("Attachment must not be fetched without a valid selected destination");
    };
    SupportLogDownload.prepareRemote(store, files(), "ticket", "message", remoteEntry("log"), 1000);
    assertFalse(SupportLogDownload.save(store, files(), null, 1100, unexpected));
    assertEquals(0, store.read().length());
    SupportLogDownload.prepareRemote(store, files(), "ticket", "message", remoteEntry("log"), 1000);
    ByteArrayOutputStream destination = new ByteArrayOutputStream();
    assertThrows(IllegalStateException.class,
        () -> SupportLogDownload.save(store, files(), () -> destination, 601001, unexpected));
    assertEquals(0, destination.size()); assertEquals(0, store.read().length());
  }

  @Test public void failedRemoteDownloadClearsPendingRequestAndLeavesDestinationUntouched() throws Exception {
    Store store = new Store();
    SupportLogDownload.prepareRemote(store, files(), "ticket", "message", remoteEntry("log"), 1000);
    ByteArrayOutputStream destination = new ByteArrayOutputStream();
    assertThrows(IOException.class, () -> SupportLogDownload.save(store, files(), () -> destination, 1001,
        (ticket, message, metadata) -> { throw new IOException("Network unavailable"); }));
    assertEquals(0, destination.size()); assertEquals(0, store.read().length());
  }

  @Test public void failedRemoteWriteDeletesDownloadedTemporaryLog() throws Exception {
    Store store = new Store();
    SupportLogDownload.prepareRemote(store, files(), "ticket", "message", remoteEntry("log"), 1000);
    String[] downloaded = {null};
    OutputStream full = new OutputStream() {
      @Override public void write(int value) throws IOException { throw new IOException("full"); }
    };
    assertThrows(IOException.class, () -> SupportLogDownload.save(store, files(), () -> full, 1001,
        (ticket, message, metadata) -> { downloaded[0] = files().write("log"); return downloaded[0]; }));
    assertEquals(0, store.read().length()); assertFalse(files().exists(downloaded[0]));
  }

  @Test public void malformedRemoteMetadataCannotReplaceAnExistingPendingSave() throws Exception {
    Store store = new Store(); prepare(store, "existing log", 1000);
    String previous = store.encryptedRecord;
    JSONObject entry = new JSONObject().put("logBytes", SupportAttachment.MAX_BYTES + 1L).put("logSha256", "0".repeat(64));
    assertThrows(IllegalStateException.class,
        () -> SupportLogDownload.prepareRemote(store, files(), "ticket", "message", entry, 1100));
    assertEquals(previous, store.encryptedRecord);
    assertTrue(files().exists(store.read().getString("file")));
  }
}
