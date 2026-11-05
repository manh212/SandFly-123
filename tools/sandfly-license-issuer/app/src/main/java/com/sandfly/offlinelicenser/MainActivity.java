package com.sandfly.offlinelicenser;

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
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import io.meridian.licensing.RecoveryProtocol;
import io.meridian.licensing.LicensePolicy;
import java.security.Signature;
import java.util.Iterator;
import org.json.JSONObject;

public final class MainActivity extends ComponentActivity {
  private final java.util.concurrent.atomic.AtomicBoolean syncBusy = new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicBoolean syncAgain = new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicBoolean refreshBusy = new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicInteger refreshGeneration = new java.util.concurrent.atomic.AtomicInteger();
  private static final int AUTH_REQUEST = 501;
  private static final int BROWSER_REQUEST = 502;
  private TextView state, publicKeyText, licenseText, deviceCount;
  private EditText requestInput, nameInput;
  private Button issueButton, loginButton;
  private String pendingRequest, pendingName, issued;
  private String pendingDescription, pendingUid;
  private LicensePolicy pendingPolicy;
  private AdminPanel panel;
  private SupportDesk support;
  private LinearLayout ownerSection;
  private volatile boolean ownerAuthorized;
  private boolean authBusy;
  private boolean loginCompleting;
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
    title(column, "Meridian Quản trị");
    state = label(column, "");
    if (!OfficialInstall.verified(this)) {
      state.setText("Công cụ chưa có chữ ký chính thức. Hãy cài APK Meridian Quản trị từ dự án.");
      return;
    }
    support = new SupportDesk(this);
    support.attach(column);
    ownerSection = new LinearLayout(this);
    ownerSection.setOrientation(LinearLayout.VERTICAL);
    ownerSection.setVisibility(View.GONE);
    column.addView(ownerSection);
    column = ownerSection;
    label(column, "Công cụ này cài song song với bản cũ. Giữ nguyên bản cũ để bảo toàn khóa và giấy phép đã cấp trước đây.");
    button(column, "Tạo khóa cấp phép", view -> createKey());
    publicKeyText = label(column, "Chưa tạo khóa cấp phép.");
    publicKeyText.setTextIsSelectable(true);
    button(column, "Sao chép khóa công khai", view -> SyncCoordinator.WORKER.execute(() -> {
      try { String value = IssuerKeys.publicKey(); onUi(() -> copy("Khóa công khai", value)); }
      catch (Exception e) { error(e); }
    }));
    title(column, "Kết nối GitHub");
    label(column, "Đăng nhập tài khoản manh212 một lần trong trình duyệt. Xác nhận tạo kết nối và chỉ chọn SandFly-123 khi GitHub hỏi quyền truy cập. Xong ứng dụng tự trở về và tự đồng bộ giấy phép; không cần tạo hoặc nhập token.");
    loginButton = button(column, "Đăng nhập GitHub", view -> login());
    button(column, "Tiếp tục từ liên kết đã sao chép", view -> continueFromClipboard());
    button(column, "Hủy đăng nhập GitHub", view -> SyncCoordinator.WORKER.execute(() -> {
      try { BrowserConnection.cancel(getApplicationContext()); onUi(() -> loginButton.setEnabled(true)); refresh(); }
      catch (Exception e) { error(e); }
    }));
    button(column, "Đồng bộ lại GitHub", view -> synchronize());
    title(column, "Cấp phép thiết bị");
    requestInput = input(column, "Mã yêu cầu SFREQ2");
    requestInput.setMinLines(3);
    nameInput = input(column, "Tên người nhận");
    panel = new AdminPanel(this);
    issueButton = button(column, "Chọn gói và cấp phép", view -> panel.issue(
        requestInput.getText().toString().trim(), nameInput.getText().toString().trim(), "", ""));
    licenseText = label(column, "Chưa cấp giấy phép trong phiên này.");
    licenseText.setTextIsSelectable(true);
    button(column, "Sao chép giấy phép", view -> {
      if (issued != null) copy("Giấy phép", issued);
      else state.setText("Chưa có giấy phép để sao chép.");
    });
    title(column, "Thiết bị đã cấp phép");
    deviceCount = label(column, "Đang đọc danh sách…");
    panel.attach(column);
    label(column, "Không gỡ hoặc xóa dữ liệu công cụ này. Khóa riêng trong phần cứng không thể xuất hoặc khôi phục từ GitHub. Tệp công khai chỉ có giấy phép đã ký và định danh đã băm.");
    BrowserConnection.observe(this);
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (intent.getData() != null && OfficialInstall.verified(this)) completeLogin(intent.getData());
  }

  private void login() {
    if (!ownerAuthorized) return;
    loginButton.setEnabled(false);
    state.setText("Đang mở đăng nhập GitHub…");
    SyncCoordinator.WORKER.execute(() -> {
      try {
        Uri start = BrowserConnection.begin(getApplicationContext());
        onUi(() -> launchBrowser(start));
      } catch (Exception e) {
        onUi(() -> {
          loginButton.setEnabled(true);
          state.setText("Chưa mở được phiên đăng nhập. Hãy đóng phiên cũ và thử lại.");
        });
      }
    });
  }

  private void launchBrowser(Uri start) {
    try {
      String provider = CustomTabsClient.getPackageName(this, null);
      if (provider == null) throw new IllegalStateException();
      CustomTabsIntent browser = new CustomTabsIntent.Builder().setShowTitle(true).build();
      browser.intent.setPackage(provider);
      browser.intent.setData(start);
      startActivityForResult(browser.intent, BROWSER_REQUEST);
      BrowserConnection.watch();
    } catch (RuntimeException e) {
      BrowserConnection.stop(getApplicationContext());
      loginButton.setEnabled(true);
      state.setText("Không mở được trình duyệt. Hãy cài hoặc bật trình duyệt rồi thử lại.");
    }
  }

  private void continueFromClipboard() {
    if (!ownerAuthorized) return;
    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
    ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
    CharSequence value = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText();
    if (value == null || value.length() > 4096) {
      state.setText("Hãy sao chép liên kết this setup page từ GitHub trước.");
      return;
    }
    String link = value.toString().trim();
    loginButton.setEnabled(false);
    state.setText("Đang xác minh liên kết trở về từ GitHub…");
    SyncCoordinator.WORKER.execute(() -> {
      try {
        Uri installation = BrowserConnection.continueFromLink(getApplicationContext(), link);
        onUi(() -> launchBrowser(installation));
      } catch (Exception e) { onUi(() -> loginButton.setEnabled(true)); error(e); }
    });
  }

  private void completeLogin(Uri uri) {
    if (!ownerAuthorized || loginCompleting) return;
    loginCompleting = true;
    getIntent().setData(null);
    SyncCoordinator.WORKER.execute(() -> {
      try {
        BrowserConnection.finish(getApplicationContext(), uri);
        onUi(() -> finishActivity(BROWSER_REQUEST));
        SyncCoordinator.schedule(getApplicationContext());
        synchronize();
      } catch (Exception e) { error(e); }
      finally { onUi(() -> { loginCompleting = false; loginButton.setEnabled(true); }); }
    });
  }

  void browserReady(Uri uri) {
    onUi(() -> completeLogin(uri));
  }

  private void resumeLogin() {
    SyncCoordinator.WORKER.execute(() -> {
      try { BrowserConnection.resume(getApplicationContext()); }
      catch (Exception ignored) {}
    });
  }

  @Override protected void onResume() {
    super.onResume();
    if (OfficialInstall.verified(this)) {
      BrowserConnection.observe(this);
      if (support != null) support.refreshRole();
      if (ownerAuthorized) { resumeLogin(); synchronize(); }
    }
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
    if (!ownerAuthorized) return;
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
    if (!ownerAuthorized) return;
    if (!syncBusy.compareAndSet(false, true)) { syncAgain.set(true); return; }
    SyncCoordinator.WORKER.execute(() -> {
      try {
        if (IssuerKeys.exists() && new IssuerStore(this).connected()) {
          boolean retry = SyncCoordinator.sync(getApplicationContext(), () -> false);
          if (retry) SyncCoordinator.schedule(getApplicationContext());
        }
      } catch (Exception e) { error(e); }
      finally {
        syncBusy.set(false);
        if (syncAgain.getAndSet(false)) synchronize();
      }
      refresh();
    });
  }

  private void refresh() {
    if (!ownerAuthorized) return;
    refreshGeneration.incrementAndGet();
    onUi(() -> {
      deviceCount.setText("Đang tải danh sách thiết bị mới nhất…");
      panel.clear();
    });
    loadFreshDevices();
  }

  private void loadFreshDevices() {
    if (!refreshBusy.compareAndSet(false, true)) return;
    int generation = refreshGeneration.get();
    SyncCoordinator.READS.execute(() -> {
      try {
        boolean exists = IssuerKeys.exists();
        if (!exists) {
          onUi(() -> {
            if (generation != refreshGeneration.get()) return;
            publicKeyText.setText("Chưa tạo khóa cấp phép.");
            deviceCount.setText("Tạo khóa cấp phép trước khi tải danh sách thiết bị.");
          });
          return;
        }
        String key = IssuerKeys.securityLevel() + "\n" + IssuerKeys.publicKey();
        IssuerStore store = new IssuerStore(this);
        JSONObject incoming = EnrollmentService.create(this).get("devices");
        JSONObject records = store.importRecords(incoming);
        String status = store.status();
        StringBuilder report = new StringBuilder("Số thiết bị: ").append(records.length());
        onUi(() -> {
          if (!ownerAuthorized || generation != refreshGeneration.get()) return;
          publicKeyText.setText(key);
          deviceCount.setText(report.toString());
          panel.render(records);
          if (!authBusy) state.setText(status);
        });
      } catch (Exception e) {
        onUi(() -> {
          if (generation != refreshGeneration.get()) return;
          deviceCount.setText("Chưa tải được danh sách mới nhất. Chọn Tải danh sách thiết bị từ Firebase để thử lại.");
          panel.clear();
        });
        error(e);
      } finally {
        refreshBusy.set(false);
        if (generation != refreshGeneration.get()) loadFreshDevices();
      }
    });
  }

  private void authenticate() {
    if (!ownerAuthorized) return;
    if (authBusy) return;
    try {
      pendingRequest = requestInput.getText().toString().trim();
      pendingName = nameInput.getText().toString().trim();
      RecoveryProtocol.deviceId(pendingRequest);
      if (pendingPolicy == null) throw new IllegalStateException("Chưa chọn nội dung giấy phép.");
      if (pendingName.length() > 80 || pendingName.chars().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Tên người nhận không hợp lệ.");
      }
      authBusy = true;
      issueButton.setEnabled(false);
      if (Build.VERSION.SDK_INT >= 30) {
        SyncCoordinator.SIGNER.execute(() -> {
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
        Intent intent = guard.createConfirmDeviceCredentialIntent(authorizationTitle(),
            "Thiết bị " + pendingPolicy.device.substring(0, 12) + " · Gói " + pendingPolicy.packageId);
        if (intent == null) throw new IllegalStateException("Không mở được xác thực màn hình.");
        startActivityForResult(intent, AUTH_REQUEST);
      }
    } catch (Exception e) { finishAuthentication(); error(e); }
  }

  private void prompt(Signature signer) {
    if (Build.VERSION.SDK_INT < 30) return;
    authentication = new CancellationSignal();
    new BiometricPrompt.Builder(this)
        .setTitle(authorizationTitle())
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
    if (support != null && support.result(requestCode, resultCode, data)) return;
    if (!ownerAuthorized) return;
    if (requestCode == BROWSER_REQUEST) {
      loginButton.setEnabled(true);
      resumeLogin();
      refresh();
      return;
    }
    if (requestCode != AUTH_REQUEST) return;
    if (resultCode != RESULT_OK) {
      finishAuthentication();
      state.setText("Đã hủy cấp phép.");
      return;
    }
    issue(null);
  }

  private void issue(Signature signer) {
    if (!ownerAuthorized) return;
    String request = pendingRequest;
    String name = pendingName;
    String description = pendingDescription;
    String uid = pendingUid;
    LicensePolicy policy = pendingPolicy;
    SyncCoordinator.SIGNER.execute(() -> {
      try {
        Signature authenticatedSigner = signer == null ? IssuerKeys.signer() : signer;
        String license = policy.sign(authenticatedSigner);
        new IssuerStore(this).record(request, name, description, uid, license);
        onUi(() -> {
          issued = license;
          licenseText.setText(license);
          state.setText("Đã ký và lưu trên điện thoại. Đang đồng bộ giấy phép…");
        });
        SyncCoordinator.schedule(getApplicationContext());
        synchronize();
      } catch (Exception e) { error(e); }
      finally { finishAuthentication(); }
    });
  }

  private void finishAuthentication() {
    onUi(() -> {
      authBusy = false;
      pendingRequest = null;
      pendingName = null;
      pendingPolicy = null;
      pendingDescription = null;
      pendingUid = null;
      if (issueButton != null) issueButton.setEnabled(true);
    });
  }

  void preparePolicy(LicensePolicy policy, String request, String name, String description, String uid) {
    if (!ownerAuthorized) return;
    if (authBusy) { state.setText("Hãy hoàn tất xác thực hiện tại trước."); return; }
    panel.closeScreens();
    pendingPolicy = policy;
    pendingDescription = description;
    pendingUid = uid;
    requestInput.setText(request);
    nameInput.setText(name);
    authenticate();
  }

  void supportOwnerMode(boolean allowed) {
    boolean changed = ownerAuthorized != allowed;
    ownerAuthorized = allowed;
    ownerSection.setVisibility(allowed ? View.VISIBLE : View.GONE);
    if (!allowed) {
      state.setText("");
      if (authentication != null) authentication.cancel();
    }
    if (allowed) {
      if (changed) { refresh(); synchronize(); resumeLogin(); }
      if (getIntent().getData() != null) completeLogin(getIntent().getData());
    }
  }

  void message(String value) { onUi(() -> state.setText(value)); }
  void refreshPanel() { refresh(); }

  private String authorizationTitle() {
    return pendingPolicy.state.equals("revoked") ? "Xác nhận thu hồi giấy phép"
        : pendingPolicy.state.equals("suspended") ? "Xác nhận tạm khóa thiết bị"
        : "Xác nhận cấp phép hoặc mở lại";
  }
}
