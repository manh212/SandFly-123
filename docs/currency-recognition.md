# Đọc tiền ngoại tuyến trong Meridian

Chế độ Đọc tiền sử dụng mô hình đã huấn luyện của dự án MoneyVision, không phải API Roboflow hay mô hình COCO chung.

## Tệp mô hình

- Nguồn: https://github.com/hezioquan12/vietnamese-money-vision
- Tác giả: Nguyễn Quang Toàn (hezioquan12).
- Đường dẫn tại commit nguồn `2c46d754c15f6739cb2467d29ebcb29ed1e4b4ec`: `android/app/src/main/assets/models/yolo26_money_quant.tflite`.
- Tên mô hình trong Meridian: `vn_currency_yolo11n.tflite` (giữ tương thích với mã đã triển khai).
- Định dạng: TensorFlow Lite / LiteRT (mô hình MoneyVision được mô tả là YOLO26n INT8 weights, FP32 activations).
- SHA-256: `b3d3727f9ca1fb41789071e5590f7677bca83e9dd61505b322580c52251fddd9`.
- Dung lượng: 3.027.064 byte.
- Giấy phép kho nguồn: MIT; bản giấy phép và ghi nhận tác giả được đưa vào assets ứng dụng.

## Đóng gói

Gradle tải tệp mô hình từ URL GitHub đã ghim commit trong công đoạn build, kiểm tra SHA-256, đặt vào `talkback/build/generated/currency-model-assets/` rồi đóng gói vào APK. Nếu tải lỗi hoặc checksum không khớp, build phải thất bại, không âm thầm tạo APK thiếu mô hình. Cần mạng **chỉ khi build** lần đầu hoặc khi không còn tệp trong cache Gradle; **nhận dạng trên điện thoại hoàn toàn ngoại tuyến**.

## Đầu vào và đầu ra

- Input `[1,3,640,640]` (NCHW), RGB float32 0..1; bộ đọc cũng xử lý được NHWC.
- Output `[1,13,8400]`: 4 tọa độ, 9 giá trị lớp.
- Thứ tự nhãn theo tài liệu nguồn: 1000, 10000, 100000, 2000, 20000, 200000, 5000, 50000, 500000.
- Chọn một tờ tiền có độ tin cậy cao nhất; không tính tổng.
- Ngưỡng tin cậy 0,78 và hai khung hình trùng mệnh giá để đọc.
- Với riêng chế độ Đọc tiền, phân tích camera tối đa mỗi 450 ms, có cơ chế bỏ khung hình khi còn xử lý.
- Không truy cập mạng, không gửi ảnh đến Gemini, Roboflow hoặc dịch vụ khác trong chế độ Đọc tiền.

## Lưu ý

Các thông số và nhãn lấy từ tài liệu MoneyVision; chưa có kiểm chứng độc lập độ chính xác trên Galaxy A05. Việc có tệp và chạy được trên Android không đảm bảo nhận dạng đúng mọi trường hợp. Cần thử trên đủ chín mệnh giá, ảnh không có tiền, tiền mờ, tiền nghiêng và thiếu sáng. Chế độ này chỉ hỗ trợ đọc mệnh giá, không xác định tiền giả.
