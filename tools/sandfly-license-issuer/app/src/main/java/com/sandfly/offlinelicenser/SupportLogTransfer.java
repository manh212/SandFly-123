package com.sandfly.offlinelicenser;

import io.meridian.licensing.SupportAttachment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

final class SupportLogTransfer {
  interface Reader {
    JSONObject get(String path) throws Exception;
    default void cancel() {}
  }
  interface Progress { void update(int received, int total); }
  private static final class Part {
    final int index;
    final byte[] bytes;
    Part(int index, byte[] bytes) { this.index = index; this.bytes = bytes; }
  }

  static byte[] download(Reader reader, String ticket, String message, JSONObject entry,
      Progress progress, BooleanSupplier cancelled, long timeoutMillis) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    ExecutorService workers = Executors.newFixedThreadPool(4, task -> {
      Thread thread = new Thread(task, "MeridianLogDownload"); thread.setDaemon(true); return thread;
    });
    List<Future<?>> tasks = new ArrayList<>();
    try {
      remaining(deadline, cancelled);
      Future<JSONObject> metadata = workers.submit(() -> reader.get("supportAttachments/" + ticket + "/" + message));
      tasks.add(metadata);
      JSONObject info = metadata.get(remaining(deadline, cancelled), TimeUnit.NANOSECONDS);
      SupportAttachment.validate(info);
      int size = info.getInt("bytes"), parts = info.getInt("parts");
      String hash = info.getString("sha256");
      if (entry.optLong("logBytes") != size || !entry.optString("logSha256").equals(hash)) {
        throw new IOException("Thông tin nhật ký không khớp tin nhắn.");
      }
      byte[] result = new byte[size];
      CompletionService<Part> completed = new ExecutorCompletionService<>(workers);
      progress.update(0, size);
      for (int index = 0; index < parts; index++) {
        int partIndex = index;
        tasks.add(completed.submit(() -> {
          remaining(deadline, cancelled);
          JSONObject part = reader.get("supportLogChunks/" + ticket + "/" + message + "/chunk" + partIndex);
          int expected = Math.min(SupportAttachment.CHUNK_BYTES, size - partIndex * SupportAttachment.CHUNK_BYTES);
          if (part.optInt("index", -1) != partIndex || part.optInt("bytes") != expected
              || part.optString("data").length() != 4 * ((expected + 2) / 3)) {
            throw new IOException("Nhật ký thiếu dữ liệu hoặc không hợp lệ. Hãy tải lại.");
          }
          byte[] bytes;
          try { bytes = Base64.getDecoder().decode(part.getString("data")); }
          catch (IllegalArgumentException e) { throw new IOException("Dữ liệu nhật ký không hợp lệ.", e); }
          if (bytes.length != expected) throw new IOException("Kích thước nhật ký không khớp.");
          return new Part(partIndex, bytes);
        }));
      }
      int received = 0;
      for (int index = 0; index < parts; index++) {
        Future<Part> next = completed.poll(remaining(deadline, cancelled), TimeUnit.NANOSECONDS);
        if (next == null) throw new TimeoutException();
        Part part = next.get();
        remaining(deadline, cancelled);
        System.arraycopy(part.bytes, 0, result, part.index * SupportAttachment.CHUNK_BYTES, part.bytes.length);
        received += part.bytes.length;
        progress.update(received, size);
      }
      JSONObject actual = SupportAttachment.inspect(() -> new ByteArrayInputStream(result));
      if (!hash.equals(actual.getString("sha256"))) throw new IOException("Nhật ký bị thiếu hoặc thay đổi. Hãy tải lại.");
      remaining(deadline, cancelled);
      return result;
    } catch (TimeoutException e) {
      throw new IOException("Tải nhật ký quá thời gian chờ. Kiểm tra mạng rồi thử lại.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Đã hủy tải nhật ký.");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof TimeoutException) throw new IOException("Tải nhật ký quá thời gian chờ. Kiểm tra mạng rồi thử lại.", e.getCause());
      if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
      throw new IOException("Không tải được nhật ký.", e.getCause());
    } finally {
      for (Future<?> task : tasks) task.cancel(true);
      workers.shutdownNow();
      reader.cancel();
    }
  }

  private static long remaining(long deadline, BooleanSupplier cancelled) throws IOException, TimeoutException {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Đã hủy tải nhật ký.");
    long left = deadline - System.nanoTime();
    if (left <= 0) throw new TimeoutException();
    return left;
  }

  private SupportLogTransfer() {}
}
