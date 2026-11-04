package com.sandfly.offlinelicenser;

import static org.junit.Assert.*;

import io.meridian.licensing.SupportAttachment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

public final class SupportLogTransferTest {
  private byte[] content(int size) {
    byte[] bytes = new byte[size];
    for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) (32 + (index / SupportAttachment.CHUNK_BYTES * 13 + index % 61) % 95);
    return bytes;
  }

  private JSONObject metadata(byte[] bytes) throws Exception {
    return SupportAttachment.inspect(() -> new ByteArrayInputStream(bytes));
  }

  private JSONObject entry(JSONObject metadata) {
    return new JSONObject().put("logBytes", metadata.getInt("bytes")).put("logSha256", metadata.getString("sha256"));
  }

  private JSONObject part(byte[] bytes, String path) {
    int index = Integer.parseInt(path.substring(path.lastIndexOf("chunk") + 5));
    byte[] data = Arrays.copyOfRange(bytes, index * SupportAttachment.CHUNK_BYTES,
        Math.min(bytes.length, (index + 1) * SupportAttachment.CHUNK_BYTES));
    return new JSONObject().put("index", index).put("bytes", data.length)
        .put("data", Base64.getEncoder().encodeToString(data));
  }

  @Test(timeout = 15000) public void downloadsFourPartsConcurrentlyAndReassemblesExactBytesWithProgress() throws Exception {
    byte[] bytes = content(SupportAttachment.CHUNK_BYTES * 5 + 19); JSONObject info = metadata(bytes);
    CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger(); AtomicBoolean closed = new AtomicBoolean();
    List<Integer> progress = new ArrayList<>();
    SupportLogTransfer.Reader reader = new SupportLogTransfer.Reader() {
      @Override public JSONObject get(String path) throws Exception {
        if (path.startsWith("supportAttachments/")) return info;
        int running = active.incrementAndGet(); maximum.accumulateAndGet(running, Math::max);
        try { entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Parts did not run concurrently"); return part(bytes, path); }
        finally { active.decrementAndGet(); }
      }
      @Override public void cancel() { closed.set(true); release.countDown(); }
    };
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<byte[]> result = executor.submit(() -> SupportLogTransfer.download(reader, "ticket", "message", entry(info),
          (received, total) -> { assertEquals(bytes.length, total); progress.add(received); }, () -> false, 10000));
      assertTrue(entered.await(5, TimeUnit.SECONDS)); release.countDown();
      assertArrayEquals(bytes, result.get(5, TimeUnit.SECONDS)); assertEquals(4, maximum.get());
      assertEquals(Integer.valueOf(0), progress.get(0));
      assertEquals(Integer.valueOf(bytes.length), progress.get(progress.size() - 1));
      for (int index = 1; index < progress.size(); index++) assertTrue(progress.get(index) > progress.get(index - 1));
      assertTrue(closed.get());
    } finally { release.countDown(); executor.shutdownNow(); }
  }

  @Test(timeout = 5000) public void stalledRequestHasAnOverallDeadlineAndCancelsItsReader() throws Exception {
    byte[] bytes = {65}; JSONObject info = metadata(bytes); AtomicBoolean closed = new AtomicBoolean();
    CountDownLatch release = new CountDownLatch(1);
    SupportLogTransfer.Reader reader = new SupportLogTransfer.Reader() {
      @Override public JSONObject get(String path) throws Exception { release.await(); return info; }
      @Override public void cancel() { closed.set(true); release.countDown(); }
    };
    assertThrows(IOException.class, () -> SupportLogTransfer.download(reader, "ticket", "message", entry(info), (a, b) -> {}, () -> false, 100));
    assertTrue(closed.get());
  }

  @Test public void cancelledTransferDoesNotStartANetworkRequest() throws Exception {
    JSONObject info = metadata(new byte[]{65}); AtomicInteger calls = new AtomicInteger();
    SupportLogTransfer.Reader reader = path -> { calls.incrementAndGet(); return info; };
    assertThrows(IOException.class, () -> SupportLogTransfer.download(reader, "ticket", "message", entry(info), (a, b) -> {}, () -> true, 1000));
    assertEquals(0, calls.get());
  }

  @Test public void invalidChunkAndFinalChecksumCannotBeAccepted() throws Exception {
    byte[] bytes = content(100); JSONObject info = metadata(bytes);
    SupportLogTransfer.Reader invalid = path -> path.startsWith("supportAttachments/") ? info : part(bytes, path).put("index", 3);
    assertThrows(IOException.class, () -> SupportLogTransfer.download(invalid, "ticket", "message", entry(info), (a, b) -> {}, () -> false, 5000));
    JSONObject changed = new JSONObject(info.toString()).put("sha256", "0".repeat(64));
    SupportLogTransfer.Reader corrupt = path -> path.startsWith("supportAttachments/") ? changed : part(bytes, path);
    assertThrows(IOException.class, () -> SupportLogTransfer.download(corrupt, "ticket", "message", entry(changed), (a, b) -> {}, () -> false, 5000));
  }

  @Test public void metadataMismatchStopsBeforeAnyChunksAreRequested() throws Exception {
    byte[] bytes = {65}; JSONObject info = metadata(bytes); AtomicInteger chunks = new AtomicInteger();
    SupportLogTransfer.Reader reader = path -> {
      if (path.startsWith("supportAttachments/")) return info;
      chunks.incrementAndGet(); return part(bytes, path);
    };
    JSONObject wrong = entry(info).put("logSha256", "0".repeat(64));
    assertThrows(IOException.class, () -> SupportLogTransfer.download(reader, "ticket", "message", wrong, (a, b) -> {}, () -> false, 5000));
    assertEquals(0, chunks.get());
  }
}
