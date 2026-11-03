# Offline Vietnamese banknote recognition

Meridian's Live recognition has a Currency mode. Its Android code runs locally with TensorFlow Lite and never sends camera frames to Roboflow. The mode stays disabled, with a Vietnamese spoken explanation, if the trained weights are absent.

## Selected Roboflow model

- Source: https://universe.roboflow.com/ts-workspace-fzqzj/vietnamese-currency-lgi9i-dgsa5
- Model ID: `vietnamese-currency-lgi9i-dgsa5/1`.
- Architecture: YOLO11n object detection.
- Classes listed on the Roboflow model page (verify the exported class-index mapping): 1000, 10000, 100000, 2000, 20000, 200000, 5000, 50000, 500000.
- The Roboflow page states CC BY 4.0 for the dataset. Permissions for distributing trained weights must be verified separately.

## Required local model asset

Export the trained checkpoint from the selected model as a TensorFlow Lite detection model with an NHWC input and a raw, un-NMS'ed YOLO output. Save it as:

`talkback/src/main/assets/vn_currency_yolo11n.tflite`

Expected model I/O:

- A single input `[1,height,width,3]`, RGB, float32 normalized to [0,1] or uint8 with tensor quantization parameters.
- A single raw YOLO output `[1,13,N]` or `[1,N,13]`, float32 or uint8, where four coordinates precede nine class confidences. Postprocessed/NMS exports need an adapter.
- The nine class confidences must follow the exact order listed above; check the exported model metadata before adding weights.
- Recommended export input size: 640x640. Do not use a COCO-only checkpoint. It would not recognize VND denominations.
- Keep the model only in the shipped APK. No remote API key or runtime model downloads are used.
- If weights cannot be exported or their distribution rights cannot be confirmed, do not substitute a different model silently.

## Behavior

Each camera frame is processed on a background worker. The highest-confidence denomination above 0.78 is selected, without summing multiple notes. It must match in two consecutive analyzed frames before being spoken. An unchanged note is not repeatedly announced; three empty/uncertain frames reset the announcement. Other recognition modes and the existing loading sound/haptic behavior remain independent.

## Verification still needed

No trained weights are included in this commit, so end-to-end banknote inference has not been validated. Before calling this feature complete, obtain the verified checkpoint, inspect its tensor dimensions, class mapping and license, add the asset, run focused CI tests, and validate each denomination, dim lighting, angle, false positives and offline operation on actual devices. Benchmarks reported by Roboflow are not mobile-device accuracy benchmarks.
