/*
 * Copyright (C) 2021 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.imagecaption;

import static com.google.android.accessibility.talkback.imagecaption.ImageCaptionUtils.CaptionType.OCR;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.StringBuilderUtils;
import com.google.android.accessibility.utils.ocr.OcrController;
import com.google.android.accessibility.utils.ocr.OcrController.OcrListener;
import com.google.android.accessibility.utils.ocr.OcrInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link CaptionRequest} for performing OCR (optical character recognition) to recognize text
 * from the screenshot.
 */
public class CharacterCaptionRequest extends CaptionRequest implements OcrListener {

  private final OcrController ocrController;
  private final AccessibilityService service;
  private final Bitmap screenCapture;
  private final float threshold = 0.3f;

  /** This object takes ownership of node, caller should not recycle. */
  public CharacterCaptionRequest(
      int requestId,
      AccessibilityService service,
      AccessibilityNodeInfoCompat node,
      Bitmap screenCapture,
      @NonNull OnFinishListener onFinishListener,
      @NonNull OnErrorListener onErrorListener,
      boolean isUserRequested) {
    super(requestId, node, onFinishListener, onErrorListener, isUserRequested);
    ocrController = new OcrController(this, /* needCropScreenshot= */ false);
    this.service = service;
    this.screenCapture = screenCapture;
  }

  /**
   * Captures screen and performs ocr(optical character recognition) to recognize text for the given
   * node.
   */
  @Override
  public void perform() {
    onCaptionStart();
    if (useTesseract(service)) {
      recognizeWithTesseract();
    } else {
      recognizeWithMlKit();
    }

    runTimeoutRunnable();
  }

  private void recognizeWithMlKit() {
    final List<OcrInfo> ocrInfos = new ArrayList<>();
    ocrInfos.add(new OcrInfo(node));
    ocrController.recognizeTextForNodes(
        screenCapture, ocrInfos, /* selectionBounds= */ null, Filter.node(node -> true));
  }

  private void recognizeWithTesseract() {
    Handler mainHandler = new Handler(Looper.getMainLooper());
    new Thread(
            () -> {
              String text = TesseractOcr.recognizeText(service, screenCapture);
              mainHandler.post(
                  () -> {
                    if (text == null) {
                      recognizeWithMlKit();
                      return;
                    }
                    stopTimeoutRunnable();
                    onCaptionFinish(Result.create(OCR, text.trim()));
                  });
            })
        .start();
  }

  private static boolean useTesseract(AccessibilityService service) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(service);
    String engine =
        prefs.getString(
            service.getString(R.string.pref_ocr_engine_key),
            service.getString(R.string.ocr_engine_mlkit_value));
    return service.getString(R.string.ocr_engine_tesseract_value).equals(engine)
        && TesseractOcr.isDataDownloaded(service);
  }

  @Override
  public void onOcrStarted() {}

  @Override
  public void onOcrFinished(List<OcrInfo> ocrResults) {
    stopTimeoutRunnable();
    if (ocrResults == null) {
      onError(ERROR_TEXT_RECOGNITION_NO_RESULT);
      return;
    }

    List<CharSequence> texts = new ArrayList<>();
    for (OcrInfo ocrResult : ocrResults) {
      String text = OcrController.getTextFromBlocks(ocrResult.getTextBlocks(), threshold);
      if (TextUtils.isEmpty(text)) {
        continue;
      }
      texts.add(text);
    }
    onCaptionFinish(Result.create(OCR, StringBuilderUtils.getAggregateText(texts)));
  }
}
