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
  private TextView enrollmentStatus;
  private EditText fullName, description;
  private Button submit;
  private boolean visible, checking;
  private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable poll = new Runnable() {
    @Override public void run() {
      if (!visible) return;
      checkEnrollment(null, null);
      handler.postDelayed(this, 15_000L);
    }
  };
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
    addButton(layout, "Nhật ký và báo lỗi", v -> startActivity(new android.content.Intent(this, SupportActivity.class)));
    fullName = new EditText(this); fullName.setHint("Họ và tên");
    fullName.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
    layout.addView(fullName);
    description = new EditText(this); description.setHint("Mô tả yêu cầu đăng ký");
    description.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    description.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1000)});
    layout.addView(description);
    enrollmentStatus = new TextView(this); layout.addView(enrollmentStatus);
    submit = new Button(this); submit.setAllCaps(false); submit.setText("Gửi yêu cầu đăng ký");
    submit.setOnClickListener(v -> checkEnrollment(fullName.getText().toString(), description.getText().toString()));
    layout.addView(submit);
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
    refresh();
  }
  @Override protected void onResume() {
    super.onResume();
    SupportClient.opened(this);
    ActivationReminder.setActivationVisible(true);
    visible = true;
    handler.post(poll);
    restore(false);
  }
  @Override protected void onPause() {
    ActivationReminder.setActivationVisible(false);
    visible = false;
    handler.removeCallbacks(poll);
    super.onPause();
  }
  private void addButton(LinearLayout layout, String title, View.OnClickListener listener) {
    Button button = new Button(this); button.setAllCaps(false); button.setText(title);
    button.setOnClickListener(listener); layout.addView(button);
  }
  private void restore(boolean force) {
    if (!ApkSignatureVerifier.isOfficial(this)) { refresh(); return; }
    status.setText("Đang kiểm tra giấy phép…");
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
          if (DeviceLicenseManager.isLicensed(activity)) activity.refresh();
          else activity.status.setText("Chưa có giấy phép trực tuyến.");
          break;
        case OFFLINE:
          activity.status.setText(DeviceLicenseManager.status(activity) + "\nChưa kết nối được GitHub.");
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
      : DeviceLicenseManager.status(this));
  }
  private void checkEnrollment(String name, String details) {
    if (checking) return;
    checking = true;
    submit.setEnabled(false);
    Enrollment.check(this, name, details, (message, locked, savedName, savedDescription) -> {
      checking = false;
      if (isFinishing() || isDestroyed()) return;
      enrollmentStatus.setText(message);
      if (locked) {
        if (!fullName.getText().toString().equals(savedName)) fullName.setText(savedName);
        if (!description.getText().toString().equals(savedDescription)) description.setText(savedDescription);
      }
      fullName.setEnabled(!locked); description.setEnabled(!locked); submit.setEnabled(!locked);
      if (DeviceLicenseManager.isLicensed(this)) refresh();
    });
  }
}
