# SandFly - Công cụ cấp mã Android (ngoại tuyến)

Ứng dụng Android dành cho quản trị viên để tạo chữ ký ECDSA trên điện thoại. Khóa riêng chỉ nằm ở Android Keystore. Không cần máy chủ hay Internet khi cấp mã.

## Tạo APK bằng điện thoại

1. Mở **Actions** trên GitHub trong repository này.
2. Chọn **Build SandFly License Issuer APK**.
3. Chọn lần chạy mới nhất có dấu xanh.
4. Mở **Artifacts**, chọn **SandFly-License-Issuer-APK** rồi giải nén ZIP.
5. Cài **app-debug.apk** trên điện thoại **quản trị** (Android 8+).
6. Mở app, tạo khóa cấp mã và sao chép chuỗi **SFPUB1.**.

> Để giữ được khóa Android Keystore, không xóa dữ liệu hoặc gỡ ứng dụng quản trị. APK debug do Actions tạo có thể có khóa ký khác giữa các lần chạy và không nâng cấp đè được. Trước khi dùng lâu dài, nên thiết lập ký APK bằng khóa release ổn định.

## Bật kiểm tra mã trong Meridian

Trong repository: **Settings → Secrets and variables → Actions → Variables → New repository variable**, tạo biến:

- Name: `SANDFLY_ISSUER_PUBLIC_KEY`
- Value: **toàn bộ chuỗi SFPUB1.** được tạo ở bước trên.

Sau đó phát hành APK Meridian mới bằng workflow chuẩn. Chỉ cần giá trị khóa công khai, **không bao giờ đưa private key, keystore hay mật khẩu cấp mã lên repository hoặc GitHub Actions**.

Khi đã cấu hình khóa công khai, ứng dụng chỉ cho truy cập kênh cập nhật tích hợp nếu giấy phép SFLIC1 khớp với khóa thiết bị hiện tại. Người dùng mở **Cài đặt → Kích hoạt cập nhật SandFly**, sao chép `SFREQ1`, gửi cho quản trị viên, nhận `SFLIC1`, dán để kích hoạt. Không cần nhập lại mã sau khi khởi động lại hoặc cập nhật ứng dụng giữ nguyên app ID và dữ liệu.

**Hiện tại:** nếu biến trên chưa được cấu hình, bản build vẫn cho phép cập nhật thông thường để tránh vô tình cắt quyền cập nhật của người dùng cũ. Không coi việc merge mã nguồn là đã bật kích hoạt.

**Giới hạn:** bảo vệ này chỉ áp dụng cho trình cập nhật có sẵn trong ứng dụng. Không ngăn cài APK thủ công hoặc người có mã nguồn biên dịch bản khác. Dịch vụ đọc màn hình không bao giờ bị khóa. Các mã đã phát hành không thể thu hồi từ xa. Không tự động đếm thiết bị đã cài, chỉ đếm danh sách cấp phép trên điện thoại quản trị.

**Quyền riêng tư:** repository này hiện là public. Ứng dụng cấp mã không chứa khóa riêng, nhưng người khác có thể tải mã nguồn và tự biên dịch một công cụ cấp mã của riêng họ; các mã đó không được Meridian chính thức chấp nhận vì không khớp khóa công khai do bạn cấu hình.
