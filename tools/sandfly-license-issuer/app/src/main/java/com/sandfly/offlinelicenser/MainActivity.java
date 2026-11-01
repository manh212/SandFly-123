package com.sandfly.offlinelicenser;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.*;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;
import java.util.Iterator;

/** Android-only offline issuance. No network permission and no embedded private key. */
public final class MainActivity extends Activity {
  private static final int AUTH_REQUEST = 501;
  private TextView state, publicKeyText, licenseText, deviceCount;
  private EditText requestInput, nameInput;
  private String pendingRequest, pendingName, issued;
  private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }
  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    ScrollView scroll = new ScrollView(this);
    LinearLayout column = new LinearLayout(this);
    column.setPadding(dp(20), dp(20), dp(20), dp(32));
    column.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(column); setContentView(scroll);
    title(column, "SandFly - Công cụ cấp mã thiết bị");
    state = label(column, "Khởi tạo");
    button(column, "1. Tạo khóa cấp mã (chỉ lần đầu)", v -> createKey());
    publicKeyText = label(column, "Khóa công khai: chưa tạo");
    publicKeyText.setTextIsSelectable(true);
    button(column, "Sao chép khóa công khai SFPUB1", v -> {
      try { copy("Khóa công khai", IssuerProtocol.publicKey()); }
      catch(Exception e){ error(e); }
    });
    title(column, "2. Yêu cầu kích hoạt của người dùng");
    requestInput = new EditText(this);
    requestInput.setHint("Dán mã SFREQ1"); requestInput.setContentDescription("Mã yêu cầu SFREQ1");
    requestInput.setMinLines(3); column.addView(requestInput);
    nameInput = new EditText(this);
    nameInput.setHint("Tên người nhận (không bắt buộc)");
    nameInput.setContentDescription("Tên người nhận");
    column.addView(nameInput);
    button(column, "3. Tạo mã cho một thiết bị", v -> authenticate());
    title(column, "Mã kích hoạt được cấp");
    licenseText = label(column, "Chưa có mã");
    licenseText.setTextIsSelectable(true);
    button(column, "Sao chép mã SFLIC1", v -> {
      if (issued == null) { state.setText("Bạn chưa cấp mã."); return; }
      copy("Mã kích hoạt", issued);
    });
    title(column, "Thiết bị đã cấp mã");
    deviceCount = label(column, "Chưa có thiết bị");
    label(column, "Khóa bí mật chỉ ở Android Keystore trên máy quản trị. Không gỡ hoặc xóa dữ liệu ứng dụng. "
      + "Không thể thu hồi giấy phép từ xa. Cần kết nối khóa công khai với bản Meridian trước khi phát hành.");
    refresh();
  }
  private void title(LinearLayout root, String text) {
    TextView t = label(root, text); t.setTextSize(21); t.setPadding(0, dp(18), 0, dp(5));
  }
  private TextView label(LinearLayout root, String text) {
    TextView t = new TextView(this); t.setText(text); t.setTextSize(16); t.setPadding(0, dp(6), 0, dp(6));
    root.addView(t); return t;
  }
  private void button(LinearLayout root, String text, View.OnClickListener click) {
    Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setOnClickListener(click); root.addView(b);
  }
  private void copy(String title, String value) {
    ClipboardManager cb = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
    if (cb == null) { state.setText("Không sử dụng được bộ nhớ tạm."); return; }
    cb.setPrimaryClip(ClipData.newPlainText(title, value));
    state.setText("Đã sao chép: " + title);
  }
  private void error(Exception e) { state.setText("Không thực hiện được: " + e.getMessage()); }
  private void createKey() {
    try {
      IssuerKeys.create(this); refresh();
      state.setText("Khóa cấp mã đã sẵn sàng. Sao chép SFPUB1 để cấu hình Meridian.");
    } catch(Exception e) { error(e); }
  }
  private void refresh() {
    try {
      boolean exists = IssuerKeys.exists();
      state.setText(exists ? "Khóa nhà phát hành sẵn sàng" : "Chưa tạo khóa nhà phát hành");
      publicKeyText.setText(exists ? IssuerProtocol.publicKey() : "Chưa có khóa công khai");
    } catch(Exception e) { error(e); }
    try {
      JSONObject items = new JSONObject(getPreferences(MODE_PRIVATE).getString("devices","{}"));
      StringBuilder report = new StringBuilder("Số thiết bị: ").append(items.length());
      Iterator<String> keys = items.keys();
      while(keys.hasNext()) {
        String id = keys.next();
        JSONObject item = items.optJSONObject(id);
        report.append("\n").append(item == null ? "" : item.optString("name", ""))
          .append(" • ").append(id.substring(0, Math.min(14, id.length())));
      }
      deviceCount.setText(report.toString());
    } catch(Exception e) { deviceCount.setText("Không đọc được lịch sử cấp mã."); }
  }
  private void authenticate() {
    try {
      if (!IssuerKeys.exists()) throw new IllegalStateException("Hãy tạo khóa cấp mã trước");
      pendingRequest = requestInput.getText().toString().trim();
      pendingName = nameInput.getText().toString().trim();
      IssuerProtocol.requestKey(pendingRequest);
      if(pendingName.length() > 80) throw new IllegalArgumentException("Tên quá dài");
      KeyguardManager keyguard = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
      if(keyguard == null || !keyguard.isDeviceSecure()) throw new IllegalStateException("Hãy đặt PIN hoặc mật khẩu điện thoại");
      Intent auth = keyguard.createConfirmDeviceCredentialIntent("Xác nhận cấp mã", "Xác thực để ký giấy phép cho một thiết bị");
      if(auth == null) throw new IllegalStateException("Không thể mở xác thực màn hình");
      startActivityForResult(auth, AUTH_REQUEST);
    } catch(Exception e) { error(e); }
  }
  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if(requestCode != AUTH_REQUEST) return;
    if(resultCode != RESULT_OK) { state.setText("Đã hủy yêu cầu cấp mã"); return; }
    try {
      if(pendingRequest == null) throw new IllegalStateException("Yêu cầu đã hết hạn");
      issued = IssuerProtocol.issue(pendingRequest, pendingName, IssuerKeys.privateKey());
      licenseText.setText(issued);
      String id = IssuerProtocol.fingerprint(IssuerProtocol.requestKey(pendingRequest));
      JSONObject entries = new JSONObject(getPreferences(MODE_PRIVATE).getString("devices","{}"));
      JSONObject entry = new JSONObject();
      entry.put("name", pendingName == null ? "" : pendingName);
      entry.put("issuedAt", System.currentTimeMillis());
      entries.put(id, entry);
      if (!getPreferences(MODE_PRIVATE).edit().putString("devices", entries.toString()).commit()) {
        throw new IllegalStateException("Không lưu được lịch sử cấp phép");
      }
      refresh(); state.setText("Đã tạo mã thành công. Có thể sao chép SFLIC1.");
    } catch(Exception e) { error(e); }
    finally { pendingRequest = null; pendingName = null; }
  }
}
