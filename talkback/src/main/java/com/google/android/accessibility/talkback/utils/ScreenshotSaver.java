package com.google.android.accessibility.talkback.utils;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.Nullable;

public class ScreenshotSaver {

  private static final String TAG = "ScreenshotSaver";

  public interface Callback {
    void onSavedAndShared(Uri uri);

    void onFailed();
  }

  private ScreenshotSaver() {
    throw new AssertionError();
  }

  public static void saveAndShare(Context context, Bitmap bitmap, String chooserTitle, Callback callback) {
    if (bitmap == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      callback.onFailed();
      return;
    }
    String name =
        "sandfly-screenshot-"
            + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date())
            + ".png";
    ContentValues values = new ContentValues();
    values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
    values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SandFly");
    Uri uri;
    try {
      uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "Cannot create screenshot file: %s", e);
      callback.onFailed();
      return;
    }
    if (uri == null) {
      callback.onFailed();
      return;
    }
    try {
      OutputStream stream = context.getContentResolver().openOutputStream(uri, "wt");
      if (stream == null) {
        throw new IOException("No output stream");
      }
      try {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
          throw new IOException("Bitmap compression failed");
        }
      } finally {
        stream.close();
      }
    } catch (Exception e) {
      LogUtils.w(TAG, "Cannot write screenshot file: %s", e);
      try {
        context.getContentResolver().delete(uri, null, null);
      } catch (RuntimeException ignored) {
      }
      callback.onFailed();
      return;
    }
    Intent share = new Intent(Intent.ACTION_SEND);
    share.setType("image/png");
    share.putExtra(Intent.EXTRA_STREAM, uri);
    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    Intent chooser = Intent.createChooser(share, chooserTitle);
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try {
      context.startActivity(chooser);
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "Cannot open share dialog: %s", e);
      callback.onFailed();
      return;
    }
    callback.onSavedAndShared(uri);
  }
}
