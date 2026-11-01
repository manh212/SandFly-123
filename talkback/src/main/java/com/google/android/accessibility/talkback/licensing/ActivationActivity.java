package com.google.android.accessibility.talkback.licensing;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

/** Offline activation screen. Accessibility speech remains enabled without a license. */
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
    title.setText("Kích hoạt cập nhật SandFly"); title.setTextSize(22); layout.addView(title);
    status = new TextView(this); status.setTextSize(17); layout.addView(status);
    TextView help = new TextView(this);
    help.setText("Gửi yêu cầu SFREQ1 cho người quản lý. Nhận mã SFLIC1 và dán vào đây. Kích hoạt không cần Internet.");
    layout.addView(help);
    TextView requestText = new TextView(this); requestText.setTextIsSelectable(true); layout.addView(requestText);
    try { request = DeviceLicenseManager.request(this); requestText.setText(request); }
    catch (Exception e) { requestText.setText(e.getMessage()); }
    addButton(layout, "Sao chép yêu cầu kích hoạt", v -> {
      ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
      if (clipboard != null && request != null) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Yêu cầu kích hoạt", request));
        status.setText("Đã sao chép yêu cầu.");
      }
    });
    EditText input = new EditText(this);
    input.setHint("Dán mã SFLIC1"); input.setContentDescription("Mã kích hoạt");
    layout.addView(input, new LinearLayout.LayoutParams(-1, -2));
    addButton(layout, "Xác nhận kích hoạt", v -> {
      try { DeviceLicenseManager.activate(this, input.getText().toString()); input.setText(""); refresh(); }
      catch (Exception e) { status.setText("Không thể kích hoạt: " + e.getMessage()); }
    });
    refresh();
  }
  private void addButton(LinearLayout layout, String title, View.OnClickListener listener) {
    Button button = new Button(this); button.setAllCaps(false); button.setText(title);
    button.setOnClickListener(listener); layout.addView(button);
  }
  private void refresh() {
    status.setText(!DeviceLicenseManager.configured() ? "Bản dựng chưa cấu hình khóa cấp phép."
      : DeviceLicenseManager.isLicensed(this) ? "Thiết bị đã kích hoạt cập nhật."
      : "Cần kích hoạt thiết bị để nhận và cài bản cập nhật.");
  }
}