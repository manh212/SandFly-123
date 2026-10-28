package com.google.android.libraries.accessibility.utils.log;

import androidx.annotation.WorkerThread;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.Nullable;

final class AsyncLogFileWriter {
  private static final long FLUSH_DELAY_MS = 200;

  private final Object submissionLock = new Object();
  private final ScheduledExecutorService executor;
  private final Consumer<IOException> errorHandler;
  private final long maxBytes;
  private final SimpleDateFormat timestampFormat =
      new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

  private volatile @Nullable File configuredFile;
  private boolean enabled;
  private @Nullable File file;
  private @Nullable File oldFile;
  private @Nullable OutputStream stream;
  private @Nullable ScheduledFuture<?> pendingFlush;
  private long bytesWritten;

  AsyncLogFileWriter(
      long maxBytes, ScheduledExecutorService executor, Consumer<IOException> errorHandler) {
    this.maxBytes = maxBytes;
    this.executor = executor;
    this.errorHandler = errorHandler;
  }

  void setFile(@Nullable File nextFile) {
    synchronized (submissionLock) {
      configuredFile = nextFile;
      executor.execute(
          () -> {
            closeStream();
            file = nextFile;
            oldFile = nextFile == null ? null : oldFileFor(nextFile);
          });
    }
  }

  @Nullable File getFile() {
    return configuredFile;
  }

  @Nullable File getOldFile() {
    File current = configuredFile;
    return current == null ? null : oldFileFor(current);
  }

  private static File oldFileFor(File file) {
    return new File(file.getParentFile(), LogUtils.LOG_OLD_FILE_NAME);
  }

  void setEnabled(boolean nextEnabled) {
    synchronized (submissionLock) {
      enabled = nextEnabled;
      if (!nextEnabled) {
        executor.execute(this::closeStream);
      }
    }
  }

  void append(long timestamp, char priority, String tag, String message) {
    synchronized (submissionLock) {
      if (!enabled || configuredFile == null) {
        return;
      }
      executor.execute(() -> write(timestamp, priority, tag, message));
    }
  }

  @WorkerThread
  boolean flush() {
    return await(this::flushStream, false);
  }

  @WorkerThread
  boolean clear() {
    return await(
        () -> {
          closeStream();
          boolean deleted = file != null && file.delete();
          return (oldFile != null && oldFile.delete()) || deleted;
        },
        false);
  }

  @WorkerThread
  private <T> T await(Callable<T> task, T failureValue) {
    Future<T> future;
    synchronized (submissionLock) {
      future = executor.submit(task);
    }
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      errorHandler.accept(new IOException("Cannot finish the debug log operation", e.getCause()));
    }
    return failureValue;
  }

  private void write(long timestamp, char priority, String tag, String message) {
    if (file == null) {
      return;
    }
    try {
      if (stream == null) {
        openStream();
      }
      if (bytesWritten > maxBytes) {
        closeStream();
        if (oldFile != null) {
          oldFile.delete();
          if (!file.renameTo(oldFile)) {
            throw new IOException("Cannot rotate the debug log file");
          }
        }
        openStream();
      }
      timestampFormat.setTimeZone(TimeZone.getDefault());
      String line =
          timestampFormat.format(new Date(timestamp))
              + " "
              + priority
              + "/"
              + tag
              + ": "
              + message
              + "\n";
      byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
      stream.write(bytes);
      bytesWritten += bytes.length;
      if (pendingFlush == null) {
        pendingFlush =
            executor.schedule(
                () -> {
                  pendingFlush = null;
                  flushStream();
                },
                FLUSH_DELAY_MS,
                TimeUnit.MILLISECONDS);
      }
    } catch (IOException e) {
      closeStream();
      errorHandler.accept(e);
    }
  }

  private void openStream() throws IOException {
    File parent = file.getParentFile();
    if (parent != null) {
      parent.mkdirs();
    }
    stream = new BufferedOutputStream(new FileOutputStream(file, true));
    bytesWritten = file.length();
  }

  private boolean flushStream() {
    cancelPendingFlush();
    try {
      if (stream != null) {
        stream.flush();
      }
      return true;
    } catch (IOException e) {
      closeStream();
      errorHandler.accept(e);
      return false;
    }
  }

  private void closeStream() {
    cancelPendingFlush();
    if (stream != null) {
      try {
        stream.close();
      } catch (IOException e) {
        errorHandler.accept(e);
      }
      stream = null;
    }
  }

  private void cancelPendingFlush() {
    if (pendingFlush != null) {
      pendingFlush.cancel(false);
      pendingFlush = null;
    }
  }
}
