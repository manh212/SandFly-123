package com.Braille;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.media.AudioAttributes;
import com.google.android.accessibility.talkback.keyboardecho.BrailleKeyboardEchoSettings;
import com.google.android.accessibility.talkback.keyboardecho.KeyboardEchoSettings;
import com.google.android.accessibility.utils.input.TextEventFilter;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BrailleKeyboardService extends InputMethodService {

    private static final String PREFS_NAME = "BraillePhoneBreakthroughV2Prefs";

    private static final long CALIBRATION_HOLD_MS = 1500L;
    private static final long DEFAULT_LETTER_DELAY_MS = BrailleSpeedSettings.DEFAULT_LETTER_DELAY_MS;
    private static final long DEFAULT_GESTURE_DELAY_MS = BrailleSpeedSettings.DEFAULT_GESTURE_DELAY_MS;
    private static final long EXPLORE_HOLD_DOT_MS = 230L;
    private static final long DOUBLE_SPACE_THRESHOLD_MS = 600L;
    private static final long DOUBLE_SWIPE_CONFIRM_MS = 2500L;

    private static final int FEEDBACK_BOTH = 0;
    private static final int FEEDBACK_VIBRATE_ONLY = 1;
    private static final int FEEDBACK_SOUND_ONLY = 2;
    private static final int FEEDBACK_NONE = 3;

    private static final int ECHO_CHAR_AND_WORD = TextEventFilter.PREF_ECHO_CHARACTERS_AND_WORDS;
    private static final int ECHO_CHAR_ONLY = TextEventFilter.PREF_ECHO_CHARACTERS;
    private static final int ECHO_WORD_ONLY = TextEventFilter.PREF_ECHO_WORDS;
    private static final int ECHO_NONE = TextEventFilter.PREF_ECHO_NONE;

    private static final String[][] VIET_VOWELS = new String[][]{
            {"a", "á", "à", "ả", "ã", "ạ"},
            {"ă", "ắ", "ằ", "ẳ", "ẵ", "ặ"},
            {"â", "ấ", "ầ", "ẩ", "ẫ", "ậ"},
            {"e", "é", "è", "ẻ", "ẽ", "ẹ"},
            {"ê", "ế", "ề", "ể", "ễ", "ệ"},
            {"i", "í", "ì", "ỉ", "ĩ", "ị"},
            {"o", "ó", "ò", "ỏ", "õ", "ọ"},
            {"ô", "ố", "ồ", "ổ", "ỗ", "ộ"},
            {"ơ", "ớ", "ờ", "ở", "ỡ", "ợ"},
            {"u", "ú", "ù", "ủ", "ũ", "ụ"},
            {"ư", "ứ", "ừ", "ử", "ữ", "ự"},
            {"y", "ý", "ỳ", "ỷ", "ỹ", "ỵ"}
    };

    private SharedPreferences prefs;
    private final BrailleImeInputPolicy imeInputPolicy = new BrailleImeInputPolicy();
    private final BrailleTextOperations.NavigationState navigationState =
            new BrailleTextOperations.NavigationState();

    private static final int SOUND_SLOT_TYPING = 0;
    private static final int SOUND_SLOT_CAPITAL = 1;
    private static final int SOUND_SLOT_SPACE = 2;

    private static class SoundSlot {
        String uri = "";
        MediaPlayer player;
    }

    private final SoundSlot typingSlot = new SoundSlot();
    private final SoundSlot capitalSlot = new SoundSlot();
    private final SoundSlot spaceSlot = new SoundSlot();

    private ToneGenerator toneGenerator;
    private Vibrator vibrator;

    private final LinkedHashMap<String, String> charToDotsMap = new LinkedHashMap<String, String>();
    private final LinkedHashMap<String, String> dotsToCharMap = new LinkedHashMap<String, String>();
    private final LinkedHashMap<String, String> gestureActionMap = new LinkedHashMap<String, String>();
    private final LinkedHashMap<String, String> emojiToDotsMap = new LinkedHashMap<String, String>();
    private final LinkedHashMap<String, String> dotsToEmojiMap = new LinkedHashMap<String, String>();
    private boolean emojiModeActive = false;

    private boolean usePerDotGestures = true;
    private boolean slateAndStylusMode = false;
    private boolean invertKeyboard180 = false;
    private boolean autoCapitalize = true;
    private boolean doubleSpacePeriod = true;
    private boolean privacyScreenActive = false;
    private int feedbackMode = FEEDBACK_BOTH;
    @TextEventFilter.KeyboardEchoType private int echoMode = ECHO_CHAR_AND_WORD;
    private long letterDelayMs = DEFAULT_LETTER_DELAY_MS;
    private long gestureDelayMs = DEFAULT_GESTURE_DELAY_MS;
    private int swipeDistanceDp = BrailleSpeedSettings.DEFAULT_SWIPE_DISTANCE_DP;
    private int longSwipeDistanceDp = BrailleSpeedSettings.DEFAULT_LONG_SWIPE_DISTANCE_DP;
    private boolean distinguishSwipeLength = false;

    private boolean useCalibratedPoints = false;
    private final float[] calDotX = new float[7];
    private final float[] calDotY = new float[7];

    private boolean nextCharCapital = false;
    private boolean capsLockWord = false;
    private boolean numberModeActive = false;
    private int capitalSignCount = 0;
    private boolean capsLockSticky = false;
    private boolean isPasswordInput = false;
    private boolean speakPasswordChars = false;
    private int vibrationLevel = 1;

    private String carriedToneName = null;
    private boolean carriedToneUsed = false;

    private int markPos = -1;
    private final String[] selectionMenuOptions = new String[]{
            "Đọc vùng chọn",
            "Sao chép (Copy)",
            "Cắt (Cut)",
            "Dán (Paste)",
            "Xóa vùng chọn",
            "Chọn tất cả văn bản",
            "Đóng bàn phím"
    };
    private int currentSelectionMenuIndex = 0;

    private long lastSpaceTimeMs = 0L;
    private long lastDeleteLineRequestMs = 0L;
    private long lastClearAllRequestMs = 0L;

    private BrailleImeCanvasView imeCanvasView;
    private final boolean[] pendingChordDots = new boolean[7];
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable singleFingerCommitRunnable = new Runnable() {
        @Override
        public void run() {
            commitPendingDotsNow();
        }
    };

    public static void openEnableKeyboardSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(context, "Không thể mở cài đặt bàn phím", Toast.LENGTH_SHORT).show();
        }
    }

    public static void showKeyboardPicker(Context context) {
        try {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showInputMethodPicker();
            }
        } catch (Throwable t) {
            Toast.makeText(context, "Không thể mở hộp thoại chọn bàn phím", Toast.LENGTH_SHORT).show();
        }
    }

    public static boolean isBrailleKeyboardEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_INPUT_METHODS
            );
            if (enabled == null) return false;
            ComponentName keyboard = new ComponentName(context, BrailleKeyboardService.class);
            for (String entry : enabled.split(":")) {
                if (keyboard.equals(ComponentName.unflattenFromString(entry.split(";")[0]))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isBrailleKeyboardSelected(Context context) {
        try {
            String current = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD
            );
            return new ComponentName(context, BrailleKeyboardService.class)
                    .equals(current == null ? null : ComponentName.unflattenFromString(current));
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initFeedbackEngines();
        reloadAllSettings();
    }

    @Override
    public View onCreateInputView() {
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.BLACK);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int fullHeight = dm.heightPixels;

        imeCanvasView = new BrailleImeCanvasView(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                fullHeight
        );
        container.addView(imeCanvasView, params);
        return container;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        imeInputPolicy.onStartInput(attribute);
        navigationState.reset();
        cancelPendingInput();
        reloadAllSettings();
        if (!BrailleMeridianBridge.isAvailable()) {
            mainHandler.removeCallbacks(singleFingerCommitRunnable);
            clearPendingDots();
            requestHideSelf(0);
            return;
        }

        numberModeActive = false;
        capsLockWord = false;
        carriedToneName = null;
        carriedToneUsed = false;
        markPos = -1;
        capitalSignCount = 0;
        nextCharCapital = false;
        lastSpaceTimeMs = 0L;
        lastDeleteLineRequestMs = 0L;
        lastClearAllRequestMs = 0L;
        if (!restarting) {
            capsLockSticky = false;
        }
        isPasswordInput = isEditorPassword(attribute);

        if (attribute != null) {
            int inputClass = attribute.inputType & InputType.TYPE_MASK_CLASS;
            if (inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE) {
                numberModeActive = true;
            }
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (!BrailleMeridianBridge.isAvailable()) {
            cancelPendingInput();
            requestHideSelf(0);
            return;
        }
        reloadAllSettings();
        clearPendingDots();
        if (!restarting) {
            emojiModeActive = false;
        }

        if (BrailleImeInputPolicy.shouldCapitalize(info, autoCapitalize)) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                CharSequence before = ic.getTextBeforeCursor(2, 0);
                if (before == null || before.length() == 0) {
                    nextCharCapital = true;
                }
            }
        }

        if (imeCanvasView != null) {
            imeCanvasView.invalidate();
        }
        triggerFeedback(imeCanvasView, 45L, ToneGenerator.TONE_PROP_ACK, 90);
        speak("Bàn phím chữ nổi sẵn sàng", true);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        cancelPendingInput();
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onFinishInput() {
        cancelPendingInput();
        navigationState.reset();
        imeInputPolicy.onFinishInput();
        super.onFinishInput();
    }

    private void cancelPendingInput() {
        mainHandler.removeCallbacks(singleFingerCommitRunnable);
        if (imeCanvasView != null) imeCanvasView.cancelGesture();
        else clearPendingDots();
    }

    @Override
    public boolean onShowInputRequested(int flags, boolean configChange) {
        return imeInputPolicy.shouldShow(getCurrentInputEditorInfo(), flags)
                && super.onShowInputRequested(flags, configChange);
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
    }

    @Override
    public void onWindowHidden() {
        cancelPendingInput();
        navigationState.reset();
        imeInputPolicy.onHidden();
        super.onWindowHidden();
    }

    private boolean hidePasswordInSpeech() {
        return isPasswordInput && !speakPasswordChars;
    }

    private boolean isEditorPassword(EditorInfo info) {
        if (info == null) return false;
        int inputClass = info.inputType & InputType.TYPE_MASK_CLASS;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        return inputClass == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || inputClass == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private void initFeedbackEngines() {
        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Throwable ignored) {
        }

        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, 85);
        } catch (Throwable ignored) {
        }

    }

    

    

    

    private SoundSlot slotFor(int soundSlot) {

        if (soundSlot == SOUND_SLOT_CAPITAL) return capitalSlot;
        if (soundSlot == SOUND_SLOT_SPACE) return spaceSlot;
        return typingSlot;
    }

    private void updateSlotUri(SoundSlot slot, String newUri) {
        String safeUri = newUri != null ? newUri : "";
        if (!safeUri.equals(slot.uri)) {
            releaseSlot(slot);
            slot.uri = safeUri;
        }
    }

    private boolean playSlot(SoundSlot slot) {
        if (slot == null || slot.uri == null || slot.uri.length() == 0) return false;
        MediaPlayer newPlayer = null;
        try {
            if (slot.player == null) {
                newPlayer = new MediaPlayer();
                newPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                newPlayer.setDataSource(this, Uri.parse(slot.uri));
                newPlayer.prepare();
                slot.player = newPlayer;
            }
            if (slot.player.isPlaying()) {
                slot.player.seekTo(0);
            } else {
                slot.player.start();
            }
            return true;
        } catch (Throwable t) {
            if (newPlayer != null && newPlayer != slot.player) {
                try {
                    newPlayer.release();
                } catch (Throwable ignored) {
                }
            }
            releaseSlot(slot);
            return false;
        }
    }

    private void releaseSlot(SoundSlot slot) {
        if (slot != null && slot.player != null) {
            try {
                slot.player.release();
            } catch (Throwable ignored) {
            }
            slot.player = null;
        }
    }

    private void vibrateForLevel(View view, long vibrateMs) {
        if (view != null) {
            try {
                view.performHapticFeedback(
                        vibrationLevel == 0 ? HapticFeedbackConstants.KEYBOARD_TAP : HapticFeedbackConstants.LONG_PRESS,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                                | HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                );
            } catch (Throwable ignored) {
            }
        }
        if (vibrator != null && vibrateMs > 0) {
            long scaledMs = vibrateMs;
            if (vibrationLevel == 0) {
                scaledMs = Math.max(12L, vibrateMs / 2);
            } else if (vibrationLevel == 2) {
                scaledMs = Math.min(500L, vibrateMs * 3);
            }
            try {
                vibrator.vibrate(scaledMs);
            } catch (Throwable ignored) {
            }
        }
    }

    private void triggerFeedback(View view, long vibrateMs, int toneType, int toneDurationMs) {
        triggerFeedback(view, vibrateMs, toneType, toneDurationMs, SOUND_SLOT_TYPING);
    }

    private void triggerFeedback(View view, long vibrateMs, int toneType, int toneDurationMs, int soundSlot) {

        boolean allowVibrate = (feedbackMode == FEEDBACK_BOTH || feedbackMode == FEEDBACK_VIBRATE_ONLY);
        boolean allowSound = (feedbackMode == FEEDBACK_BOTH || feedbackMode == FEEDBACK_SOUND_ONLY);

        if (allowVibrate) {
            vibrateForLevel(view, vibrateMs);
        }

        if (allowSound && !playSlot(slotFor(soundSlot)) && toneGenerator != null && toneType >= 0) {
            try {
                toneGenerator.startTone(toneType, toneDurationMs);
            } catch (Throwable ignored) {
            }
        }
    }

    private void speak(String text, boolean flush) {
        if (text == null || text.length() == 0) return;
        BrailleMeridianBridge.speak(text, flush);
    }

    private void speakTyping(String text, boolean flush) {
        echoMode = BrailleKeyboardEchoSettings.read(this);
        if (echoMode == ECHO_NONE || BrailleKeyboardEchoSettings.isTypingSilent(echoMode)) return;
        speak(text, flush);
    }

    private void speakEditFeedback(String text, boolean flush) {
        if (BrailleKeyboardEchoSettings.isTypingSilent(BrailleKeyboardEchoSettings.read(this))) return;
        speak(text, flush);
    }

    private String protectedText(String text) {
        return hidePasswordInSpeech() && !text.isEmpty() ? "Mật khẩu" : text;
    }

    private void reloadAllSettings() {
        resetDefaultGestures();
        resetDefaultVietnameseBraille();

        usePerDotGestures = prefs.getBoolean("use_per_dot_gestures", true);
        slateAndStylusMode = prefs.getBoolean("slate_and_stylus", false);
        invertKeyboard180 = prefs.getBoolean("invert_180", false);
        autoCapitalize = prefs.getBoolean("auto_capitalize", true);
        doubleSpacePeriod = prefs.getBoolean("double_space_period", true);
        privacyScreenActive = prefs.getBoolean("privacy_screen", false);
        feedbackMode = prefs.getInt("feedback_mode", FEEDBACK_BOTH);
        echoMode = BrailleKeyboardEchoSettings.read(this);
        speakPasswordChars = prefs.getBoolean("speak_password_chars", false);
        vibrationLevel = prefs.getInt("vibration_level", 1);

        boolean useTypingSound = prefs.getBoolean("use_custom_sound", false);
        String typingUri = prefs.getString("custom_sound_uri", "");
        updateSlotUri(typingSlot, useTypingSound ? typingUri : "");
        updateSlotUri(capitalSlot, prefs.getString("custom_sound_capital_uri", ""));
        updateSlotUri(spaceSlot, prefs.getString("custom_sound_space_uri", ""));
        letterDelayMs = BrailleSpeedSettings.letterDelay(prefs);
        gestureDelayMs = BrailleSpeedSettings.gestureDelay(prefs);
        swipeDistanceDp = BrailleSpeedSettings.swipeDistance(prefs);
        longSwipeDistanceDp = BrailleSpeedSettings.longSwipeDistance(prefs);
        distinguishSwipeLength = prefs.getBoolean(BrailleSpeedSettings.KEY_DISTINGUISH_SWIPE_LENGTH, false);

        useCalibratedPoints = prefs.getBoolean("use_calibrated_points", false);
        for (int i = 1; i <= 6; i++) {
            calDotX[i] = prefs.getFloat("cal_x_" + i, 0f);
            calDotY[i] = prefs.getFloat("cal_y_" + i, 0f);
        }

        String[] allGestureKeys = new String[]{
                MainActivity.GES_DOT1_DOWN, MainActivity.GES_DOT1_UP, MainActivity.GES_DOT1_LEFT, MainActivity.GES_DOT1_RIGHT,
                MainActivity.GES_DOT2_DOWN, MainActivity.GES_DOT2_UP, MainActivity.GES_DOT2_LEFT, MainActivity.GES_DOT2_RIGHT,
                MainActivity.GES_DOT3_DOWN, MainActivity.GES_DOT3_UP, MainActivity.GES_DOT3_LEFT, MainActivity.GES_DOT3_RIGHT,
                MainActivity.GES_DOT4_DOWN, MainActivity.GES_DOT4_UP, MainActivity.GES_DOT4_LEFT, MainActivity.GES_DOT4_RIGHT,
                MainActivity.GES_DOT5_DOWN, MainActivity.GES_DOT5_UP, MainActivity.GES_DOT5_LEFT, MainActivity.GES_DOT5_RIGHT,
                MainActivity.GES_DOT6_DOWN, MainActivity.GES_DOT6_UP, MainActivity.GES_DOT6_LEFT, MainActivity.GES_DOT6_RIGHT,
                MainActivity.GES_GLOBAL_DOWN_1, MainActivity.GES_GLOBAL_UP_1, MainActivity.GES_GLOBAL_LEFT_1, MainActivity.GES_GLOBAL_RIGHT_1,
                MainActivity.GES_GLOBAL_DOWN_2, MainActivity.GES_GLOBAL_UP_2, MainActivity.GES_GLOBAL_LEFT_2, MainActivity.GES_GLOBAL_RIGHT_2
        };

        for (int i = 0; i < allGestureKeys.length; i++) {
            String key = allGestureKeys[i];
            String savedAct = prefs.getString("ges_" + key, null);
            if (savedAct != null && savedAct.length() > 0) gestureActionMap.put(key, savedAct);
            String longAct = prefs.getString("ges_" + key + "_LONG", null);
            if (longAct != null && longAct.length() > 0) {
                gestureActionMap.put(key + "_LONG", longAct);
            }
        }

        String customKeysCsv = prefs.getString("custom_char_keys", null);
        if (customKeysCsv != null) {
            charToDotsMap.clear();
            if (customKeysCsv.length() > 0) {
                String[] keys = customKeysCsv.split("\u001F");
                for (int i = 0; i < keys.length; i++) {
                    String k = keys[i];
                    if (k != null && k.length() > 0) {
                        String savedDots = prefs.getString("char_dots_" + k, null);
                        if (savedDots != null && savedDots.length() > 0) {
                            charToDotsMap.put(k, normalizeDotsString(savedDots));
                        }
                    }
                }
            }
        }
        fixHyphenDotsConflict();
        ensureEmojiToggleGesture();
        loadEmojiSettings();
        rebuildDotsLookupMap();
    }

    private void resetDefaultEmoji() {
        emojiToDotsMap.clear();
        emojiToDotsMap.put("\uD83D\uDE00", "1");
        emojiToDotsMap.put("\uD83D\uDE02", "2");
        emojiToDotsMap.put("\uD83D\uDE22", "3");
        emojiToDotsMap.put("\uD83D\uDE21", "4");
        emojiToDotsMap.put("\uD83D\uDC4D", "5");
        emojiToDotsMap.put("\u2764\uFE0F", "6");
    }

    private void loadEmojiSettings() {
        resetDefaultEmoji();
        String emojiKeysCsv = prefs.getString("emoji_keys", null);
        if (emojiKeysCsv != null) {
            emojiToDotsMap.clear();
            if (emojiKeysCsv.length() > 0) {
                String[] keys = emojiKeysCsv.split("\u001F");
                for (int i = 0; i < keys.length; i++) {
                    String k = keys[i];
                    if (k != null && k.length() > 0) {
                        String savedDots = prefs.getString("emoji_dots_" + k, null);
                        if (savedDots != null && savedDots.length() > 0) {
                            String normalized = normalizeDotsString(savedDots);
                            if (normalized.length() > 0) {
                                emojiToDotsMap.put(k, normalized);
                            }
                        }
                    }
                }
            }
        }
        dotsToEmojiMap.clear();
        for (Map.Entry<String, String> entry : emojiToDotsMap.entrySet()) {
            String dots = normalizeDotsString(entry.getValue());
            if (dots.length() > 0 && !dotsToEmojiMap.containsKey(dots)) {
                dotsToEmojiMap.put(dots, entry.getKey());
            }
        }
    }

    private String describeEmoji(String emoji) {
        if ("\uD83D\uDE00".equals(emoji)) return "mặt cười";
        if ("\uD83D\uDE02".equals(emoji)) return "cười ra nước mắt";
        if ("\uD83D\uDE22".equals(emoji)) return "mặt khóc";
        if ("\uD83D\uDE21".equals(emoji)) return "mặt giận dữ";
        if ("\uD83D\uDC4D".equals(emoji)) return "giơ ngón cái, thích";
        if ("\u2764\uFE0F".equals(emoji) || "\u2764".equals(emoji)) return "trái tim";
        return emoji;
    }

    private void ensureEmojiToggleGesture() {
        if (gestureActionMap.containsValue(MainActivity.ACT_TOGGLE_EMOJI)) return;
        if (MainActivity.ACT_SPEAK_STATS.equals(gestureActionMap.get(MainActivity.GES_DOT3_RIGHT))) {
            gestureActionMap.put(MainActivity.GES_DOT3_RIGHT, MainActivity.ACT_TOGGLE_EMOJI);
        }
    }

    private void toggleEmojiMode() {
        emojiModeActive = !emojiModeActive;
        numberModeActive = false;
        triggerFeedback(imeCanvasView, 50L, ToneGenerator.TONE_PROP_BEEP2, 90);
        speak(emojiModeActive ? "Chế độ biểu tượng cảm xúc" : "Chế độ chữ cái", true);
    }

    private boolean commitToEditor(InputConnection ic, String value) {
        boolean committed = BrailleInputWriter.commit(ic, value,
                BrailleImeInputPolicy.needsKeyEvents(getCurrentInputEditorInfo()));
        if (!committed) speak("Ô nhập liệu không nhận ký tự", true);
        return committed;
    }

    private void processEmojiChord(String dotsKey, InputConnection ic) {
        String emoji = dotsToEmojiMap.get(dotsKey);
        if (emoji == null) {
            triggerFeedback(imeCanvasView, 70L, ToneGenerator.TONE_SUP_ERROR, 110);
            speak("Chưa có biểu tượng cho chấm " + dotsKey, true);
            return;
        }
        triggerFeedback(imeCanvasView, 40L, ToneGenerator.TONE_PROP_ACK, 65, SOUND_SLOT_TYPING);
        capitalSignCount = 0;
        carriedToneName = null;
        carriedToneUsed = false;
        if (!commitToEditor(ic, emoji)) return;
        if ((echoMode == ECHO_CHAR_AND_WORD || echoMode == ECHO_CHAR_ONLY) && !screenReaderEchoesTypedText()) {
            speak(hidePasswordInSpeech() ? "Sao" : describeEmoji(emoji), true);
        }
    }

    private int lastSymbolLength(CharSequence before) {
        int n = before.length();
        if (n == 0) return 0;
        int i = n;
        while (i > 0) {
            int cp = Character.codePointBefore(before, i);
            i -= Character.charCount(cp);
            boolean joinerOrModifier = (cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D
                    || (cp >= 0x1F3FB && cp <= 0x1F3FF));
            if (joinerOrModifier && i > 0) continue;
            if (i > 0 && Character.codePointBefore(before, i) == 0x200D) continue;
            break;
        }
        return n - i;
    }

    private void fixHyphenDotsConflict() {

        String hyphenDots = charToDotsMap.get("-");
        String tildeDots = charToDotsMap.get("dấu ngã");
        if (hyphenDots != null && tildeDots != null
                && normalizeDotsString(hyphenDots).equals(normalizeDotsString(tildeDots))) {
            boolean taken = false;
            for (Map.Entry<String, String> entry : charToDotsMap.entrySet()) {
                if (!"-".equals(entry.getKey()) && "356".equals(normalizeDotsString(entry.getValue()))) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                charToDotsMap.put("-", "356");
            }
        }
    }

    private void savePhoneOptionsFromIme() {

        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("privacy_screen", privacyScreenActive);
        editor.putInt("feedback_mode", feedbackMode);
        editor.putBoolean("use_calibrated_points", useCalibratedPoints);
        for (int i = 1; i <= 6; i++) {
            editor.putFloat("cal_x_" + i, calDotX[i]);
            editor.putFloat("cal_y_" + i, calDotY[i]);
        }
        editor.apply();
    }

    private void resetDefaultGestures() {
        gestureActionMap.clear();

        gestureActionMap.put(MainActivity.GES_DOT1_DOWN, MainActivity.ACT_MOVE_CHAR_NEXT);
        gestureActionMap.put(MainActivity.GES_DOT1_UP, MainActivity.ACT_MOVE_CHAR_PREV);
        gestureActionMap.put(MainActivity.GES_DOT1_LEFT, MainActivity.ACT_READ_CHAR);
        gestureActionMap.put(MainActivity.GES_DOT1_RIGHT, MainActivity.ACT_CYCLE_FEEDBACK);

        gestureActionMap.put(MainActivity.GES_DOT2_DOWN, MainActivity.ACT_MOVE_WORD_NEXT);
        gestureActionMap.put(MainActivity.GES_DOT2_UP, MainActivity.ACT_MOVE_WORD_PREV);
        gestureActionMap.put(MainActivity.GES_DOT2_LEFT, MainActivity.ACT_READ_WORD);
        gestureActionMap.put(MainActivity.GES_DOT2_RIGHT, MainActivity.ACT_CYCLE_ECHO);

        gestureActionMap.put(MainActivity.GES_DOT3_DOWN, MainActivity.ACT_MOVE_LINE_NEXT);
        gestureActionMap.put(MainActivity.GES_DOT3_UP, MainActivity.ACT_MOVE_LINE_PREV);
        gestureActionMap.put(MainActivity.GES_DOT3_LEFT, MainActivity.ACT_READ_LINE);
        gestureActionMap.put(MainActivity.GES_DOT3_RIGHT, MainActivity.ACT_TOGGLE_EMOJI);

        gestureActionMap.put(MainActivity.GES_DOT4_DOWN, MainActivity.ACT_SPACE);
        gestureActionMap.put(MainActivity.GES_DOT4_UP, MainActivity.ACT_BACKSPACE);
        gestureActionMap.put(MainActivity.GES_DOT4_LEFT, MainActivity.ACT_TOGGLE_PRIVACY);
        gestureActionMap.put(MainActivity.GES_DOT4_RIGHT, MainActivity.ACT_ENTER);

        gestureActionMap.put(MainActivity.GES_DOT5_DOWN, MainActivity.ACT_SPACE);
        gestureActionMap.put(MainActivity.GES_DOT5_UP, MainActivity.ACT_DELETE_WORD);
        gestureActionMap.put(MainActivity.GES_DOT5_LEFT, MainActivity.ACT_SET_MARK);
        gestureActionMap.put(MainActivity.GES_DOT5_RIGHT, MainActivity.ACT_EXIT_KEYBOARD);

        gestureActionMap.put(MainActivity.GES_DOT6_DOWN, MainActivity.ACT_CYCLE_SELECTION_MENU);
        gestureActionMap.put(MainActivity.GES_DOT6_UP, MainActivity.ACT_DELETE_LINE);
        gestureActionMap.put(MainActivity.GES_DOT6_LEFT, MainActivity.ACT_EXEC_SELECTION_MENU);
        gestureActionMap.put(MainActivity.GES_DOT6_RIGHT, MainActivity.ACT_SPEAK_TEXT);

        gestureActionMap.put(MainActivity.GES_GLOBAL_DOWN_1, MainActivity.ACT_SPACE);
        gestureActionMap.put(MainActivity.GES_GLOBAL_UP_1, MainActivity.ACT_BACKSPACE);
        gestureActionMap.put(MainActivity.GES_GLOBAL_LEFT_1, MainActivity.ACT_TOGGLE_PRIVACY);
        gestureActionMap.put(MainActivity.GES_GLOBAL_RIGHT_1, MainActivity.ACT_SPEAK_TEXT);

        gestureActionMap.put(MainActivity.GES_GLOBAL_DOWN_2, MainActivity.ACT_ENTER);
        gestureActionMap.put(MainActivity.GES_GLOBAL_UP_2, MainActivity.ACT_DELETE_WORD);
        gestureActionMap.put(MainActivity.GES_GLOBAL_LEFT_2, MainActivity.ACT_EXIT_KEYBOARD);
        gestureActionMap.put(MainActivity.GES_GLOBAL_RIGHT_2, MainActivity.ACT_START_CALIBRATION);
    }

    private void resetDefaultVietnameseBraille() {
        charToDotsMap.clear();
        charToDotsMap.put("a", "1");
        charToDotsMap.put("ă", "345");
        charToDotsMap.put("â", "16");
        charToDotsMap.put("b", "12");
        charToDotsMap.put("c", "14");
        charToDotsMap.put("d", "145");
        charToDotsMap.put("đ", "2346");
        charToDotsMap.put("e", "15");
        charToDotsMap.put("ê", "126");
        charToDotsMap.put("f", "124");
        charToDotsMap.put("g", "1245");
        charToDotsMap.put("h", "125");
        charToDotsMap.put("i", "24");
        charToDotsMap.put("j", "245");
        charToDotsMap.put("k", "13");
        charToDotsMap.put("l", "123");
        charToDotsMap.put("m", "134");
        charToDotsMap.put("n", "1345");
        charToDotsMap.put("o", "135");
        charToDotsMap.put("ô", "1456");
        charToDotsMap.put("ơ", "246");
        charToDotsMap.put("p", "1234");
        charToDotsMap.put("q", "12345");
        charToDotsMap.put("r", "1235");
        charToDotsMap.put("s", "234");
        charToDotsMap.put("t", "2345");
        charToDotsMap.put("u", "136");
        charToDotsMap.put("ư", "1256");
        charToDotsMap.put("v", "1236");
        charToDotsMap.put("w", "2456");
        charToDotsMap.put("x", "1346");
        charToDotsMap.put("y", "13456");
        charToDotsMap.put("z", "1356");

        charToDotsMap.put("dấu sắc", "35");
        charToDotsMap.put("dấu huyền", "56");
        charToDotsMap.put("dấu hỏi", "26");
        charToDotsMap.put("dấu ngã", "36");
        charToDotsMap.put("dấu nặng", "6");

        charToDotsMap.put("dấu viết hoa", "46");
        charToDotsMap.put("dấu báo số", "3456");

        charToDotsMap.put(",", "2");
        charToDotsMap.put(".", "256");
        charToDotsMap.put("?", "236");
        charToDotsMap.put("!", "235");
        charToDotsMap.put(":", "25");
        charToDotsMap.put(";", "23");
        charToDotsMap.put("-", "356");
    }

    private void rebuildDotsLookupMap() {
        dotsToCharMap.clear();
        String[] priorityKeys = new String[]{"dấu viết hoa", "dấu báo số"};
        for (int p = 0; p < priorityKeys.length; p++) {
            String priorityDots = charToDotsMap.get(priorityKeys[p]);
            if (priorityDots != null) {
                String normalizedPriority = normalizeDotsString(priorityDots);
                if (normalizedPriority.length() > 0 && !dotsToCharMap.containsKey(normalizedPriority)) {
                    dotsToCharMap.put(normalizedPriority, priorityKeys[p]);
                }
            }
        }
        for (Map.Entry<String, String> entry : charToDotsMap.entrySet()) {
            String ch = entry.getKey();
            String dots = normalizeDotsString(entry.getValue());
            if (dots.length() > 0 && !dotsToCharMap.containsKey(dots)) {
                dotsToCharMap.put(dots, ch);
            }
        }
    }

    private String normalizeDotsString(String raw) {
        if (raw == null) return "";
        boolean[] present = new boolean[7];
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '1' && c <= '6') {
                present[c - '0'] = true;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int d = 1; d <= 6; d++) {
            if (present[d]) {
                sb.append(d);
            }
        }
        return sb.toString();
    }

    private boolean screenReaderEchoesTypedText() {
        if (isPasswordInput) return false; // Keep the supplied password behavior untouched.
        return BrailleMeridianBridge.isAvailable();
    }

    private void addDotToPendingChord(int dotNumber, View sourceView) {
        if (dotNumber < 1 || dotNumber > 6) return;

        mainHandler.removeCallbacks(singleFingerCommitRunnable);
        if (!pendingChordDots[dotNumber]) {
            pendingChordDots[dotNumber] = true;
            triggerFeedback(sourceView, 22L, ToneGenerator.TONE_PROP_BEEP, 40);
            if (imeCanvasView != null) imeCanvasView.invalidate();
        }
        mainHandler.postDelayed(singleFingerCommitRunnable, letterDelayMs);
    }

    private void clearPendingDots() {
        mainHandler.removeCallbacks(singleFingerCommitRunnable);
        for (int i = 1; i <= 6; i++) {
            pendingChordDots[i] = false;
        }
        if (imeCanvasView != null) imeCanvasView.invalidate();
    }

    private void commitPendingDotsNow() {
        mainHandler.removeCallbacks(singleFingerCommitRunnable);
        StringBuilder sb = new StringBuilder();
        for (int d = 1; d <= 6; d++) {
            if (pendingChordDots[d]) {
                sb.append(d);
                pendingChordDots[d] = false;
            }
        }
        if (imeCanvasView != null) imeCanvasView.invalidate();

        String dotsKey = sb.toString();
        if (dotsKey.length() == 0) return;

        processBrailleChordOnInputConnection(dotsKey);
    }

    private void processBrailleChordOnInputConnection(String dotsKey) {
        if (!BrailleMeridianBridge.isAvailable()) return;
        navigationState.reset();
        echoMode = BrailleKeyboardEchoSettings.read(this);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            speak("Ô nhập liệu chưa sẵn sàng", true);
            return;
        }

        if (emojiModeActive) {
            processEmojiChord(dotsKey, ic);
            return;
        }

        String matched = dotsToCharMap.get(dotsKey);
        if (matched == null && "3456".equals(dotsKey)) {
            matched = "dấu báo số";
        }
        if (matched == null) {
            triggerFeedback(imeCanvasView, 70L, ToneGenerator.TONE_SUP_ERROR, 110);
            speak("Chưa có chấm " + dotsKey, true);
            return;
        }

        triggerFeedback(imeCanvasView, 40L, ToneGenerator.TONE_PROP_ACK, 65,
                "dấu viết hoa".equals(matched) ? SOUND_SLOT_CAPITAL : SOUND_SLOT_TYPING);

        if ("dấu viết hoa".equals(matched)) {
            if (capsLockSticky) {
                capsLockSticky = false;
                capsLockWord = false;
                nextCharCapital = false;
                capitalSignCount = 0;
                speakTyping("Tắt khóa viết hoa", true);
                return;
            }
            capitalSignCount++;
            if (capitalSignCount >= 3) {
                capsLockSticky = true;
                capsLockWord = false;
                nextCharCapital = false;
                capitalSignCount = 0;
                speakTyping("Bật khóa viết hoa. Bấm dấu viết hoa một lần để tắt", true);
            } else if (capitalSignCount == 2) {
                capsLockWord = true;
                speakTyping("Viết hoa cả từ", true);
            } else {
                nextCharCapital = true;
                speakTyping("Viết hoa", true);
            }
            return;
        }
        capitalSignCount = 0;

        if ("dấu báo số".equals(matched)) {
            if (numberModeActive) {
                numberModeActive = false;
                speakTyping("Chế độ chữ cái", true);
            } else {
                numberModeActive = true;
                speakTyping("Chế độ số", true);
            }
            return;
        }

        if (numberModeActive) {
            String digit = convertDotsToDigit(dotsKey);
            if (digit != null) {
                if (!commitToEditor(ic, digit)) return;
                if ((echoMode == ECHO_CHAR_AND_WORD || echoMode == ECHO_CHAR_ONLY)
                        && !screenReaderEchoesTypedText()) {
                    speak(hidePasswordInSpeech() ? "Sao" : digit, true);
                }
                return;
            } else {
                numberModeActive = false;
            }
        }

        if (isVietnameseToneKey(matched)) {
            carriedToneName = matched;
            boolean applied = applyCarriedToneToCurrentWord(ic);
            carriedToneUsed = applied;
            if (applied) {
                String word = getWordBeforeCursor(ic);
                speakTyping(matched + (hidePasswordInSpeech() ? "" : (", " + word)), true);
            } else {
                speakTyping(matched + ", hãy viết chữ cái", true);
            }
            return;
        }

        String outputChar = matched;
        if (nextCharCapital || capsLockWord || capsLockSticky) {
            outputChar = outputChar.toUpperCase(new Locale("vi", "VN"));
            nextCharCapital = false;
        }

        boolean carryTone = carriedToneName != null
                && outputChar.length() == 1
                && Character.isLetter(outputChar.charAt(0));

        if (!commitToEditor(ic, outputChar)) return;

        String echoText = outputChar;
        if (carryTone) {
            if (applyCarriedToneToCurrentWord(ic)) {
                carriedToneUsed = true;
                CharSequence lastChar = ic.getTextBeforeCursor(1, 0);
                if (lastChar != null && lastChar.length() > 0) {
                    echoText = lastChar.toString();
                }
            }
        } else {
            carriedToneName = null;
            carriedToneUsed = false;
        }

        if ((echoMode == ECHO_CHAR_AND_WORD || echoMode == ECHO_CHAR_ONLY) && !screenReaderEchoesTypedText()) {
            speak(hidePasswordInSpeech() ? "Sao" : echoText, true);
        }
    }

    private String convertDotsToDigit(String dotsKey) {
        if ("1".equals(dotsKey)) return "1";
        if ("12".equals(dotsKey)) return "2";
        if ("14".equals(dotsKey)) return "3";
        if ("145".equals(dotsKey)) return "4";
        if ("15".equals(dotsKey)) return "5";
        if ("124".equals(dotsKey)) return "6";
        if ("1245".equals(dotsKey)) return "7";
        if ("125".equals(dotsKey)) return "8";
        if ("24".equals(dotsKey)) return "9";
        if ("245".equals(dotsKey)) return "0";
        return null;
    }

    private boolean isVietnameseToneKey(String key) {
        return "dấu sắc".equals(key)
                || "dấu huyền".equals(key)
                || "dấu hỏi".equals(key)
                || "dấu ngã".equals(key)
                || "dấu nặng".equals(key);
    }

    private int vowelRowOf(char c) {
        char lc = Character.toLowerCase(c);
        for (int r = 0; r < VIET_VOWELS.length; r++) {
            for (int col = 0; col < 6; col++) {
                if (VIET_VOWELS[r][col].charAt(0) == lc) return r;
            }
        }
        return -1;
    }

    private int toneIndexOf(String toneName) {
        if ("dấu sắc".equals(toneName)) return 1;
        if ("dấu huyền".equals(toneName)) return 2;
        if ("dấu hỏi".equals(toneName)) return 3;
        if ("dấu ngã".equals(toneName)) return 4;
        if ("dấu nặng".equals(toneName)) return 5;
        return -1;
    }

    private boolean applyCarriedToneToCurrentWord(InputConnection ic) {
        int toneIndex = toneIndexOf(carriedToneName);
        if (toneIndex < 0 || ic == null) return false;

        CharSequence beforeSeq = ic.getTextBeforeCursor(40, 0);
        if (beforeSeq == null || beforeSeq.length() == 0) return false;

        String before = beforeSeq.toString();
        int wordStart = before.length();
        while (wordStart > 0) {
            char ch = before.charAt(wordStart - 1);
            if (ch == ' ' || ch == '\n') break;
            wordStart--;
        }
        if (wordStart >= before.length()) return false;

        String word = before.substring(wordStart);
        int len = word.length();
        int[] rows = new int[len];
        for (int i = 0; i < len; i++) {
            rows[i] = vowelRowOf(word.charAt(i));
        }

        int first = -1;
        for (int i = 0; i < len; i++) {
            if (rows[i] >= 0) {
                first = i;
                break;
            }
        }
        if (first < 0) return false;

        if (first > 0 && first + 1 < len && rows[first + 1] >= 0) {
            char prev = Character.toLowerCase(word.charAt(first - 1));
            if (prev == 'q' && rows[first] == 9) {
                first++;
            } else if (prev == 'g' && rows[first] == 5) {
                first++;
            }
        }

        int clusterStart = first;
        int clusterEnd = first;
        while (clusterEnd + 1 < len && rows[clusterEnd + 1] >= 0) {
            clusterEnd++;
        }
        int n = clusterEnd - clusterStart + 1;
        boolean hasFinalConsonant = (clusterEnd + 1 < len);

        int target = -1;
        for (int i = clusterEnd; i >= clusterStart; i--) {
            int r = rows[i];
            if (r == 1 || r == 2 || r == 4 || r == 7 || r == 8 || r == 10) {
                target = i;
                break;
            }
        }
        if (target < 0) {
            if (n == 1) {
                target = clusterStart;
            } else if (n == 2) {
                int r0 = rows[clusterStart];
                int r1 = rows[clusterEnd];
                boolean secondVowel = hasFinalConsonant
                        || (r0 == 6 && (r1 == 0 || r1 == 3))
                        || (r0 == 9 && r1 == 11);
                target = secondVowel ? clusterEnd : clusterStart;
            } else if (n == 3) {
                target = clusterStart + 1;
            } else {
                target = clusterEnd;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            char c = word.charAt(i);
            if (rows[i] >= 0) {
                int col = (i == target) ? toneIndex : 0;
                String rep = VIET_VOWELS[rows[i]][col];
                if (Character.isUpperCase(c)) rep = rep.toUpperCase(new Locale("vi", "VN"));
                sb.append(rep);
            } else {
                sb.append(c);
            }
        }

        String updatedWord = sb.toString();
        ic.beginBatchEdit();
        ic.deleteSurroundingText(len, 0);
        ic.commitText(updatedWord, 1);
        ic.endBatchEdit();
        return true;
    }

    private void dispatchSwipeGesture(int startDot, String direction, boolean twoFingers, boolean longSwipe) {
        clearPendingDots();

        String gestureKey;
        if (twoFingers) {
            if ("DOWN".equals(direction)) gestureKey = MainActivity.GES_GLOBAL_DOWN_2;
            else if ("UP".equals(direction)) gestureKey = MainActivity.GES_GLOBAL_UP_2;
            else if ("LEFT".equals(direction)) gestureKey = MainActivity.GES_GLOBAL_LEFT_2;
            else gestureKey = MainActivity.GES_GLOBAL_RIGHT_2;
        } else if (usePerDotGestures && startDot >= 1 && startDot <= 6) {
            gestureKey = "DOT" + startDot + "_" + direction;
        } else {
            if ("DOWN".equals(direction)) gestureKey = MainActivity.GES_GLOBAL_DOWN_1;
            else if ("UP".equals(direction)) gestureKey = MainActivity.GES_GLOBAL_UP_1;
            else if ("LEFT".equals(direction)) gestureKey = MainActivity.GES_GLOBAL_LEFT_1;
            else gestureKey = MainActivity.GES_GLOBAL_RIGHT_1;
        }

        String actionCode = BrailleSpeedSettings.swipeAction(gestureActionMap, gestureKey,
                distinguishSwipeLength, longSwipe);
        if (actionCode != null) {
            executeImeAction(actionCode);
        }
    }

    private static class DocumentSnapshot {
        String text;
        int cursor;
        boolean supportsDirectSelection;
        int startOffset;
    }

    private DocumentSnapshot readDocumentSnapshot(InputConnection ic) {
        DocumentSnapshot snap = new DocumentSnapshot();
        snap.text = "";
        snap.cursor = 0;
        snap.supportsDirectSelection = false;
        snap.startOffset = 0;
        if (ic == null) return snap;

        try {
            ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
            if (et != null && et.text != null) {
                snap.text = et.text.toString();
                snap.startOffset = Math.max(0, et.startOffset);
                snap.cursor = Math.max(0, Math.min(
                        et.selectionEnd - snap.startOffset, snap.text.length()));
                snap.supportsDirectSelection = et.startOffset >= 0;
                return snap;
            }
        } catch (Throwable ignored) {
        }

        CharSequence before = ic.getTextBeforeCursor(2000, 0);
        CharSequence after = ic.getTextAfterCursor(2000, 0);
        String b = (before != null) ? before.toString() : "";
        String a = (after != null) ? after.toString() : "";
        snap.text = b + a;
        snap.cursor = b.length();
        return snap;
    }

    private boolean moveCursorTo(InputConnection ic, DocumentSnapshot snap, int newPos) {
        if (ic == null) return false;
        int clamped = Math.max(0, Math.min(newPos, snap.text.length()));
        if (snap.supportsDirectSelection) {
            return ic.setSelection(snap.startOffset + clamped, snap.startOffset + clamped);
        }
        int delta = clamped - snap.cursor;
        int keyCode = delta > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        for (int i = 0; i < Math.abs(delta); i++) {
            boolean down = ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            boolean up = ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            if (!down || !up) return false;
        }
        return true;
    }

    private boolean deleteTextRange(InputConnection ic, DocumentSnapshot snap,
            BrailleTextOperations.Range range) {
        if (range.isEmpty()) return false;
        if (snap.supportsDirectSelection
                && ic.setSelection(snap.startOffset + range.start,
                        snap.startOffset + range.end)) {
            return ic.commitText("", 1);
        }
        if (range.start <= snap.cursor && range.end >= snap.cursor) {
            return ic.deleteSurroundingText(snap.cursor - range.start,
                    range.end - snap.cursor);
        }
        return false;
    }

    private boolean sendDocumentEdgeKey(InputConnection ic, int keyCode) {
        long time = android.os.SystemClock.uptimeMillis();
        boolean down = ic.sendKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN,
                keyCode, 0, KeyEvent.META_CTRL_ON));
        boolean up = ic.sendKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP,
                keyCode, 0, KeyEvent.META_CTRL_ON));
        return down && up;
    }

    private void executeImeAction(String actionCode) {
        if (!BrailleMeridianBridge.isAvailable()) return;
        echoMode = BrailleKeyboardEchoSettings.read(this);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            speak("Ô nhập liệu chưa sẵn sàng", true);
            return;
        }
        if (!MainActivity.ACT_DELETE_CHAR_AT_CURSOR.equals(actionCode)
                && !MainActivity.ACT_MOVE_CHAR_NEXT.equals(actionCode)
                && !MainActivity.ACT_MOVE_CHAR_PREV.equals(actionCode)
                && !MainActivity.ACT_MOVE_LINE_BOUNDARY_NEXT.equals(actionCode)) {
            navigationState.reset();
        }

        long now = System.currentTimeMillis();
        capitalSignCount = 0;
        carriedToneName = null;
        carriedToneUsed = false;
        if (MainActivity.ACT_SPACE.equals(actionCode)) {
            numberModeActive = false;
            capsLockWord = false;

            CharSequence beforeTwo = ic.getTextBeforeCursor(2, 0);
            if (doubleSpacePeriod && (now - lastSpaceTimeMs <= DOUBLE_SPACE_THRESHOLD_MS)
                    && beforeTwo != null && beforeTwo.length() == 2
                    && beforeTwo.charAt(1) == ' ' && beforeTwo.charAt(0) != ' ' && beforeTwo.charAt(0) != '.') {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(1, 0);
                ic.commitText(". ", 1);
                ic.endBatchEdit();
                lastSpaceTimeMs = 0L;
                if (autoCapitalize) nextCharCapital = true;
                triggerFeedback(imeCanvasView, 45L, ToneGenerator.TONE_PROP_ACK, 70, SOUND_SLOT_SPACE);
                speakTyping("Chấm câu", true);
            } else {
                String completedWord = getWordBeforeCursor(ic);
                if (!commitToEditor(ic, " ")) return;
                lastSpaceTimeMs = now;
                triggerFeedback(imeCanvasView, 30L, ToneGenerator.TONE_PROP_ACK, 50, SOUND_SLOT_SPACE);
                if (!screenReaderEchoesTypedText()) {
                    if (!hidePasswordInSpeech() && (echoMode == ECHO_CHAR_AND_WORD || echoMode == ECHO_WORD_ONLY) && completedWord.length() > 0) {
                        speak(completedWord, true);
                    } else {
                        speakTyping("Dấu cách", true);
                    }
                }
            }

        } else if (MainActivity.ACT_BACKSPACE.equals(actionCode)) {
            CharSequence selected = ic.getSelectedText(0);
            if (selected != null && selected.length() > 0) {
                ic.commitText("", 1);
                triggerFeedback(imeCanvasView, 40L, ToneGenerator.TONE_PROP_NACK, 60);
                speakEditFeedback("Đã xóa vùng đang chọn", true);
                return;
            }
            CharSequence beforeChunk = ic.getTextBeforeCursor(16, 0);
            if (beforeChunk != null && beforeChunk.length() > 0) {
                int deleteUnits = lastSymbolLength(beforeChunk);
                String deletedText = beforeChunk.subSequence(beforeChunk.length() - deleteUnits, beforeChunk.length()).toString();
                ic.deleteSurroundingText(deleteUnits, 0);
                triggerFeedback(imeCanvasView, 40L, ToneGenerator.TONE_PROP_NACK, 60);
                String deletedSpeech = (deleteUnits == 1)
                        ? describeCharForSpeech(deletedText.charAt(0))
                        : describeEmoji(deletedText);
                speakEditFeedback("Đã xóa " + (hidePasswordInSpeech() ? "ký tự" : deletedSpeech), true);
            } else {
                speakEditFeedback("Đầu văn bản, không có ký tự để xóa", true);
            }

        } else if (MainActivity.ACT_DELETE_CHAR_AT_CURSOR.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            BrailleTextOperations.Range range =
                    navigationState.focusedCharacter(snap.text, snap.cursor);
            if (range.isEmpty()) {
                speakEditFeedback("Không có ký tự để xóa", true);
            } else if (deleteTextRange(ic, snap, range)) {
                String deleted = snap.text.substring(range.start, range.end);
                triggerFeedback(imeCanvasView, 40L, ToneGenerator.TONE_PROP_NACK, 60);
                speakEditFeedback("Đã xóa ký tự " + protectedText(deleted), true);
            } else {
                speakEditFeedback("Ô nhập liệu không hỗ trợ xóa ký tự tại con trỏ", true);
            }
            navigationState.reset();

        } else if (MainActivity.ACT_DELETE_WORD_AT_CURSOR.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            BrailleTextOperations.Range range = BrailleTextOperations.wordAt(snap.text, snap.cursor);
            if (range.isEmpty()) {
                speakEditFeedback("Không có từ để xóa", true);
            } else if (deleteTextRange(ic, snap, range)) {
                String deleted = snap.text.substring(range.start, range.end);
                triggerFeedback(imeCanvasView, 55L, ToneGenerator.TONE_PROP_NACK, 85);
                speakEditFeedback("Đã xóa từ " + protectedText(deleted), true);
            } else {
                speakEditFeedback("Ô nhập liệu không hỗ trợ xóa từ tại con trỏ", true);
            }
            navigationState.reset();

        } else if (MainActivity.ACT_DELETE_WORD.equals(actionCode)) {
            CharSequence beforeSeq = ic.getTextBeforeCursor(64, 0);
            if (beforeSeq != null && beforeSeq.length() > 0) {
                String before = beforeSeq.toString();
                int end = before.length();
                int start = end;
                while (start > 0 && before.charAt(start - 1) == ' ') start--;
                while (start > 0 && before.charAt(start - 1) != ' ' && before.charAt(start - 1) != '\n') start--;
                int deleteLen = end - start;
                String deletedWord = before.substring(start, end).trim();
                ic.deleteSurroundingText(deleteLen, 0);
                triggerFeedback(imeCanvasView, 55L, ToneGenerator.TONE_PROP_NACK, 85);
                speakEditFeedback("Đã xóa từ " + (hidePasswordInSpeech() ? "" : deletedWord), true);
            } else {
                speakEditFeedback("Không có từ để xóa", true);
            }

        } else if (MainActivity.ACT_DELETE_LINE.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            if (snap.text.length() == 0) {
                speakEditFeedback("Ô nhập đang trống", true);
                return;
            }
            if (now - lastDeleteLineRequestMs > DOUBLE_SWIPE_CONFIRM_MS) {
                lastDeleteLineRequestMs = now;
                triggerFeedback(imeCanvasView, 50L, ToneGenerator.TONE_PROP_BEEP2, 90);
                speakEditFeedback("Vuốt thêm 1 lần nữa để xác nhận xóa cả dòng", true);
                return;
            }
            lastDeleteLineRequestMs = 0L;
            int lineStart = getLineStart(snap.text, snap.cursor);
            int lineEnd = getLineEnd(snap.text, snap.cursor);
            String deletedLine = snap.text.substring(lineStart, lineEnd);
            int beforeDelete = snap.cursor - lineStart;
            int afterDelete = lineEnd - snap.cursor;
            if (lineEnd < snap.text.length() && snap.text.charAt(lineEnd) == '\n') {
                afterDelete += 1;
            }
            ic.deleteSurroundingText(Math.max(0, beforeDelete), Math.max(0, afterDelete));
            triggerFeedback(imeCanvasView, 80L, ToneGenerator.TONE_PROP_NACK, 110);
            speakEditFeedback("Đã xóa dòng: " + protectedText(deletedLine), true);

        } else if (MainActivity.ACT_CLEAR_ALL.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            if (snap.text.length() == 0) {
                speakEditFeedback("Ô nhập đang trống", true);
                return;
            }
            if (now - lastClearAllRequestMs > DOUBLE_SWIPE_CONFIRM_MS) {
                lastClearAllRequestMs = now;
                triggerFeedback(imeCanvasView, 50L, ToneGenerator.TONE_PROP_BEEP2, 90);
                speakEditFeedback("Vuốt thêm 1 lần nữa để xóa toàn bộ văn bản", true);
                return;
            }
            lastClearAllRequestMs = 0L;
            ic.beginBatchEdit();
            if (!ic.performContextMenuAction(android.R.id.selectAll)) {
                ic.endBatchEdit();
                speakEditFeedback("Không thể chọn toàn bộ văn bản", true);
                return;
            }
            ic.commitText("", 1);
            ic.endBatchEdit();
            markPos = -1;
            triggerFeedback(imeCanvasView, 90L, ToneGenerator.TONE_PROP_NACK, 120);
            speakEditFeedback("Đã xóa toàn bộ văn bản", true);

        } else if (MainActivity.ACT_ENTER.equals(actionCode)) {
            numberModeActive = false;
            capsLockWord = false;
            if (autoCapitalize) nextCharCapital = true;

            EditorInfo ei = getCurrentInputEditorInfo();
            int imeOptions = (ei != null) ? ei.imeOptions : 0;
            int actionId = imeOptions & EditorInfo.IME_MASK_ACTION;
            boolean noEnterAction = (imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
            boolean isMultiLine = ei != null && (ei.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;

            triggerFeedback(imeCanvasView, 45L, ToneGenerator.TONE_PROP_ACK, 70);
            if (!isMultiLine && !noEnterAction && actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(actionId);
                speakEditFeedback("Thực hiện lệnh", true);
            } else {
                if (!commitToEditor(ic, "\n")) return;
                speakEditFeedback("Xuống dòng", true);
            }

        } else if (MainActivity.ACT_MOVE_CHAR_NEXT.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            if (snap.cursor < snap.text.length()) {
                BrailleTextOperations.Range range =
                        BrailleTextOperations.characterAt(snap.text, snap.cursor, false);
                if (moveCursorTo(ic, snap, range.end)) {
                    navigationState.recordCharacterMovement(range.end, true);
                    String character = snap.text.substring(range.start, range.end);
                    triggerFeedback(imeCanvasView, 20L, ToneGenerator.TONE_PROP_BEEP, 35);
                    speak(protectedText(character.length() == 1
                            ? describeCharForSpeech(character.charAt(0)) : character), true);
                } else {
                    speak("Không thể di chuyển con trỏ", true);
                }
            } else {
                speak("Cuối văn bản", true);
            }

        } else if (MainActivity.ACT_MOVE_CHAR_PREV.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            if (snap.cursor > 0) {
                BrailleTextOperations.Range range =
                        BrailleTextOperations.characterAt(snap.text, snap.cursor, true);
                if (moveCursorTo(ic, snap, range.start)) {
                    navigationState.recordCharacterMovement(range.start, false);
                    String character = snap.text.substring(range.start, range.end);
                    triggerFeedback(imeCanvasView, 20L, ToneGenerator.TONE_PROP_BEEP, 35);
                    speak(protectedText(character.length() == 1
                            ? describeCharForSpeech(character.charAt(0)) : character), true);
                } else {
                    speak("Không thể di chuyển con trỏ", true);
                }
            } else {
                speak("Đầu văn bản", true);
            }

        } else if (MainActivity.ACT_MOVE_WORD_NEXT.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            int p = snap.cursor;
            if (p < snap.text.length()) {
                while (p < snap.text.length() && snap.text.charAt(p) != ' ' && snap.text.charAt(p) != '\n') p++;
                while (p < snap.text.length() && (snap.text.charAt(p) == ' ' || snap.text.charAt(p) == '\n')) p++;
                moveCursorTo(ic, snap, p);
                triggerFeedback(imeCanvasView, 25L, ToneGenerator.TONE_PROP_BEEP, 40);
                String w = getWordAroundPos(snap.text, p);
                speak(w.length() > 0 ? protectedText(w) : "Cuối văn bản", true);
            } else {
                speak("Cuối văn bản", true);
            }

        } else if (MainActivity.ACT_MOVE_WORD_PREV.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            int p = snap.cursor;
            if (p > 0) {
                while (p > 0 && (snap.text.charAt(p - 1) == ' ' || snap.text.charAt(p - 1) == '\n')) p--;
                while (p > 0 && snap.text.charAt(p - 1) != ' ' && snap.text.charAt(p - 1) != '\n') p--;
                moveCursorTo(ic, snap, p);
                triggerFeedback(imeCanvasView, 25L, ToneGenerator.TONE_PROP_BEEP, 40);
                String w = getWordAroundPos(snap.text, p);
                speak(w.length() > 0 ? protectedText(w) : "Đầu văn bản", true);
            } else {
                speak("Đầu văn bản", true);
            }

        } else if (MainActivity.ACT_MOVE_LINE_NEXT.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            int lineEnd = getLineEnd(snap.text, snap.cursor);
            if (lineEnd < snap.text.length()) {
                int newPos = lineEnd + 1;
                moveCursorTo(ic, snap, newPos);
                speak(protectedText(getLineAtPos(snap.text, newPos)), true);
            } else {
                speak("Dòng cuối cùng", true);
            }

        } else if (MainActivity.ACT_MOVE_LINE_PREV.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            int lineStart = getLineStart(snap.text, snap.cursor);
            if (lineStart > 0) {
                int newPos = getLineStart(snap.text, lineStart - 1);
                moveCursorTo(ic, snap, newPos);
                speak(protectedText(getLineAtPos(snap.text, newPos)), true);
            } else {
                speak("Dòng đầu tiên", true);
            }

        } else if (MainActivity.ACT_MOVE_LINE_START.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            int position = BrailleTextOperations.lineStart(snap.text, snap.cursor);
            speak(moveCursorTo(ic, snap, position) ? "Đầu dòng" : "Không thể di chuyển con trỏ", true);

        } else if (MainActivity.ACT_MOVE_LINE_END.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            int position = BrailleTextOperations.lineEnd(snap.text, snap.cursor);
            speak(moveCursorTo(ic, snap, position) ? "Cuối dòng" : "Không thể di chuyển con trỏ", true);

        } else if (MainActivity.ACT_MOVE_LINE_BOUNDARY_NEXT.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            int position = navigationState.nextLineBoundary(snap.text, snap.cursor);
            if (moveCursorTo(ic, snap, position)) {
                int start = BrailleTextOperations.lineStart(snap.text, position);
                int end = BrailleTextOperations.lineEnd(snap.text, position);
                String line = snap.text.substring(start, end);
                speak(line.isEmpty() ? "Dòng trống" : protectedText(line), true);
            } else {
                navigationState.reset();
                speak("Không thể di chuyển con trỏ", true);
            }

        } else if (MainActivity.ACT_MOVE_START.equals(actionCode)) {
            boolean moved = ic.setSelection(0, 0)
                    || sendDocumentEdgeKey(ic, KeyEvent.KEYCODE_MOVE_HOME);
            speak(moved ? "Đầu văn bản" : "Không thể chuyển tới đầu văn bản", true);

        } else if (MainActivity.ACT_MOVE_END.equals(actionCode)) {
            boolean moved = sendDocumentEdgeKey(ic, KeyEvent.KEYCODE_MOVE_END);
            speak(moved ? "Cuối văn bản" : "Không thể chuyển tới cuối văn bản", true);

        } else if (MainActivity.ACT_READ_CHAR.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            if (snap.cursor < snap.text.length()) {
                speak(protectedText(describeCharForSpeech(snap.text.charAt(snap.cursor))), true);
            } else if (snap.cursor > 0) {
                speak(protectedText(describeCharForSpeech(snap.text.charAt(snap.cursor - 1))), true);
            } else {
                speak("Trống", true);
            }

        } else if (MainActivity.ACT_READ_WORD.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            String w = getWordAroundPos(snap.text, snap.cursor);
            speak(w.length() > 0 ? protectedText(w) : "Chưa có từ nào", true);

        } else if (MainActivity.ACT_READ_LINE.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            String line = getLineAtPos(snap.text, snap.cursor);
            speak(line.length() > 0 ? protectedText(line) : "Dòng trống", true);

        } else if (MainActivity.ACT_SPEAK_TEXT.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            String all = snap.text.trim();
            speak(all.length() > 0 ? protectedText(all) : "Ô nhập liệu đang trống", true);

        } else if (MainActivity.ACT_SPEAK_STATS.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            String all = snap.text;
            int charCount = all.length();
            String trimmed = all.trim();
            int wordCount = trimmed.length() == 0 ? 0 : trimmed.split("\\s+").length;
            int lineCount = all.length() == 0 ? 0 : all.split("\n", -1).length;
            speak("Thống kê: " + charCount + " ký tự, " + wordCount + " từ, " + lineCount + " dòng.", true);

        } else if (MainActivity.ACT_SET_MARK.equals(actionCode)) {
            DocumentSnapshot snap = readDocumentSnapshot(ic);
            if (!snap.supportsDirectSelection) {
                markPos = -1;
                speak("Ô nhập liệu không hỗ trợ xác định vị trí chọn", true);
            } else {
                markPos = snap.startOffset + snap.cursor;
                triggerFeedback(imeCanvasView, 50L, ToneGenerator.TONE_PROP_ACK, 80);
                speak("Đã cắm mốc chọn văn bản tại vị trí " + markPos, true);
            }

        } else if (MainActivity.ACT_CYCLE_SELECTION_MENU.equals(actionCode)) {
            currentSelectionMenuIndex = (currentSelectionMenuIndex + 1) % selectionMenuOptions.length;
            String optionName = selectionMenuOptions[currentSelectionMenuIndex];
            triggerFeedback(imeCanvasView, 25L, ToneGenerator.TONE_PROP_BEEP, 45);
            speak("Menu: " + optionName, true);

        } else if (MainActivity.ACT_EXEC_SELECTION_MENU.equals(actionCode)) {
            executeSelectionMenuItemOnIc(ic, currentSelectionMenuIndex);

        } else if (MainActivity.ACT_TOGGLE_PRIVACY.equals(actionCode)) {
            privacyScreenActive = !privacyScreenActive;
            savePhoneOptionsFromIme();
            if (imeCanvasView != null) imeCanvasView.invalidate();
            speak(privacyScreenActive ? "Màn hình tối" : "Màn hình sáng", true);

        } else if (MainActivity.ACT_CYCLE_FEEDBACK.equals(actionCode)) {
            feedbackMode = (feedbackMode + 1) % 4;
            savePhoneOptionsFromIme();
            String[] names = new String[]{"Rung và Âm thanh", "Chỉ Rung", "Chỉ Âm thanh", "Tắt phản hồi"};
            speak("Phản hồi bàn phím: " + names[feedbackMode], true);

        } else if (MainActivity.ACT_CYCLE_ECHO.equals(actionCode)) {
            echoMode = BrailleKeyboardEchoSettings.cycle(this);
            speak("Chế độ đọc vọng: "
                    + KeyboardEchoSettings.choiceName(getResources(), echoMode), true);

        } else if (MainActivity.ACT_START_CALIBRATION.equals(actionCode)) {
            if (imeCanvasView != null) {
                imeCanvasView.startTwoTouchCalibration();
            }

        } else if (MainActivity.ACT_TOGGLE_EMOJI.equals(actionCode)) {
            toggleEmojiMode();

        } else if (MainActivity.ACT_EXIT_KEYBOARD.equals(actionCode)) {
            triggerFeedback(imeCanvasView, 50L, ToneGenerator.TONE_PROP_BEEP2, 90);

            speak("Đã đóng bàn phím", true);
            imeInputPolicy.onHidden();
            requestHideSelf(0);
        }
    }

    private void executeSelectionMenuItemOnIc(InputConnection ic, int menuIdx) {
        DocumentSnapshot snap = readDocumentSnapshot(ic);
        int cursorAbsolute = snap.startOffset + snap.cursor;
        int startAbsolute = (markPos >= 0) ? Math.min(markPos, cursorAbsolute) : snap.startOffset;
        int endAbsolute = (markPos >= 0) ? Math.max(markPos, cursorAbsolute)
                : snap.startOffset + snap.text.length();
        if (startAbsolute < snap.startOffset || endAbsolute > snap.startOffset + snap.text.length()) {
            speak("Vùng chọn nằm ngoài phần văn bản hiện có", true);
            return;
        }
        int start = startAbsolute - snap.startOffset;
        int end = endAbsolute - snap.startOffset;

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        switch (menuIdx) {
            case 0: {
                String sub = snap.text.substring(start, end);
                speak(sub.length() > 0 ? ("Vùng chọn: " + protectedText(sub)) : "Chưa có văn bản trong vùng chọn", true);
                break;
            }
            case 1: {
                String sub = snap.text.substring(start, end);
                if (sub.length() > 0 && clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("BrailleText", sub));
                    speak("Đã sao chép: " + protectedText(sub), true);
                } else {
                    speak("Không có nội dung để sao chép", true);
                }
                break;
            }
            case 2: {
                String sub = snap.text.substring(start, end);
                if (sub.length() > 0 && clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("BrailleText", sub));
                    ic.beginBatchEdit();
                    ic.setSelection(startAbsolute, endAbsolute);
                    ic.commitText("", 1);
                    ic.endBatchEdit();
                    markPos = -1;
                    speak("Đã cắt: " + protectedText(sub), true);
                } else {
                    speak("Không có nội dung để cắt", true);
                }
                break;
            }
            case 3: {
                if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                    CharSequence pasted = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
                    if (pasted != null && pasted.length() > 0) {
                        ic.commitText(pasted.toString(), 1);
                        speak("Đã dán: " + protectedText(pasted.toString()), true);
                        break;
                    }
                }
                speak("Bộ nhớ tạm đang trống", true);
                break;
            }
            case 4: {
                if (end > start) {
                    String deleted = snap.text.substring(start, end);
                    ic.beginBatchEdit();
                    ic.setSelection(startAbsolute, endAbsolute);
                    ic.commitText("", 1);
                    ic.endBatchEdit();
                    markPos = -1;
                    speak("Đã xóa vùng chọn: " + protectedText(deleted), true);
                } else {
                    speak("Chưa chọn vùng văn bản nào", true);
                }
                break;
            }
            case 5: {
                if (!ic.performContextMenuAction(android.R.id.selectAll)) {
                    speak("Không thể chọn toàn bộ văn bản", true);
                    break;
                }
                markPos = 0;
                speak("Đã chọn toàn bộ văn bản", true);
                break;
            }
            case 6: {
                speak("Đã đóng bàn phím", true);
                requestHideSelf(0);
                break;
            }
        }
    }

    private String describeCharForSpeech(char c) {
        if (c == ' ') return "dấu cách";
        if (c == '\n') return "xuống dòng";
        if (c == '.') return "dấu chấm";
        if (c == ',') return "dấu phẩy";
        if (c == '?') return "dấu hỏi chấm";
        if (c == '!') return "dấu chấm than";
        return String.valueOf(c);
    }

    private String getWordBeforeCursor(InputConnection ic) {
        if (ic == null) return "";
        CharSequence seq = ic.getTextBeforeCursor(48, 0);
        if (seq == null || seq.length() == 0) return "";
        String s = seq.toString();
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') end--;
        int start = end;
        while (start > 0 && s.charAt(start - 1) != ' ' && s.charAt(start - 1) != '\n') start--;
        return s.substring(start, end);
    }

    private String getWordAroundPos(String text, int cursor) {
        if (text == null || text.length() == 0) return "";
        int pos = Math.max(0, Math.min(cursor, text.length() - 1));
        if (text.charAt(pos) == ' ' && pos > 0) pos--;
        int start = pos;
        while (start > 0 && text.charAt(start - 1) != ' ' && text.charAt(start - 1) != '\n') start--;
        int end = pos;
        while (end < text.length() && text.charAt(end) != ' ' && text.charAt(end) != '\n') end++;
        return text.substring(start, end).trim();
    }

    private int getLineStart(String text, int pos) {
        int p = Math.max(0, Math.min(pos, text.length()));
        while (p > 0 && text.charAt(p - 1) != '\n') p--;
        return p;
    }

    private int getLineEnd(String text, int pos) {
        int p = Math.max(0, Math.min(pos, text.length()));
        while (p < text.length() && text.charAt(p) != '\n') p++;
        return p;
    }

    private String getLineAtPos(String text, int pos) {
        int s = getLineStart(text, pos);
        int e = getLineEnd(text, pos);
        return text.substring(s, e);
    }

    private static class FingerTrace {
        int pointerId;
        float startX;
        float startY;
        float currentX;
        float currentY;
        long downTime;
        int startDot;
    }

    private class BrailleImeCanvasView extends View {

        private static final int CAL_NONE = 0;
        private static final int CAL_STEP_123 = 1;
        private static final int CAL_STEP_456 = 2;

        private int calibrationStep = CAL_NONE;
        private boolean calHoldLocked = false;
        private boolean calCountErrorSpoken = false;

        private final Map<Integer, FingerTrace> activeFingers = new LinkedHashMap<Integer, FingerTrace>();
        private final List<FingerTrace> gestureFingers = new ArrayList<FingerTrace>();
        private int maxPointersInChord = 0;
        private boolean swipeDetected = false;
        private boolean dotCommittedDuringExplore = false;
        private boolean chordCommittedOnTouch = false;
        private int hoverCurrentDot = -1;

        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint topHalfPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bottomHalfPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cellRect = new RectF();

        private final Runnable calibrationHoldRunnable = new Runnable() {
            @Override
            public void run() {
                if (calibrationStep != CAL_NONE && !activeFingers.isEmpty() && !calHoldLocked) {
                    confirmCalibrationStepHold();
                }
            }
        };

        private final Runnable exploreHoldRunnable = new Runnable() {
            @Override
            public void run() {
                if (calibrationStep == CAL_NONE && !swipeDetected && hoverCurrentDot >= 1 && hoverCurrentDot <= 6) {
                    dotCommittedDuringExplore = true;
                    pendingChordDots[hoverCurrentDot] = true;
                    triggerFeedback(BrailleImeCanvasView.this, 22L, ToneGenerator.TONE_PROP_BEEP, 40);
                    invalidate();
                }
            }
        };

        public BrailleImeCanvasView(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            setContentDescription("Bàn phím chữ nổi");

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(3));
            borderPaint.setColor(Color.parseColor("#455A64"));

            topHalfPaint.setStyle(Paint.Style.FILL);
            topHalfPaint.setColor(Color.parseColor("#0D1B2A"));

            bottomHalfPaint.setStyle(Paint.Style.FILL);
            bottomHalfPaint.setColor(Color.parseColor("#1B263B"));

            activeDotPaint.setStyle(Paint.Style.FILL);
            activeDotPaint.setColor(Color.parseColor("#2E7D32"));

            titlePaint.setColor(Color.WHITE);
            titlePaint.setTextSize(dp(25));
            titlePaint.setTextAlign(Paint.Align.CENTER);
            titlePaint.setFakeBoldText(true);

            subPaint.setColor(Color.parseColor("#90A4AE"));
            subPaint.setTextSize(dp(13));
            subPaint.setTextAlign(Paint.Align.CENTER);
        }

        public void startTwoTouchCalibration() {
            calibrationStep = CAL_STEP_123;
            calHoldLocked = false;
            activeFingers.clear();
            gestureFingers.clear();
            mainHandler.removeCallbacks(calibrationHoldRunnable);

            triggerFeedback(this, 60L, ToneGenerator.TONE_PROP_BEEP2, 120);
            speak("Hiệu chuẩn 2 lần chạm. Bước 1: Đặt 3 ngón tay chấm 1, 2, 3 lên màn hình và giữ 1 phẩy 5 giây.", true);
            invalidate();
        }

        private void confirmCalibrationStepHold() {
            List<FingerTrace> pts = new ArrayList<FingerTrace>(activeFingers.values());
            if (pts.isEmpty()) return;

            if (pts.size() != 3) {
                calCountErrorSpoken = true;
                triggerFeedback(this, 200L, ToneGenerator.TONE_PROP_NACK, 200);
                speak("Đang có " + pts.size() + " ngón tay trên màn hình, cần đúng 3 ngón. Hãy nhấc tay lên rồi đặt lại đúng 3 ngón tay.", true);
                return;
            }

            calHoldLocked = true;
            float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
            float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
            for (int i = 0; i < pts.size(); i++) {
                FingerTrace f = pts.get(i);
                if (f.currentX < minX) minX = f.currentX;
                if (f.currentX > maxX) maxX = f.currentX;
                if (f.currentY < minY) minY = f.currentY;
                if (f.currentY > maxY) maxY = f.currentY;
            }

            if ((maxX - minX) >= (maxY - minY)) {
                Collections.sort(pts, new Comparator<FingerTrace>() {
                    @Override
                    public int compare(FingerTrace a, FingerTrace b) {
                        return Float.compare(a.currentX, b.currentX);
                    }
                });
            } else {
                Collections.sort(pts, new Comparator<FingerTrace>() {
                    @Override
                    public int compare(FingerTrace a, FingerTrace b) {
                        return Float.compare(a.currentY, b.currentY);
                    }
                });
            }

            int baseDot = (calibrationStep == CAL_STEP_123) ? 1 : 4;
            for (int i = 0; i < 3; i++) {
                FingerTrace f = pts.get(Math.min(i, pts.size() - 1));
                calDotX[baseDot + i] = f.currentX;
                calDotY[baseDot + i] = f.currentY;
            }

            triggerFeedback(this, 140L, ToneGenerator.TONE_CDMA_CONFIRM, 190);
            if (calibrationStep == CAL_STEP_123) {
                speak("Đã ghi nhận chấm 1, 2, 3. Hãy nhấc tay lên.", true);
            } else {
                speak("Đã ghi nhận chấm 4, 5, 6. Hãy nhấc tay lên để hoàn tất.", true);
            }
            invalidate();
        }

        private int resolveDotAt(float x, float y) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return 1;

            if (useCalibratedPoints) {
                int bestDot = 1;
                float minDistSq = Float.MAX_VALUE;
                for (int d = 1; d <= 6; d++) {
                    float dx = x - calDotX[d];
                    float dy = y - calDotY[d];
                    float distSq = dx * dx + dy * dy;
                    if (distSq < minDistSq) {
                        minDistSq = distSq;
                        bestDot = d;
                    }
                }
                return applyStyleMapping(bestDot);
            }

            boolean isTopHalf = (y < h / 2f);
            int col;
            if (x < w / 3f) col = 0;
            else if (x < (w * 2f) / 3f) col = 1;
            else col = 2;

            int rawDot = isTopHalf ? (1 + col) : (4 + col);
            return applyStyleMapping(rawDot);
        }

        private int applyStyleMapping(int dot) {
            int mapped = dot;
            if (invertKeyboard180) {
                mapped = 7 - mapped;
            }
            if (slateAndStylusMode) {
                if (mapped <= 3) mapped += 3;
                else mapped -= 3;
            }
            return mapped;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (privacyScreenActive) {
                canvas.drawColor(Color.BLACK);
                return;
            }

            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (useCalibratedPoints) {
                canvas.drawColor(Color.parseColor("#0D1B2A"));
                for (int d = 1; d <= 6; d++) {
                    int actualDot = applyStyleMapping(d);
                    Paint fill = pendingChordDots[actualDot] ? activeDotPaint : bottomHalfPaint;
                    canvas.drawCircle(calDotX[d], calDotY[d], dp(36), fill);
                    canvas.drawCircle(calDotX[d], calDotY[d], dp(36), borderPaint);
                    canvas.drawText("CHẤM " + actualDot, calDotX[d], calDotY[d] + dp(8), titlePaint);
                }
                return;
            }

            float halfH = h / 2f;
            float cellW = w / 3f;

            for (int row = 0; row < 2; row++) {
                boolean isTop = (row == 0);
                for (int col = 0; col < 3; col++) {
                    int rawDot = isTop ? (1 + col) : (4 + col);
                    int actualDot = applyStyleMapping(rawDot);

                    float left = col * cellW + dp(4);
                    float top = row * halfH + dp(4);
                    float right = (col + 1) * cellW - dp(4);
                    float bottom = (row + 1) * halfH - dp(4);

                    cellRect.set(left, top, right, bottom);
                    Paint bg = pendingChordDots[actualDot]
                            ? activeDotPaint
                            : (isTop ? topHalfPaint : bottomHalfPaint);

                    canvas.drawRoundRect(cellRect, dp(14), dp(14), bg);
                    canvas.drawRoundRect(cellRect, dp(14), dp(14), borderPaint);

                    float cx = (left + right) / 2f;
                    float cy = (top + bottom) / 2f;
                    canvas.drawText("CHẤM " + actualDot, cx, cy, titlePaint);
                    String posHint = isTop ? "(Phía Đầu Loa)" : "(Phía Cổng Sạc)";
                    canvas.drawText(posHint, cx, cy + dp(24), subPaint);
                }
            }
        }

        @Override
        public boolean onHoverEvent(MotionEvent event) {
            // Hover is not multi-touch. Never synthesize input from accessibility exploration.
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!BrailleMeridianBridge.isAvailable()) {
                cancelGesture();
                return true;
            }
            int action = event.getActionMasked();
            int actionIndex = event.getActionIndex();

            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    activeFingers.clear();
                    gestureFingers.clear();
                    maxPointersInChord = 1;
                    swipeDetected = false;
                    dotCommittedDuringExplore = false;
                    chordCommittedOnTouch = false;

                    FingerTrace f = createTrace(event, 0);
                    activeFingers.put(f.pointerId, f);
                    gestureFingers.add(f);
                    hoverCurrentDot = f.startDot;

                    mainHandler.removeCallbacks(singleFingerCommitRunnable);

                    if (calibrationStep != CAL_NONE) {
                        mainHandler.removeCallbacks(calibrationHoldRunnable);
                        mainHandler.postDelayed(calibrationHoldRunnable, CALIBRATION_HOLD_MS);
                    } else {
                        mainHandler.removeCallbacks(exploreHoldRunnable);
                        mainHandler.postDelayed(exploreHoldRunnable, EXPLORE_HOLD_DOT_MS);
                    }
                    return true;
                }

                case MotionEvent.ACTION_POINTER_DOWN: {
                    FingerTrace f = createTrace(event, actionIndex);
                    activeFingers.put(f.pointerId, f);
                    gestureFingers.add(f);
                    if (activeFingers.size() > maxPointersInChord) {
                        maxPointersInChord = activeFingers.size();
                    }

                    mainHandler.removeCallbacks(exploreHoldRunnable);

                    if (calibrationStep != CAL_NONE) {
                        mainHandler.removeCallbacks(calibrationHoldRunnable);
                        mainHandler.postDelayed(calibrationHoldRunnable, CALIBRATION_HOLD_MS);
                    } else {
                        if (chordCommittedOnTouch) {
                            return true;
                        }
                        for (FingerTrace trace : activeFingers.values()) {
                            pendingChordDots[trace.startDot] = true;
                        }
                        triggerFeedback(this, 20L, ToneGenerator.TONE_PROP_BEEP, 35);
                        invalidate();
                        // Do not commit before ACTION_UP. ACTION_CANCEL must remain reversible.
                    }
                    return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    for (int i = 0; i < event.getPointerCount(); i++) {
                        int pid = event.getPointerId(i);
                        FingerTrace f = activeFingers.get(pid);
                        if (f != null) {
                            f.currentX = event.getX(i);
                            f.currentY = event.getY(i);

                            float dx = f.currentX - f.startX;
                            float dy = f.currentY - f.startY;
                            long elapsed = android.os.SystemClock.uptimeMillis() - f.downTime;

                            if (calibrationStep == CAL_NONE
                                    && BrailleSpeedSettings.isSwipe(dx, dy, elapsed, gestureDelayMs, dp(swipeDistanceDp))) {
                                swipeDetected = true;
                                mainHandler.removeCallbacks(exploreHoldRunnable);
                            }
                        }
                    }

                    if (calibrationStep == CAL_NONE && maxPointersInChord == 1 && !swipeDetected && event.getPointerCount() > 0) {
                        int d = resolveDotAt(event.getX(0), event.getY(0));
                        if (d != hoverCurrentDot) {
                            hoverCurrentDot = d;
                            mainHandler.removeCallbacks(exploreHoldRunnable);
                            mainHandler.postDelayed(exploreHoldRunnable, EXPLORE_HOLD_DOT_MS);
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_POINTER_UP: {
                    int pid = event.getPointerId(actionIndex);
                    updateTrace(event, actionIndex);
                    activeFingers.remove(pid);
                    return true;
                }

                case MotionEvent.ACTION_CANCEL: {
                    cancelGesture();
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    updateTrace(event, actionIndex);
                    mainHandler.removeCallbacks(calibrationHoldRunnable);
                    mainHandler.removeCallbacks(exploreHoldRunnable);

                    if (calibrationStep != CAL_NONE) {
                        handleCalibrationFingerRelease();
                    } else {
                        handleTypingFingerRelease();
                    }

                    activeFingers.clear();
                    gestureFingers.clear();
                    return true;
                }
            }
            return super.onTouchEvent(event);
        }

        public void cancelGesture() {
            mainHandler.removeCallbacks(calibrationHoldRunnable);
            mainHandler.removeCallbacks(exploreHoldRunnable);
            mainHandler.removeCallbacks(singleFingerCommitRunnable);
            activeFingers.clear();
            gestureFingers.clear();
            maxPointersInChord = 0;
            swipeDetected = false;
            dotCommittedDuringExplore = false;
            chordCommittedOnTouch = false;
            calHoldLocked = false;
            calibrationStep = CAL_NONE;
            calCountErrorSpoken = false;
            hoverCurrentDot = -1;
            clearPendingDots();
            invalidate();
        }

        private FingerTrace createTrace(MotionEvent event, int index) {
            FingerTrace f = new FingerTrace();
            f.pointerId = event.getPointerId(index);
            f.startX = event.getX(index);
            f.startY = event.getY(index);
            f.currentX = f.startX;
            f.currentY = f.startY;
            f.downTime = android.os.SystemClock.uptimeMillis();
            f.startDot = resolveDotAt(f.startX, f.startY);
            return f;
        }

        private void updateTrace(MotionEvent event, int index) {
            FingerTrace trace = activeFingers.get(event.getPointerId(index));
            if (trace != null) {
                trace.currentX = event.getX(index);
                trace.currentY = event.getY(index);
            }
        }

        private void handleCalibrationFingerRelease() {
            if (!calHoldLocked) {
                if (!calCountErrorSpoken) {
                    speak("Chưa ghi nhận được. Hãy đặt đúng 3 ngón tay và giữ ít nhất 1 phẩy 5 giây rồi mới thả ra.", true);
                }
                calCountErrorSpoken = false;
                return;
            }
            calHoldLocked = false;
            if (calibrationStep == CAL_STEP_123) {
                calibrationStep = CAL_STEP_456;
                speak("Bước 2: Đặt tiếp 3 ngón tay chấm 4, 5, 6 và giữ 1 phẩy 5 giây.", false);
            } else if (calibrationStep == CAL_STEP_456) {
                calibrationStep = CAL_NONE;
                useCalibratedPoints = true;
                savePhoneOptionsFromIme();
                speak("Đã hoàn tất hiệu chuẩn 6 ngón tay.", false);
                invalidate();
            }
        }

        private void handleTypingFingerRelease() {
            if (gestureFingers.isEmpty()) return;

            if (chordCommittedOnTouch) {
                chordCommittedOnTouch = false;
                return;
            }

            FingerTrace primary = gestureFingers.get(0);
            float dx = primary.currentX - primary.startX;
            float dy = primary.currentY - primary.startY;
            long elapsed = android.os.SystemClock.uptimeMillis() - primary.downTime;

            if (swipeDetected || (BrailleSpeedSettings.isSwipe(dx, dy, elapsed, gestureDelayMs, dp(swipeDistanceDp)))) {
                String direction;
                if (Math.abs(dy) >= Math.abs(dx)) {
                    direction = (dy > 0) ? "DOWN" : "UP";
                } else {
                    direction = (dx > 0) ? "RIGHT" : "LEFT";
                }
                boolean twoFingers = (maxPointersInChord >= 2);
                dispatchSwipeGesture(primary.startDot, direction, twoFingers,
                        BrailleSpeedSettings.isLongSwipe(dx, dy, dp(longSwipeDistanceDp)));
                return;
            }

            if (maxPointersInChord >= 2) {
                for (int i = 0; i < gestureFingers.size(); i++) {
                    FingerTrace f = gestureFingers.get(i);
                    pendingChordDots[f.startDot] = true;
                }
                commitPendingDotsNow();
                return;
            }

            int endDot = resolveDotAt(primary.currentX, primary.currentY);
            if (!dotCommittedDuringExplore && endDot == primary.startDot) {
                addDotToPendingChord(primary.startDot, this);
            } else {
                mainHandler.removeCallbacks(singleFingerCommitRunnable);
                mainHandler.postDelayed(singleFingerCommitRunnable, letterDelayMs);
            }
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    @Override
    public void onDestroy() {
        cancelPendingInput();
        if (toneGenerator != null) {
            try {
                toneGenerator.release();
            } catch (Throwable ignored) {
            }
        }
        releaseSlot(typingSlot);
        releaseSlot(capitalSlot);
        releaseSlot(spaceSlot);
        super.onDestroy();
    }
}
