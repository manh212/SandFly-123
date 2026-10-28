package com.google.android.accessibility.talkback.keyboard;

import static com.google.android.accessibility.utils.preference.PreferencesActivity.FRAGMENT_NAME;

import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.widget.Button;
import android.widget.TextView;
import androidx.core.text.HtmlCompat;
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.preference.base.TalkBackKeyboardShortcutPreferenceFragment;
import com.google.android.accessibility.utils.preference.BasePreferencesActivity;

/** Activity shows notifications for user how to switch to Default keymap. */
// TODO: b/370662049 - Remove this activity after the new keymap is fully launched.
public class TalkBackKeymapChangesActivity extends BasePreferencesActivity {

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_talkback_keymap_changes);
    SpannableStringBuilder content =
        new SpannableStringBuilder(
            HtmlCompat.fromHtml(
                getString(R.string.keycombo_keymap_changes_activity_content),
                HtmlCompat.FROM_HTML_MODE_LEGACY));
    TextView contentTextView = findViewById(R.id.content);
    contentTextView.setMovementMethod(LinkMovementMethod.getInstance());
    contentTextView.setText(content);

    Button openSettings = findViewById(R.id.open_settings);
    openSettings.setOnClickListener(
        v -> {
          Intent intent =
              new Intent(TalkBackKeymapChangesActivity.this, TalkBackPreferencesActivity.class);
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          intent.putExtra(
              FRAGMENT_NAME, TalkBackKeyboardShortcutPreferenceFragment.class.getName());
          TalkBackKeymapChangesActivity.this.startActivity(intent);
        });
  }
}
