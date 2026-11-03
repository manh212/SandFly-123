package com.sandfly.offlinelicenser;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.meridian.licensing.RecoveryProtocol;
import java.security.Signature;
import java.util.Iterator;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  private static final int AUTH_REQUEST = 501;
  private TextView state, publicKeyText, licenseText, deviceCount;
  private EditText requestInput, nameInput, tokenInput;
  private Button issueButton;
  private String pendingRequest, pendingName, issued;
  private boolean authBusy;
  private CancellationSignal authentication;

  @Override public void onCreate(Bundle bundle) {
    super.onCreate(bundle);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    ScrollView scroll = new ScrollView(this);
    LinearLayout column = new LinearLayout(this);
    column.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (20 * getResources().getDisplayMetrics().density);
    column.setPadding(padding, padding, padding, padding);
    scroll.addView(column);
    setContentView(scroll);
    title(column, "Quản trị kích hoạt Meridian");
    state = label(column, "Đang đọc trạng thái…");
    if (!OfficialInstall.verified(this)) {
      state.setText("Công cụ chưa có chữ ký chính thức. Hãy cài APK Meridian Quản trị từ dự án.");
      return;
    }
    label(column, "Công cụ này cài song song với bản cũ. Giữ nguyên bản cũ để bảo toàn khóa và giấy phép đã cấp trước đây.");
    button(column, "Tạo khóa cấp phép", view -> createKey());
    publicKeyText = label(column, "Chưa tạo khóa cấp phép.");
    publicKeyText.setTextIsSelectable(true);
    button(column, "Sao chép khóa công khai", view -> SyncCoordinator.WORKER.execute(() -> {
      try { String value = IssuerKeys.publicKey(); onUi(() -> copy("Khóa công khai", value)); }
      catch (Exception e) { error(e); }
    }));
    title(column, "Kết nối GitHub");
    label(column, "Tạo fine-grained token cho riêng manh212/SandFly-123, quyền Contents: Read and write. Token được mã hóa trên điện thoại và không được đưa lên GitHub.");
    button(column, "Tạo token GitHub", view -> startActivity(new Intent(Intent.ACTION_VIEW,
        Uri.parse("https://github.com/settings/personal-access-tokens/new"))));
    tokenInput = input(column, "Token GitHub");
    tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    tokenInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    button(column, "Lưu token và kết nối", view -> {
      String token = tokenInput.getText().toString().trim();
      SyncCoordinator.WORKER.execute(() -> {
        try {
          new IssuerStore(this).token(token);
          onUi(() -> { tokenInput.setText(""); state.setText("Đã lưu token trên điện thoại."); });
          synchronize();
        } catch (Exception e) { error(e); }
      });
    });
    button(column, "Đồng bộ lại GitHub", view -> synchronize());
    title(column, "Cấp phép thiết bị");
    requestInput = input(column, "Mã yêu cầu SFREQ2");
    requestInput.setMinLines(3);
    nameInput = input(column, "Tên người nhận, chỉ lưu trên điện thoại");
    issueButton = button(column, "Cấp phép và lưu GitHub", view -> authenticate());
    licenseText = label(column, "Chưa cấp giấy phép trong phiên này.");
    licenseText.setTextIsSelectable(true);
    button(column, "Sao chép giấy phép", view -> {
      if (issued != null) copy("Giấy phép", issued);
      else state.setText("Chưa có giấy phép để sao chép.");
    });
    title(column, "Thiết bị đã cấp phép");
    deviceCount = label(column, "Đang đọc danh sách…");
    label(column, "Không gỡ hoặc xóa dữ liệu công cụ này. Khóa riêng trong phần cứng không thể xuất hoặc khôi phục từ GitHub. Tệp công khai chỉ có giấy phép đã ký và định danh đã băm.");
    refresh();
  }

  @Override protected void onResume() {
    super.onResume();
    if (OfficialInstall.verified(this)) synchronize();
  }

  @Override protected void onDestroy() {
    if (authentication != null) authentication.cancel();
    super.onDestroy();
  }

  private void onUi(Runnable action) {
    runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); });
  }

  private TextView label(LinearLayout root, String value) {
    TextView text = new TextView(this);
    text.setText(value);
    text.setTextSize(16);
    text.setPadding(0, 12, 0, 12);
    root.addView(text);
    return text;
  }

  private void title(LinearLayout root, String value) {
    TextView title = label(root, value);
    title.setTextSize(21);
  }

  private EditText input(LinearLayout root, String name) {
    EditText input = new EditText(this);
    input.setHint(name);
    root.addView(input);
    return input;
  }

  private Button button(LinearLayout root, String name, View.OnClickListener listener) {
    Button button = new Button(this);
    button.setText(name);
    button.setAllCaps(false);
    button.setOnClickListener(listener);
    root.addView(button);
    return button;
  }

  private void copy(String name, String value) {
    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(name, value));
    state.setText("Đã sao chép.");
  }

  private void error(Exception exception) {
    String message = exception instanceof IllegalArgumentException || exception instanceof IllegalStateException
        ? exception.getMessage() : "Không thực hiện được. Hãy kiểm tra khóa bảo mật và thử lại.";
    onUi(() -> state.setText(message));
  }

  private void createKey() {
    state.setText("Đang tạo khóa trong phần cứng bảo mật…");
    SyncCoordinator.WORKER.execute(() -> {
      try {
        IssuerKeys.create(getApplicationContext());
        synchronize();
      } catch (Exception e) { error(e); }
      refresh();
    });
  }

  private void synchronize() {
    SyncCoordinator.WORKER.execute(() -> {
      try {
        if (IssuerKeys.exists() && !new IssuerStore(this).token().isEmpty()) {
          boolean retry = SyncCoordinator.sync(getApplicationContext(), () -> false);
          if (retry) SyncCoordinator.schedule(getApplicationContext());
        }
      } catch (Exception e) { error(e); }
      refresh();
    });
  }

  private void refresh() {
    SyncCoordinator.WORKER.execute(() -> {
      try {
        boolean exists = IssuerKeys.exists();
        String key = exists ? IssuerKeys.securityLevel() + "\n" + IssuerKeys.publicKey()
            : "Chưa tạo khóa cấp phép.";
        IssuerStore store = new IssuerStore(this);
        String status = store.status();
        JSONObject records = store.records();
        StringBuilder report = new StringBuilder("Số thiết bị: ").append(records.length());
        Iterator<String> ids = records.keys();
        while (ids.hasNext()) {
          String id = ids.next();
          JSONObject entry = records.getJSONObject(id);
          report.append("\n").append(entry.optString("name")).append(" • ")
              .append(id.substring(0, 12)).append(entry.optBoolean("synced")
                  ? " • Đã lưu GitHub" : " • Chờ lưu GitHub");
        }
        onUi(() -> {
          publicKeyText.setText(key);
          deviceCount.setText(report.toString());
          if (!authBusy) state.setText(status);
        });
      } catch (Exception e) { error(e); }
    });
  }

  private void authenticate() {
    if (authBusy) return;
    try {
      pendingRequest = requestInput.getText().toString().trim();
      pendingName = nameInput.getText().toString().trim();
      RecoveryProtocol.deviceId(pendingRequest);
      if (pendingName.length() > 80 || pendingName.chars().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Tên người nhận không hợp lệ.");
      }
      authBusy = true;
      issueButton.setEnabled(false);
      if (Build.VERSION.SDK_INT >= 30) {
        SyncCoordinator.WORKER.execute(() -> {
          try {
            Signature signer = IssuerKeys.signer();
            onUi(() -> {
              try { prompt(signer); }
              catch (RuntimeException e) { finishAuthentication(); error(e); }
            });
          } catch (Exception e) { finishAuthentication(); error(e); }
        });
      } else {
        KeyguardManager guard = getSystemService(KeyguardManager.class);
        if (guard == null || !guard.isDeviceSecure()) {
          throw new IllegalStateException("Hãy đặt PIN hoặc mật khẩu điện thoại.");
        }
        Intent intent = guard.createConfirmDeviceCredentialIntent("Xác nhận cấp phép",
            "Ký giấy phép cho thiết bị đã chọn");
        if (intent == null) throw new IllegalStateException("Không mở được xác thực màn hình.");
        startActivityForResult(intent, AUTH_REQUEST);
      }
    } catch (Exception e) { finishAuthentication(); error(e); }
  }

  private void prompt(Signature signer) {
    if (Build.VERSION.SDK_INT < 30) return;
    authentication = new CancellationSignal();
    new BiometricPrompt.Builder(this)
        .setTitle("Xác nhận cấp phép")
        .setSubtitle("Thiết bị " + RecoveryProtocol.deviceId(pendingRequest).substring(0, 12))
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
            | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        .setConfirmationRequired(true)
        .build()
        .authenticate(new BiometricPrompt.CryptoObject(signer), authentication, getMainExecutor(),
            new BiometricPrompt.AuthenticationCallback() {
              @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                authentication = null;
                BiometricPrompt.CryptoObject crypto = result.getCryptoObject();
                if (crypto == null || crypto.getSignature() == null) {
                  finishAuthentication();
                  state.setText("Không nhận được xác thực cho khóa cấp phép.");
                } else {
                  issue(crypto.getSignature());
                }
              }
              @Override public void onAuthenticationError(int code, CharSequence text) {
                authentication = null;
                finishAuthentication();
                state.setText("Chưa xác thực cấp phép.");
              }
            });
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != AUTH_REQUEST) return;
    if (resultCode != RESULT_OK) {
      finishAuthentication();
      state.setText("Đã hủy cấp phép.");
      return;
    }
    issue(null);
  }

  private void issue(Signature signer) {
    String request = pendingRequest;
    String name = pendingName;
    SyncCoordinator.SIGNER.execute(() -> {
      try {
        Signature authenticatedSigner = signer == null ? IssuerKeys.signer() : signer;
        String license = RecoveryProtocol.issue(request, authenticatedSigner, System.currentTimeMillis() / 1000L);
        SyncCoordinator.WORKER.execute(() -> {
          try {
            new IssuerStore(this).record(request, name, license);
            onUi(() -> {
              issued = license;
              licenseText.setText(license);
              state.setText("Đã cấp phép và lưu trên điện thoại. Đang đồng bộ GitHub…");
            });
            SyncCoordinator.schedule(getApplicationContext());
            synchronize();
          } catch (Exception e) { error(e); }
          finally { finishAuthentication(); }
        });
      } catch (Exception e) { finishAuthentication(); error(e); }
    });
  }

  private void finishAuthentication() {
    onUi(() -> {
      authBusy = false;
      pendingRequest = null;
      pendingName = null;
      if (issueButton != null) issueButton.setEnabled(true);
    });
  }
}
