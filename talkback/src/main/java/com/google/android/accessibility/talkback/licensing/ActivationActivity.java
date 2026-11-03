package com.google.android.accessibility.talkback.licensing;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.lang.ref.WeakReference;

public final class ActivationActivity extends Activity {
  private TextView status;
  private String request;
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    ScrollView outer = new ScrollView(this);
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    int p = (int)(20 * getResources().getDisplayMetrics().density);
    layout.setPadding(p,p,p,p); outer.addView(layout); setContentView(outer);
    TextView title = new TextView(this);
    title.setText("Đăng ký Meridian"); title.setTextSize(22); layout.addView(title);
    status = new TextView(this); status.setTextSize(17); layout.addView(status);
    TextView help = new TextView(this);
    help.setText("Gửi mã yêu cầu cho người quản trị để cấp phép lần đầu. Khi cài lại trên cùng máy, Meridian tự tìm giấy phép đã lưu trên GitHub khi có Internet. Định danh đã băm được dùng để tra cứu, không gửi tên người dùng.");
    layout.addView(help);
    TextView requestText = new TextView(this); requestText.setTextIsSelectable(true); layout.addView(requestText);
    if (ApkSignatureVerifier.isOfficial(this)) {
      try { request = DeviceLicenseManager.recoveryRequest(this); requestText.setText(request); }
      catch (Exception e) { requestText.setText(e.getMessage()); }
    } else {
      requestText.setText("Không thể tạo yêu cầu đăng ký từ bản cài đặt chưa được xác thực.");
    }
    addButton(layout, "Sao chép yêu cầu kích hoạt", v -> {
      ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
      if (clipboard != null && request != null) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Yêu cầu kích hoạt", request));
        status.setText("Đã sao chép yêu cầu.");
      }
    });
    addButton(layout, "Khôi phục kích hoạt", v -> restore(true));
    EditText input = new EditText(this);
    input.setHint("Mã kích hoạt");
    layout.addView(input, new LinearLayout.LayoutParams(-1, -2));
    addButton(layout, "Xác nhận kích hoạt", v -> {
      try { DeviceLicenseManager.activate(this, input.getText().toString()); ActivationReminder.onActivated(); input.setText(""); refresh(); }
      catch (Exception e) { status.setText("Không thể kích hoạt: " + e.getMessage()); }
    });
    refresh();
  }
  @Override protected void onResume() {
    super.onResume();
    ActivationReminder.setActivationVisible(true);
    restore(false);
  }
  @Override protected void onPause() {
    ActivationReminder.setActivationVisible(false);
    super.onPause();
  }
  private void addButton(LinearLayout layout, String title, View.OnClickListener listener) {
    Button button = new Button(this); button.setAllCaps(false); button.setText(title);
    button.setOnClickListener(listener); layout.addView(button);
  }
  private void restore(boolean force) {
    if (DeviceLicenseManager.isLicensed(this)) { refresh(); return; }
    if (!ApkSignatureVerifier.isOfficial(this)) { refresh(); return; }
    status.setText("Đang tìm giấy phép đã lưu…");
    WeakReference<ActivationActivity> reference = new WeakReference<>(this);
    LicenseRecovery.start(this, force, result -> {
      ActivationActivity activity = reference.get();
      if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
      switch (result) {
        case RESTORED:
        case ALREADY_LICENSED:
          activity.refresh();
          break;
        case NOT_FOUND:
          activity.status.setText("Chưa tìm thấy giấy phép. Hãy gửi mã yêu cầu để được cấp phép lần đầu.");
          break;
        case OFFLINE:
          activity.status.setText("Chưa kết nối được GitHub. Kết nối Internet rồi chọn Khôi phục kích hoạt.");
          break;
        case NOT_CONFIGURED:
          activity.status.setText("Bản Meridian này chưa tích hợp khóa cấp phép mới. Hãy cài bản mới nhất sau khi quản trị viên kết nối công cụ với GitHub.");
          break;
        default:
          activity.status.setText("Giấy phép tải về không hợp lệ hoặc không khớp thiết bị.");
      }
    });
  }
  private void refresh() {
    status.setText(!ApkSignatureVerifier.isOfficial(this) ? "Bản cài đặt Meridian chưa được xác thực. Vui lòng cài APK do nhà phát hành ký chính thức."
      : !DeviceLicenseManager.configured() ? "Bản dựng chưa cấu hình khóa cấp phép."
      : DeviceLicenseManager.isLicensed(this) ? "Thiết bị đã đăng ký. Meridian sẽ tiếp tục hoạt động bình thường."
      : "Chưa đăng ký. Trình đọc màn hình sẽ tự tắt khi hết thời gian đếm ngược 60 giây.");
  }
}
