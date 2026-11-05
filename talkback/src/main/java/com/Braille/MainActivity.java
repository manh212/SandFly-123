package com.Braille;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
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
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    /** Registered only on Android 13+, where predictive back bypasses onBackPressed(). */
    private android.window.OnBackInvokedCallback brailleBackCallback;

    private static final String PREFS_NAME = "BraillePhoneBreakthroughV2Prefs";
    private static final String ALPHABET_EXPORT_HEADER = "BRAILLE_ALPHABET_V1";
    private static final String EMOJI_EXPORT_HEADER = "BRAILLE_EMOJI_V1";

    private static final long CALIBRATION_HOLD_MS = 1500L;
    private static final long DEFAULT_LETTER_DELAY_MS = BrailleSpeedSettings.DEFAULT_LETTER_DELAY_MS;
    private static final long EXPLORE_HOLD_DOT_MS = 230L;
    private static final long DOUBLE_SPACE_THRESHOLD_MS = 600L;
    private static final long DOUBLE_SWIPE_CONFIRM_MS = 2500L;
    private static final long DEFAULT_GESTURE_DELAY_MS = BrailleSpeedSettings.DEFAULT_GESTURE_DELAY_MS;

    public static final String ACT_SPACE = "SPACE";
    public static final String ACT_BACKSPACE = "BACKSPACE";
    public static final String ACT_DELETE_WORD = "DELETE_WORD";
    public static final String ACT_DELETE_CHAR_AT_CURSOR = "DELETE_CHAR_AT_CURSOR";
    public static final String ACT_DELETE_WORD_AT_CURSOR = "DELETE_WORD_AT_CURSOR";
    public static final String ACT_DELETE_LINE = "DELETE_LINE";
    public static final String ACT_CLEAR_ALL = "CLEAR_ALL";
    public static final String ACT_ENTER = "ENTER";

    public static final String ACT_MOVE_CHAR_NEXT = "MOVE_CHAR_NEXT";
    public static final String ACT_MOVE_CHAR_PREV = "MOVE_CHAR_PREV";
    public static final String ACT_MOVE_WORD_NEXT = "MOVE_WORD_NEXT";
    public static final String ACT_MOVE_WORD_PREV = "MOVE_WORD_PREV";
    public static final String ACT_MOVE_LINE_NEXT = "MOVE_LINE_NEXT";
    public static final String ACT_MOVE_LINE_PREV = "MOVE_LINE_PREV";
    public static final String ACT_MOVE_LINE_START = "MOVE_LINE_START";
    public static final String ACT_MOVE_LINE_END = "MOVE_LINE_END";
    public static final String ACT_MOVE_LINE_BOUNDARY_NEXT = "MOVE_LINE_BOUNDARY_NEXT";
    public static final String ACT_MOVE_START = "MOVE_START";
    public static final String ACT_MOVE_END = "MOVE_END";

    public static final String ACT_READ_CHAR = "READ_CHAR";
    public static final String ACT_READ_WORD = "READ_WORD";
    public static final String ACT_READ_LINE = "READ_LINE";
    public static final String ACT_SPEAK_TEXT = "SPEAK_TEXT";
    public static final String ACT_SPEAK_STATS = "SPEAK_STATS";

    public static final String ACT_SET_MARK = "SET_MARK";
    public static final String ACT_CYCLE_SELECTION_MENU = "CYCLE_SELECTION_MENU";
    public static final String ACT_EXEC_SELECTION_MENU = "EXEC_SELECTION_MENU";

    public static final String ACT_TOGGLE_PRIVACY = "TOGGLE_PRIVACY";
    public static final String ACT_CYCLE_FEEDBACK = "CYCLE_FEEDBACK";
    public static final String ACT_CYCLE_ECHO = "CYCLE_ECHO";
    public static final String ACT_START_CALIBRATION = "START_CALIBRATION";
    public static final String ACT_EXIT_KEYBOARD = "EXIT_KEYBOARD";
    public static final String ACT_TOGGLE_EMOJI = "TOGGLE_EMOJI";
    public static final String ACT_NONE = "NONE";

    public static final String GES_DOT1_DOWN = "DOT1_DOWN";
    public static final String GES_DOT1_UP = "DOT1_UP";
    public static final String GES_DOT1_LEFT = "DOT1_LEFT";
    public static final String GES_DOT1_RIGHT = "DOT1_RIGHT";

    public static final String GES_DOT2_DOWN = "DOT2_DOWN";
    public static final String GES_DOT2_UP = "DOT2_UP";
    public static final String GES_DOT2_LEFT = "DOT2_LEFT";
    public static final String GES_DOT2_RIGHT = "DOT2_RIGHT";

    public static final String GES_DOT3_DOWN = "DOT3_DOWN";
    public static final String GES_DOT3_UP = "DOT3_UP";
    public static final String GES_DOT3_LEFT = "DOT3_LEFT";
    public static final String GES_DOT3_RIGHT = "DOT3_RIGHT";

    public static final String GES_DOT4_DOWN = "DOT4_DOWN";
    public static final String GES_DOT4_UP = "DOT4_UP";
    public static final String GES_DOT4_LEFT = "DOT4_LEFT";
    public static final String GES_DOT4_RIGHT = "DOT4_RIGHT";

    public static final String GES_DOT5_DOWN = "DOT5_DOWN";
    public static final String GES_DOT5_UP = "DOT5_UP";
    public static final String GES_DOT5_LEFT = "DOT5_LEFT";
    public static final String GES_DOT5_RIGHT = "DOT5_RIGHT";

    public static final String GES_DOT6_DOWN = "DOT6_DOWN";
    public static final String GES_DOT6_UP = "DOT6_UP";
    public static final String GES_DOT6_LEFT = "DOT6_LEFT";
    public static final String GES_DOT6_RIGHT = "DOT6_RIGHT";

    public static final String GES_GLOBAL_DOWN_1 = "GLOBAL_DOWN_1";
    public static final String GES_GLOBAL_UP_1 = "GLOBAL_UP_1";
    public static final String GES_GLOBAL_LEFT_1 = "GLOBAL_LEFT_1";
    public static final String GES_GLOBAL_RIGHT_1 = "GLOBAL_RIGHT_1";

    public static final String GES_GLOBAL_DOWN_2 = "GLOBAL_DOWN_2";
    public static final String GES_GLOBAL_UP_2 = "GLOBAL_UP_2";
    public static final String GES_GLOBAL_LEFT_2 = "GLOBAL_LEFT_2";
    public static final String GES_GLOBAL_RIGHT_2 = "GLOBAL_RIGHT_2";

    private static final int FEEDBACK_BOTH = 0;
    private static final int FEEDBACK_VIBRATE_ONLY = 1;
    private static final int FEEDBACK_SOUND_ONLY = 2;
    private static final int FEEDBACK_NONE = 3;

    private static final int ECHO_CHAR_AND_WORD = TextEventFilter.PREF_ECHO_CHARACTERS_AND_WORDS;
    private static final int ECHO_CHAR_ONLY = TextEventFilter.PREF_ECHO_CHARACTERS;
    private static final int ECHO_WORD_ONLY = TextEventFilter.PREF_ECHO_WORDS;
    private static final int ECHO_NONE = TextEventFilter.PREF_ECHO_NONE;

    private SharedPreferences prefs;
    private ToneGenerator toneGenerator;
    private Vibrator vibrator;

    private final StringBuilder textBuffer = new StringBuilder();
    private int cursorPos = 0;
    private int markPos = -1;
    private final BrailleTextOperations.NavigationState navigationState =
            new BrailleTextOperations.NavigationState();

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

    private boolean nextCharCapital = false;
    private boolean capsLockWord = false;
    private String carriedToneName = null;
    private int carriedToneCursor = -1;
    private boolean carriedToneUsed = false;
    private long letterDelayMs = DEFAULT_LETTER_DELAY_MS;
    private long gestureDelayMs = DEFAULT_GESTURE_DELAY_MS;
    private int swipeDistanceDp = BrailleSpeedSettings.DEFAULT_SWIPE_DISTANCE_DP;
    private int longSwipeDistanceDp = BrailleSpeedSettings.DEFAULT_LONG_SWIPE_DISTANCE_DP;
    private boolean distinguishSwipeLength = false;
    private boolean numberModeActive = false;
    private long lastSpaceTimeMs = 0L;
    private long lastDeleteLineRequestMs = 0L;
    private long lastClearAllRequestMs = 0L;

    private boolean usePerDotGestures = true;
    private boolean slateAndStylusMode = false;
    private boolean invertKeyboard180 = false;
    private boolean autoCapitalize = true;
    private boolean doubleSpacePeriod = true;
    private boolean speakPasswordChars = false;
    private boolean privacyScreenActive = false;
    private int feedbackMode = FEEDBACK_BOTH;
    @TextEventFilter.KeyboardEchoType private int echoMode = ECHO_CHAR_AND_WORD;

    private boolean useCalibratedPoints = false;
    private boolean calibrationDirty = false;
    private final float[] calDotX = new float[7];
    private final float[] calDotY = new float[7];

    private boolean isKeyboardActive = false;

    private final LinkedHashMap<String, String> charToDotsMap = new LinkedHashMap<String, String>();
    private final LinkedHashMap<String, String> dotsToCharMap = new LinkedHashMap<String, String>();
    private final LinkedHashMap<String, String> gestureActionMap = new LinkedHashMap<String, String>();
    private final LinkedHashMap<String, String> gestureNameMap = new LinkedHashMap<String, String>();
    private final LinkedHashMap<String, String> actionNameMap = new LinkedHashMap<String, String>();

    private LinearLayout mainScreenContainer;
    private FrameLayout tabContentHolder;
    private int currentSettingsTab = 0;
    private int capitalSignCount = 0;
    private boolean capsLockSticky = false;
    private FrameLayout fullScreenKeyboardContainer;
private BraillePhoneCanvasView brailleCanvasView;


    private TextView tvVirtualInputDisplay;
    private Button btnTabGesture;
    private Button btnTabAlphabet;
    private Button btnTabPhoneOptions;
    private Button btnTabSound;
    private ScrollView sectionSoundLayout;
    private ScrollView sectionSoundMenuLayout;
    private ScrollView sectionVibrationLayout;
    private android.widget.RadioGroup rgVibrationLevel;
    private int vibrationLevel = 1;
    private TextView tvCustomSoundName;
    private MediaPlayer customSoundPlayer;
    private boolean useCustomSound = false;
    private String customSoundUri = "";
    private static final int REQUEST_PICK_SOUND = 4101;
    private static final int REQUEST_PICK_SOUND_CAPITAL = 4102;
    private static final int REQUEST_PICK_SOUND_SPACE = 4103;
    private static final int REQUEST_EXPORT_ALPHABET_FILE = 4104;
    private static final int REQUEST_IMPORT_ALPHABET_FILE = 4105;
    private static final int REQUEST_EXPORT_EMOJI_FILE = 4106;
    private static final int REQUEST_IMPORT_EMOJI_FILE = 4107;
    private static final int SOUND_SLOT_TYPING = 0;
    private static final int SOUND_SLOT_CAPITAL = 1;
    private static final int SOUND_SLOT_SPACE = 2;

    private static class SoundSlot {
        String uri = "";
        boolean failedNotified;
        MediaPlayer player;
    }

    private final SoundSlot capitalSlot = new SoundSlot();
    private final SoundSlot spaceSlot = new SoundSlot();
    private boolean customSoundFailedNotified = false;
    private TextView tvCapitalSoundName;
    private TextView tvSpaceSoundName;

    private LinearLayout sectionGestureLayout;
    private LinearLayout sectionAlphabetLayout;
    private ScrollView sectionPhoneOptionsLayout;

    private ListView lvGestures;
    private ArrayAdapter<String> gestureListAdapter;
    private final List<String> gestureKeysList = new ArrayList<String>();
    private final List<String> gestureDisplayList = new ArrayList<String>();

    private ListView lvAlphabet;
    private ArrayAdapter<String> alphabetListAdapter;
    private final List<String> alphabetKeysList = new ArrayList<String>();
    private final List<String> alphabetDisplayList = new ArrayList<String>();
    private final LinkedHashMap<String, String> emojiToDotsMap = new LinkedHashMap<String, String>();
    private ListView lvEmoji;
    private ArrayAdapter<String> emojiListAdapter;
    private final List<String> emojiKeysList = new ArrayList<String>();
    private final List<String> emojiDisplayList = new ArrayList<String>();
    private LinearLayout sectionEmojiLayout;
    private Button btnTabEmoji;

    private final boolean[] pendingChordDots = new boolean[7];
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable singleFingerCommitRunnable = new Runnable() {
        @Override
        public void run() {
            commitPendingDotsNow();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BrailleMeridianBridge.isAvailable()) {
            finish();
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            brailleBackCallback = this::handleBrailleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    brailleBackCallback);
        }
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initFeedbackEngines();
        initDefaultMaps();
        loadSavedSettings();
        loadEmojiSettings();

        View rootView = buildRootInterface();
        setContentView(rootView);

        switchSettingsTab(0);
        updateVirtualInputDisplay();
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

    private void triggerFeedback(View view, long vibrateMs, int toneType, int toneDurationMs) {

        triggerFeedback(view, vibrateMs, toneType, toneDurationMs, SOUND_SLOT_TYPING);
    }

    private void triggerFeedback(View view, long vibrateMs, int toneType, int toneDurationMs, int soundSlot) {
        boolean allowVibrate = (feedbackMode == FEEDBACK_BOTH || feedbackMode == FEEDBACK_VIBRATE_ONLY);
        boolean allowSound = (feedbackMode == FEEDBACK_BOTH || feedbackMode == FEEDBACK_SOUND_ONLY);

        if (allowVibrate) {
            vibrateForLevel(view, vibrateMs);
        }

        if (allowSound) {
if (soundSlot == SOUND_SLOT_CAPITAL && playSlot(capitalSlot)) {
} else if (soundSlot == SOUND_SLOT_SPACE && playSlot(spaceSlot)) {
} else if (soundSlot == SOUND_SLOT_TYPING && useCustomSound && customSoundUri != null && customSoundUri.length() > 0) {
playCustomSound();
} else if (toneGenerator != null && toneType >= 0) {
try {
toneGenerator.startTone(toneType, toneDurationMs);
} catch (Throwable ignored) {
}
}
}
}


    private void speak(String text, boolean flush) {
        BrailleMeridianBridge.speak(text, flush);
    }

    private void speakTyping(String text, boolean flush) {
        echoMode = BrailleKeyboardEchoSettings.read(this);
        if (echoMode == ECHO_NONE || BrailleKeyboardEchoSettings.isTypingSilent(echoMode)) return;
        speak(text, flush);
    }

    private void initDefaultMaps() {
        gestureNameMap.clear();

        gestureNameMap.put(GES_DOT1_DOWN, "Chấm 1: Vuốt Xuống");
        gestureNameMap.put(GES_DOT1_UP, "Chấm 1: Vuốt Lên");
        gestureNameMap.put(GES_DOT1_LEFT, "Chấm 1: Vuốt Sang trái");
        gestureNameMap.put(GES_DOT1_RIGHT, "Chấm 1: Vuốt Sang phải");

        gestureNameMap.put(GES_DOT2_DOWN, "Chấm 2: Vuốt Xuống");
        gestureNameMap.put(GES_DOT2_UP, "Chấm 2: Vuốt Lên");
        gestureNameMap.put(GES_DOT2_LEFT, "Chấm 2: Vuốt Sang trái");
        gestureNameMap.put(GES_DOT2_RIGHT, "Chấm 2: Vuốt Sang phải");

        gestureNameMap.put(GES_DOT3_DOWN, "Chấm 3: Vuốt Xuống");
        gestureNameMap.put(GES_DOT3_UP, "Chấm 3: Vuốt Lên");
        gestureNameMap.put(GES_DOT3_LEFT, "Chấm 3: Vuốt Sang trái");
        gestureNameMap.put(GES_DOT3_RIGHT, "Chấm 3: Vuốt Sang phải");

        gestureNameMap.put(GES_DOT4_DOWN, "Chấm 4: Vuốt Xuống");
        gestureNameMap.put(GES_DOT4_UP, "Chấm 4: Vuốt Lên");
        gestureNameMap.put(GES_DOT4_LEFT, "Chấm 4: Vuốt Sang trái");
        gestureNameMap.put(GES_DOT4_RIGHT, "Chấm 4: Vuốt Sang phải");

        gestureNameMap.put(GES_DOT5_DOWN, "Chấm 5: Vuốt Xuống");
        gestureNameMap.put(GES_DOT5_UP, "Chấm 5: Vuốt Lên");
        gestureNameMap.put(GES_DOT5_LEFT, "Chấm 5: Vuốt Sang trái");
        gestureNameMap.put(GES_DOT5_RIGHT, "Chấm 5: Vuốt Sang phải");

        gestureNameMap.put(GES_DOT6_DOWN, "Chấm 6: Vuốt Xuống");
        gestureNameMap.put(GES_DOT6_UP, "Chấm 6: Vuốt Lên");
        gestureNameMap.put(GES_DOT6_LEFT, "Chấm 6: Vuốt Sang trái");
        gestureNameMap.put(GES_DOT6_RIGHT, "Chấm 6: Vuốt Sang phải");

        gestureNameMap.put(GES_GLOBAL_DOWN_1, "Toàn màn hình (1 ngón): Vuốt Xuống");
        gestureNameMap.put(GES_GLOBAL_UP_1, "Toàn màn hình (1 ngón): Vuốt Lên");
        gestureNameMap.put(GES_GLOBAL_LEFT_1, "Toàn màn hình (1 ngón): Vuốt Sang trái");
        gestureNameMap.put(GES_GLOBAL_RIGHT_1, "Toàn màn hình (1 ngón): Vuốt Sang phải");

        gestureNameMap.put(GES_GLOBAL_DOWN_2, "Vuốt 2 ngón: Xuống Dưới");
        gestureNameMap.put(GES_GLOBAL_UP_2, "Vuốt 2 ngón: Lên Trên");
        gestureNameMap.put(GES_GLOBAL_LEFT_2, "Vuốt 2 ngón: Sang Trái");
        gestureNameMap.put(GES_GLOBAL_RIGHT_2, "Vuốt 2 ngón: Sang Phải");
        for (Map.Entry<String, String> entry : new ArrayList<Map.Entry<String, String>>(gestureNameMap.entrySet())) {
            gestureNameMap.put(entry.getKey() + "_LONG", entry.getValue() + " (dài)");
        }

        actionNameMap.clear();
        actionNameMap.put(ACT_SPACE, "Thêm dấu cách (Space)");
        actionNameMap.put(ACT_BACKSPACE, "Xóa 1 ký tự (Backspace)");
        actionNameMap.put(ACT_DELETE_WORD, "Xóa 1 từ trước con trỏ");
        actionNameMap.put(ACT_DELETE_CHAR_AT_CURSOR, "Xóa ký tự đang trỏ tới");
        actionNameMap.put(ACT_DELETE_WORD_AT_CURSOR, "Xóa từ đang trỏ tới");
        actionNameMap.put(ACT_DELETE_LINE, "Xóa dòng hiện tại (Vuốt 2 lần)");
        actionNameMap.put(ACT_CLEAR_ALL, "Xóa toàn bộ văn bản (Vuốt 2 lần)");
        actionNameMap.put(ACT_ENTER, "Xuống dòng (Enter)");

        actionNameMap.put(ACT_MOVE_CHAR_NEXT, "Di chuyển: Tiến 1 ký tự");
        actionNameMap.put(ACT_MOVE_CHAR_PREV, "Di chuyển: Lùi 1 ký tự");
        actionNameMap.put(ACT_MOVE_WORD_NEXT, "Di chuyển: Tiến 1 từ");
        actionNameMap.put(ACT_MOVE_WORD_PREV, "Di chuyển: Lùi 1 từ");
        actionNameMap.put(ACT_MOVE_LINE_NEXT, "Di chuyển: Tiến 1 dòng");
        actionNameMap.put(ACT_MOVE_LINE_PREV, "Di chuyển: Lùi 1 dòng");
        actionNameMap.put(ACT_MOVE_LINE_START, "Di chuyển: Về đầu dòng hiện tại");
        actionNameMap.put(ACT_MOVE_LINE_END, "Di chuyển: Về cuối dòng hiện tại");
        actionNameMap.put(ACT_MOVE_LINE_BOUNDARY_NEXT, "Di chuyển: Lần lượt đầu và cuối từng dòng");
        actionNameMap.put(ACT_MOVE_START, "Di chuyển: Về đầu văn bản");
        actionNameMap.put(ACT_MOVE_END, "Di chuyển: Về cuối văn bản");

        actionNameMap.put(ACT_READ_CHAR, "Đọc: Ký tự tại con trỏ");
        actionNameMap.put(ACT_READ_WORD, "Đọc: Từ tại con trỏ");
        actionNameMap.put(ACT_READ_LINE, "Đọc: Dòng hiện tại");
        actionNameMap.put(ACT_SPEAK_TEXT, "Đọc: Toàn bộ văn bản");
        actionNameMap.put(ACT_SPEAK_STATS, "Đọc: Số ký tự, số từ, số dòng");

        actionNameMap.put(ACT_SET_MARK, "Bôi đen: Đặt mốc chọn văn bản (Set Mark)");
        actionNameMap.put(ACT_CYCLE_SELECTION_MENU, "Bôi đen: Duyệt menu Sao chép / Cắt / Dán");
        actionNameMap.put(ACT_EXEC_SELECTION_MENU, "Bôi đen: Thực hiện mục menu đã chọn");

        actionNameMap.put(ACT_TOGGLE_PRIVACY, "Màn hình Tối / Sáng (Riêng tư)");
        actionNameMap.put(ACT_CYCLE_FEEDBACK, "Đổi chế độ Rung / Âm thanh");
        actionNameMap.put(ACT_CYCLE_ECHO, "Đổi chế độ Đọc vọng (Ký tự / Từ)");
        actionNameMap.put(ACT_START_CALIBRATION, "Hiệu chuẩn vị trí 6 ngón tay (1.5 giây)");
        actionNameMap.put(ACT_EXIT_KEYBOARD, "Viết xong (Đóng bàn phím)");
        actionNameMap.put(ACT_TOGGLE_EMOJI, "Chuyển bảng chữ cái / bảng biểu tượng cảm xúc");
        actionNameMap.put(ACT_NONE, "Không làm gì");

        resetDefaultGestures();
        resetDefaultVietnameseBraille();
    }

    private void resetDefaultGestures() {
        gestureActionMap.clear();

        gestureActionMap.put(GES_DOT1_DOWN, ACT_MOVE_CHAR_NEXT);
        gestureActionMap.put(GES_DOT1_UP, ACT_MOVE_CHAR_PREV);
        gestureActionMap.put(GES_DOT1_LEFT, ACT_READ_CHAR);
        gestureActionMap.put(GES_DOT1_RIGHT, ACT_CYCLE_FEEDBACK);

        gestureActionMap.put(GES_DOT2_DOWN, ACT_MOVE_WORD_NEXT);
        gestureActionMap.put(GES_DOT2_UP, ACT_MOVE_WORD_PREV);
        gestureActionMap.put(GES_DOT2_LEFT, ACT_READ_WORD);
        gestureActionMap.put(GES_DOT2_RIGHT, ACT_CYCLE_ECHO);

        gestureActionMap.put(GES_DOT3_DOWN, ACT_MOVE_LINE_NEXT);
        gestureActionMap.put(GES_DOT3_UP, ACT_MOVE_LINE_PREV);
        gestureActionMap.put(GES_DOT3_LEFT, ACT_READ_LINE);
        gestureActionMap.put(GES_DOT3_RIGHT, ACT_TOGGLE_EMOJI);

        gestureActionMap.put(GES_DOT4_DOWN, ACT_SPACE);
        gestureActionMap.put(GES_DOT4_UP, ACT_BACKSPACE);
        gestureActionMap.put(GES_DOT4_LEFT, ACT_TOGGLE_PRIVACY);
        gestureActionMap.put(GES_DOT4_RIGHT, ACT_ENTER);

        gestureActionMap.put(GES_DOT5_DOWN, ACT_SPACE);
        gestureActionMap.put(GES_DOT5_UP, ACT_DELETE_WORD);
        gestureActionMap.put(GES_DOT5_LEFT, ACT_SET_MARK);
        gestureActionMap.put(GES_DOT5_RIGHT, ACT_EXIT_KEYBOARD);

        gestureActionMap.put(GES_DOT6_DOWN, ACT_CYCLE_SELECTION_MENU);
        gestureActionMap.put(GES_DOT6_UP, ACT_DELETE_LINE);
        gestureActionMap.put(GES_DOT6_LEFT, ACT_EXEC_SELECTION_MENU);
        gestureActionMap.put(GES_DOT6_RIGHT, ACT_SPEAK_TEXT);

        gestureActionMap.put(GES_GLOBAL_DOWN_1, ACT_SPACE);
        gestureActionMap.put(GES_GLOBAL_UP_1, ACT_BACKSPACE);
        gestureActionMap.put(GES_GLOBAL_LEFT_1, ACT_TOGGLE_PRIVACY);
        gestureActionMap.put(GES_GLOBAL_RIGHT_1, ACT_SPEAK_TEXT);

        gestureActionMap.put(GES_GLOBAL_DOWN_2, ACT_ENTER);
        gestureActionMap.put(GES_GLOBAL_UP_2, ACT_DELETE_WORD);
        gestureActionMap.put(GES_GLOBAL_LEFT_2, ACT_EXIT_KEYBOARD);
        gestureActionMap.put(GES_GLOBAL_RIGHT_2, ACT_START_CALIBRATION);
        for (String key : gestureNameMap.keySet()) {
            if (key.endsWith("_LONG")) gestureActionMap.remove(key);
        }
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

        rebuildDotsLookupMap();
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

    private void loadSavedSettings() {
        usePerDotGestures = prefs.getBoolean("use_per_dot_gestures", true);
        slateAndStylusMode = prefs.getBoolean("slate_and_stylus", false);
        invertKeyboard180 = prefs.getBoolean("invert_180", false);
        autoCapitalize = prefs.getBoolean("auto_capitalize", true);
        doubleSpacePeriod = prefs.getBoolean("double_space_period", true);
        speakPasswordChars = prefs.getBoolean("speak_password_chars", false);
        privacyScreenActive = prefs.getBoolean("privacy_screen", false);
        feedbackMode = prefs.getInt("feedback_mode", FEEDBACK_BOTH);
        echoMode = BrailleKeyboardEchoSettings.read(this);
        letterDelayMs = BrailleSpeedSettings.letterDelay(prefs);
        gestureDelayMs = BrailleSpeedSettings.gestureDelay(prefs);
        swipeDistanceDp = BrailleSpeedSettings.swipeDistance(prefs);
        longSwipeDistanceDp = BrailleSpeedSettings.longSwipeDistance(prefs);
        distinguishSwipeLength = prefs.getBoolean(BrailleSpeedSettings.KEY_DISTINGUISH_SWIPE_LENGTH, false);
        useCustomSound = prefs.getBoolean("use_custom_sound", false);
        customSoundUri = prefs.getString("custom_sound_uri", "");
        if (customSoundUri == null) {
            customSoundUri = "";
        }
        capitalSlot.uri = prefs.getString("custom_sound_capital_uri", "");
        spaceSlot.uri = prefs.getString("custom_sound_space_uri", "");
        if (capitalSlot.uri == null) {
            capitalSlot.uri = "";
        }
        if (spaceSlot.uri == null) {
            spaceSlot.uri = "";
        }
        migrateSoundsToPrivateStorage();

        vibrationLevel = prefs.getInt("vibration_level", 1);
        useCalibratedPoints = prefs.getBoolean("use_calibrated_points", false);
for (int i = 1; i <= 6; i++) {
calDotX[i] = prefs.getFloat("cal_x_" + i, 0f);
calDotY[i] = prefs.getFloat("cal_y_" + i, 0f);
}


        for (String gestureKey : gestureNameMap.keySet()) {
            String savedAction = prefs.getString("ges_" + gestureKey, null);
            if (savedAction != null && actionNameMap.containsKey(savedAction)) {
                gestureActionMap.put(gestureKey, savedAction);
            }
        }

        String customKeysCsv = prefs.getString("custom_char_keys", null);
        if (customKeysCsv != null) {
            charToDotsMap.clear();
        }
        if (customKeysCsv != null && customKeysCsv.length() > 0) {
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
        fixHyphenDotsConflict();
        ensureEmojiToggleGesture();
        rebuildDotsLookupMap();
    }

    private void savePhoneOptions() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("use_per_dot_gestures", usePerDotGestures);
        editor.putBoolean("slate_and_stylus", slateAndStylusMode);
        editor.putBoolean("invert_180", invertKeyboard180);
        editor.putBoolean("auto_capitalize", autoCapitalize);
        editor.putBoolean("double_space_period", doubleSpacePeriod);
        editor.putBoolean("speak_password_chars", speakPasswordChars);
        editor.putBoolean("privacy_screen", privacyScreenActive);
        editor.putInt("feedback_mode", feedbackMode);
        editor.putInt("letter_delay_ms", (int) letterDelayMs);
        editor.putInt("gesture_delay_ms", (int) gestureDelayMs);
        editor.putInt(BrailleSpeedSettings.KEY_SWIPE_DISTANCE, swipeDistanceDp);
        editor.putInt(BrailleSpeedSettings.KEY_LONG_SWIPE_DISTANCE, longSwipeDistanceDp);
        editor.putBoolean(BrailleSpeedSettings.KEY_DISTINGUISH_SWIPE_LENGTH, distinguishSwipeLength);
        if (calibrationDirty) {
            editor.putBoolean("use_calibrated_points", useCalibratedPoints);
            for (int i = 1; i <= 6; i++) {
                editor.putFloat("cal_x_" + i, calDotX[i]);
                editor.putFloat("cal_y_" + i, calDotY[i]);
            }
            calibrationDirty = false;
        }
editor.putBoolean("use_custom_sound", useCustomSound);
editor.putString("custom_sound_uri", customSoundUri);
editor.putString("custom_sound_capital_uri", capitalSlot.uri);
editor.putString("custom_sound_space_uri", spaceSlot.uri);
editor.apply();
}

    private void saveGestureSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : gestureNameMap.keySet()) {
            String action = gestureActionMap.get(key);
            if (action == null) editor.remove("ges_" + key);
            else editor.putString("ges_" + key, action);
        }
        editor.apply();
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

    private String describeEmoji(String emoji) {
        if ("\uD83D\uDE00".equals(emoji)) return "mặt cười";
        if ("\uD83D\uDE02".equals(emoji)) return "cười ra nước mắt";
        if ("\uD83D\uDE22".equals(emoji)) return "mặt khóc";
        if ("\uD83D\uDE21".equals(emoji)) return "mặt giận dữ";
        if ("\uD83D\uDC4D".equals(emoji)) return "giơ ngón cái, thích";
        if ("\u2764\uFE0F".equals(emoji) || "\u2764".equals(emoji)) return "trái tim";
        return emoji;
    }

    private String emojiLabel(String emoji) {
        String name = describeEmoji(emoji);
        return name.equals(emoji) ? emoji : (name + " " + emoji);
    }

    private void loadEmojiSettings() {
        resetDefaultEmoji();
        String emojiKeysCsv = prefs.getString("emoji_keys", null);
        if (emojiKeysCsv == null) return;
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

    private void saveEmojiSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        StringBuilder sbKeys = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : emojiToDotsMap.entrySet()) {
            if (!first) sbKeys.append("\u001F");
            first = false;
            sbKeys.append(entry.getKey());
            editor.putString("emoji_dots_" + entry.getKey(), entry.getValue());
        }
        editor.putString("emoji_keys", sbKeys.toString());
        editor.apply();
    }

    private String findEmojiDotsConflict(String normalizedDots, String exceptKey) {
        for (Map.Entry<String, String> other : emojiToDotsMap.entrySet()) {
            if (!other.getKey().equals(exceptKey) && normalizedDots.equals(normalizeDotsString(other.getValue()))) {
                return other.getKey();
            }
        }
        return null;
    }

    private void ensureEmojiToggleGesture() {
        if (gestureActionMap.containsValue(ACT_TOGGLE_EMOJI)) return;
        if (ACT_SPEAK_STATS.equals(gestureActionMap.get(GES_DOT3_RIGHT))) {
            gestureActionMap.put(GES_DOT3_RIGHT, ACT_TOGGLE_EMOJI);
        }
    }

    private LinearLayout buildSection7Emoji() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView tvHint = new TextView(this);
        tvHint.setText("Bảng biểu tượng cảm xúc hoạt động độc lập với bảng chữ cái: cùng một số chấm có thể là chữ ở bảng chữ cái và là biểu tượng ở bảng này. Khi gõ, dùng cử chỉ Chuyển bảng chữ cái / bảng biểu tượng cảm xúc (mặc định: Chấm 3 vuốt xuống) để chuyển qua lại. Bấm vào biểu tượng để sửa số chấm, nhấn giữ để xóa:");
        tvHint.setTextColor(Color.LTGRAY);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvHint.setPadding(0, 0, 0, dp(4));
        layout.addView(tvHint);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnAddEmoji = new Button(this);
        btnAddEmoji.setText("+ Thêm biểu tượng mới");
        btnAddEmoji.setAllCaps(false);
        btnAddEmoji.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddEmojiDialog();
            }
        });

        Button btnResetEmoji = new Button(this);
        btnResetEmoji.setText("Khôi phục mặc định");
        btnResetEmoji.setAllCaps(false);
        btnResetEmoji.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetDefaultEmoji();
                saveEmojiSettings();
                refreshEmojiListView();
                speak("Đã khôi phục bảng biểu tượng cảm xúc mặc định", true);
            }
        });

        btnRow.addView(btnAddEmoji, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(btnResetEmoji, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        layout.addView(btnRow);

        Button btnExportEmojiFile = new Button(this);
        btnExportEmojiFile.setText("Xuất ra tệp (lưu vào Download)");
        btnExportEmojiFile.setAllCaps(false);
        btnExportEmojiFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportEmojiToFile();
            }
        });

        Button btnImportEmojiFile = new Button(this);
        btnImportEmojiFile.setText("Nhập từ tệp");
        btnImportEmojiFile.setAllCaps(false);
        btnImportEmojiFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importEmojiFromFile();
            }
        });

        layout.addView(btnExportEmojiFile, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(btnImportEmojiFile, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        lvEmoji = new ListView(this);
        emojiListAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                emojiDisplayList
        ) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = (TextView) v.findViewById(android.R.id.text1);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                return v;
            }
        };
        lvEmoji.setAdapter(emojiListAdapter);

        lvEmoji.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < emojiKeysList.size()) {
                    showEditEmojiDotsDialog(emojiKeysList.get(position));
                }
            }
        });

        lvEmoji.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < emojiKeysList.size()) {
                    showDeleteEmojiDialog(emojiKeysList.get(position));
                }
                return true;
            }
        });

        layout.addView(lvEmoji, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        refreshEmojiListView();
        return layout;
    }

    private void refreshEmojiListView() {
        emojiKeysList.clear();
        emojiDisplayList.clear();
        for (Map.Entry<String, String> entry : emojiToDotsMap.entrySet()) {
            emojiKeysList.add(entry.getKey());
            emojiDisplayList.add("Biểu tượng [" + emojiLabel(entry.getKey()) + "]   ➔   Chấm: " + entry.getValue());
        }
        if (emojiListAdapter != null) {
            emojiListAdapter.notifyDataSetChanged();
        }
    }

    private void showAddEmojiDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));

        final EditText edtEmoji = new EditText(this);
        edtEmoji.setHint("Nhập biểu tượng cảm xúc (ví dụ: \uD83D\uDE0E)");
        box.addView(edtEmoji);

        final EditText edtDots = new EditText(this);
        edtDots.setHint("Nhập số chấm từ 1 đến 6 (ví dụ: 12)");
        edtDots.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(edtDots);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Thêm biểu tượng mới");
        builder.setView(box);
        builder.setPositiveButton("Lưu", null);
        builder.setNegativeButton("Hủy", null);
        final AlertDialog addDialog = builder.create();
        addDialog.show();
        addDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String emoji = edtEmoji.getText().toString().trim();
                String dots = normalizeDotsString(edtDots.getText().toString().trim());
                if (emoji.length() == 0 || dots.length() == 0) {
                    Toast.makeText(MainActivity.this, "Vui lòng nhập đầy đủ biểu tượng và số chấm!", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (emoji.length() > 16) {
                    Toast.makeText(MainActivity.this, "Biểu tượng quá dài, chỉ nhập một biểu tượng!", Toast.LENGTH_SHORT).show();
                    return;
                }
                String conflictKey = findEmojiDotsConflict(dots, emoji);
                if (conflictKey != null) {
                    String errorMessage = "Không thể thêm '" + describeEmoji(emoji) + "': tổ hợp chấm " + dots
                            + " đã được dùng cho '" + describeEmoji(conflictKey) + "'. Hãy nhập tổ hợp khác.";
                    speak(errorMessage, true);
                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                    return;
                }
                emojiToDotsMap.put(emoji, dots);
                saveEmojiSettings();
                refreshEmojiListView();
                addDialog.dismiss();
                speak("Đã thêm " + describeEmoji(emoji) + " với chấm " + dots, true);
            }
        });
    }

    private void showEditEmojiDotsDialog(final String emojiKey) {
        String currentDots = emojiToDotsMap.get(emojiKey);
        final String emojiName = describeEmoji(emojiKey);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Sửa số chấm cho: " + emojiLabel(emojiKey));
        builder.setMessage("Bạn muốn nhập số chấm nào cho '" + emojiName + "'? (Chỉ nhập các số từ 1 đến 6, ví dụ: 12)");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(currentDots != null ? currentDots : "");
        input.setSelection(input.getText().length());
        builder.setView(input);

        builder.setPositiveButton("Lưu", null);
        builder.setNegativeButton("Hủy", null);
        final AlertDialog editDialog = builder.create();
        editDialog.show();
        editDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String normalized = normalizeDotsString(input.getText().toString().trim());
                if (normalized.length() == 0) {
                    speak("Số chấm không hợp lệ, hãy nhập từ 1 đến 6", true);
                    Toast.makeText(MainActivity.this, "Chỉ nhập các số từ 1 đến 6!", Toast.LENGTH_SHORT).show();
                    return;
                }
                String conflictKey = findEmojiDotsConflict(normalized, emojiKey);
                if (conflictKey != null) {
                    String errorMessage = "Không thể dùng: tổ hợp chấm " + normalized
                            + " đã được dùng cho '" + describeEmoji(conflictKey) + "'. Hãy nhập tổ hợp khác.";
                    speak(errorMessage, true);
                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                    return;
                }
                emojiToDotsMap.put(emojiKey, normalized);
                saveEmojiSettings();
                refreshEmojiListView();
                editDialog.dismiss();
                speak("Đã lưu " + emojiName + " là chấm " + normalized, true);
            }
        });
    }

    private void showDeleteEmojiDialog(final String emojiKey) {
        String dots = emojiToDotsMap.get(emojiKey);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Xóa biểu tượng: " + emojiLabel(emojiKey));
        builder.setMessage("Bạn có chắc muốn xóa '" + describeEmoji(emojiKey) + "' (chấm " + (dots != null ? dots : "") + ") khỏi bảng biểu tượng không?");
        builder.setPositiveButton("Xóa", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                emojiToDotsMap.remove(emojiKey);
                saveEmojiSettings();
                refreshEmojiListView();
                speak("Đã xóa " + describeEmoji(emojiKey) + " khỏi bảng biểu tượng", true);
            }
        });
        builder.setNegativeButton("Hủy", null);
        builder.show();
    }

    private String buildEmojiExportText() {
        StringBuilder sb = new StringBuilder();
        sb.append(EMOJI_EXPORT_HEADER).append("\n");
        for (Map.Entry<String, String> entry : emojiToDotsMap.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.length() == 0 || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0 || key.indexOf('=') >= 0) continue;
            String dots = normalizeDotsString(entry.getValue());
            if (dots.length() == 0) continue;
            sb.append(key).append("=").append(dots).append("\n");
        }
        return sb.toString();
    }

    private LinkedHashMap<String, String> parseEmojiExportText(String text) {
        if (text == null) return null;
        String[] lines = text.split("\\r?\\n");
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        boolean headerFound = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            if (!headerFound) {
                if (line.equals(EMOJI_EXPORT_HEADER)) {
                    headerFound = true;
                }
                continue;
            }
            int eq = line.lastIndexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq);
            String dots = normalizeDotsString(line.substring(eq + 1));
            if (key.length() == 0 || dots.length() == 0) continue;
            result.put(key, dots);
        }
        if (!headerFound || result.isEmpty()) return null;
        return result;
    }

    private void exportEmojiToFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "BangBieuTuongBraille.txt");
            startActivityForResult(intent, REQUEST_EXPORT_EMOJI_FILE);
            speak("Hãy chọn thư mục Download hoặc nơi muốn lưu tệp, rồi bấm Lưu", true);
        } catch (Throwable t) {
            speak("Không mở được hộp thoại lưu tệp", true);
            Toast.makeText(this, "Không mở được hộp thoại lưu tệp", Toast.LENGTH_LONG).show();
        }
    }

    private void writeEmojiToUri(Uri uri) {
        java.io.OutputStream out = null;
        try {
            out = getContentResolver().openOutputStream(uri);
            if (out == null) {
                throw new java.io.IOException("Không mở được luồng ghi");
            }
            out.write(buildEmojiExportText().getBytes("UTF-8"));
            out.flush();
            speak("Đã lưu tệp bảng biểu tượng cảm xúc. Bạn có thể mở thư mục chứa tệp để chia sẻ.", true);
            Toast.makeText(this, "Đã lưu tệp bảng biểu tượng cảm xúc", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            speak("Không lưu được tệp", true);
            Toast.makeText(this, "Không lưu được tệp", Toast.LENGTH_LONG).show();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void importEmojiFromFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_IMPORT_EMOJI_FILE);
            speak("Hãy chọn tệp bảng biểu tượng cảm xúc đã lưu", true);
        } catch (Throwable t) {
            speak("Không mở được hộp thoại chọn tệp", true);
            Toast.makeText(this, "Không mở được hộp thoại chọn tệp", Toast.LENGTH_LONG).show();
        }
    }

    private void handleImportedEmojiFile(Uri uri) {
        String text = readTextFromUri(uri);
        if (text == null) {
            speak("Không đọc được tệp", true);
            Toast.makeText(this, "Không đọc được tệp", Toast.LENGTH_LONG).show();
            return;
        }
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        final LinkedHashMap<String, String> parsed = parseEmojiExportText(text);
        if (parsed == null) {
            speak("Tệp không đúng định dạng bảng biểu tượng cảm xúc", true);
            Toast.makeText(this, "Tệp không đúng định dạng bảng biểu tượng cảm xúc!", Toast.LENGTH_LONG).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Nhập bảng biểu tượng từ tệp");
        builder.setMessage("Tệp có " + parsed.size() + " biểu tượng. Chọn Gộp để giữ các biểu tượng hiện có, hoặc Thay thế toàn bộ để dùng đúng bảng trong tệp. Biểu tượng nào trùng tổ hợp chấm sẽ bị bỏ qua.");
        builder.setPositiveButton("Gộp", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                applyImportedEmoji(parsed, false);
            }
        });
        builder.setNeutralButton("Thay thế toàn bộ", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                applyImportedEmoji(parsed, true);
            }
        });
        builder.setNegativeButton("Hủy", null);
        builder.show();
    }

    private void applyImportedEmoji(LinkedHashMap<String, String> imported, boolean replaceAll) {
        if (imported == null || imported.isEmpty()) {
            speak("Dữ liệu bảng biểu tượng không hợp lệ", true);
            Toast.makeText(this, "Dữ liệu không đúng định dạng bảng biểu tượng!", Toast.LENGTH_LONG).show();
            return;
        }
        if (replaceAll) {
            emojiToDotsMap.clear();
        }
        int added = 0;
        int skipped = 0;
        for (Map.Entry<String, String> entry : imported.entrySet()) {
            if (findEmojiDotsConflict(entry.getValue(), entry.getKey()) != null) {
                skipped++;
                continue;
            }
            emojiToDotsMap.put(entry.getKey(), entry.getValue());
            added++;
        }
        saveEmojiSettings();
        refreshEmojiListView();
        String message = (replaceAll ? "Đã thay thế bảng biểu tượng bằng " : "Đã gộp thêm ") + added + " biểu tượng";
        if (skipped > 0) {
            message = message + ". Bỏ qua " + skipped + " biểu tượng vì trùng tổ hợp chấm";
        }
        speak(message, true);
    }

    private void saveAlphabetSettings() {

        SharedPreferences.Editor editor = prefs.edit();
        StringBuilder sbKeys = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : charToDotsMap.entrySet()) {
            if (!first) sbKeys.append("\u001F");
            first = false;
            sbKeys.append(entry.getKey());
            editor.putString("char_dots_" + entry.getKey(), entry.getValue());
        }
        editor.putString("custom_char_keys", sbKeys.toString());
        editor.apply();
        rebuildDotsLookupMap();
    }

    private View buildRootInterface() {
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Color.parseColor("#121212"));
        rootFrame.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        mainScreenContainer = buildMainScreen();
        fullScreenKeyboardContainer = buildFullScreenKeyboard();
        fullScreenKeyboardContainer.setVisibility(View.GONE);

        rootFrame.addView(mainScreenContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootFrame.addView(fullScreenKeyboardContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        return rootFrame;
    }

    private LinearLayout buildMainScreen() {
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        final int basePadding = dp(10);
        BrailleSettingsMenu.applySafePadding(main, basePadding, 0, 0);
        main.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View view, android.view.WindowInsets insets) {
                int topInset;
                int bottomInset;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                    topInset = bars.top;
                    bottomInset = bars.bottom;
                } else {
                    topInset = insets.getSystemWindowInsetTop();
                    bottomInset = insets.getSystemWindowInsetBottom();
                }
                BrailleSettingsMenu.applySafePadding(view, basePadding, topInset, bottomInset);
                return insets;
            }
        });

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.VERTICAL);
        tabRow.setPadding(0, 0, 0, dp(6));

        btnTabGesture = createTabButton("Cài đặt cử chỉ");
btnTabAlphabet = createTabButton("Cài đặt bảng chữ");
btnTabPhoneOptions = createTabButton("Cài đặt bàn phím");
btnTabSound = createTabButton("Âm thanh và Rung");
        btnTabEmoji = createTabButton("Cài đặt biểu tượng");


        btnTabGesture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchSettingsTab(1);
                speak("Cài đặt cử chỉ. Bấm vào từng cử chỉ để chọn chức năng. Bấm nút quay lại để trở về menu.", true);
            }
        });
        btnTabAlphabet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchSettingsTab(2);
                speak("Cài đặt bảng chữ cái chữ nổi tiếng Việt. Bấm vào chữ để sửa số chấm, nhấn giữ để xóa. Bấm nút quay lại để trở về menu.", true);
            }
        });
        btnTabPhoneOptions.setOnClickListener(new View.OnClickListener() {
@Override
public void onClick(View v) {
switchSettingsTab(3);
speak("Cài đặt bàn phím và nhập liệu. Chọn Tăng tốc khi nhập để điều chỉnh tốc độ và cử chỉ. Bấm nút quay lại để trở về menu.", true);
}
});
btnTabSound.setOnClickListener(new View.OnClickListener() {
@Override
public void onClick(View v) {
switchSettingsTab(4);
speak("Âm thanh và Rung. Gồm hai mục: Thiết lập âm thanh và Chọn mức độ rung. Bấm nút quay lại để trở về menu.", true);
}
});

        tabRow.addView(btnTabPhoneOptions, new LinearLayout.LayoutParams(
ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
tabRow.addView(btnTabSound, new LinearLayout.LayoutParams(
ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
btnTabEmoji.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchSettingsTab(6);
                speak("Cài đặt biểu tượng cảm xúc. Bấm vào biểu tượng để sửa số chấm, nhấn giữ để xóa. Bấm nút quay lại để trở về menu.", true);
            }
        });
tabRow.addView(btnTabEmoji, new LinearLayout.LayoutParams(
ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
tabRow.addView(btnTabGesture, new LinearLayout.LayoutParams(
ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
tabRow.addView(btnTabAlphabet, new LinearLayout.LayoutParams(
ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView menuScroll = BrailleSettingsMenu.wrap(this, tabRow);
        main.addView(menuScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));


        sectionGestureLayout = buildSection2Gestures();
        sectionAlphabetLayout = buildSection3Alphabet();
        sectionPhoneOptionsLayout = buildSection4PhoneOptions();
        sectionSoundLayout = buildSection5Sound();
        sectionSoundMenuLayout = buildSection4SoundMenu();
        sectionVibrationLayout = buildSection8Vibration();
        sectionEmojiLayout = buildSection7Emoji();

        tabContentHolder = new FrameLayout(this);
        main.addView(tabContentHolder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabContentHolder.addView(sectionGestureLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabContentHolder.addView(sectionAlphabetLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabContentHolder.addView(sectionPhoneOptionsLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabContentHolder.addView(sectionSoundLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabContentHolder.addView(sectionSoundMenuLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabContentHolder.addView(sectionVibrationLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabContentHolder.addView(sectionEmojiLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));


        return main;
    }

    private Button createTabButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return b;
    }

    private FrameLayout buildFullScreenKeyboard() {
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.BLACK);

        brailleCanvasView = new BraillePhoneCanvasView(this);
        brailleCanvasView.setContentDescription("Bàn phím chữ nổi đã sẵn sàng. Đầu loa trên là chấm 1, 2, 3. Cổng sạc dưới là chấm 4, 5, 6.");

        container.addView(brailleCanvasView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        return container;
    }

    private ScrollView buildSection4SoundMenu() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(6), dp(6), dp(6), dp(16));

        TextView tvHint = new TextView(this);
        tvHint.setText("Chọn một mục để thiết lập:");
        tvHint.setTextColor(Color.LTGRAY);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvHint.setPadding(0, 0, 0, dp(8));
        container.addView(tvHint);

        Button btnOpenSound = new Button(this);
        btnOpenSound.setAllCaps(false);
        btnOpenSound.setText("Thiết lập âm thanh");
        btnOpenSound.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchSettingsTab(7);
                speak("Thiết lập âm thanh. Chọn tệp âm thanh từ quản lý tệp tin để phát khi nhập liệu, hoặc dùng âm thanh hệ thống mặc định. Bấm nút quay lại để trở về menu Âm thanh và Rung.", true);
            }
        });
        container.addView(btnOpenSound);

        Button btnOpenVibration = new Button(this);
        btnOpenVibration.setAllCaps(false);
        btnOpenVibration.setText("Chọn mức độ rung");
        btnOpenVibration.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchSettingsTab(8);
                speak("Chọn mức độ rung: nhẹ, trung bình hoặc mạnh. Bấm nút quay lại để trở về menu Âm thanh và Rung.", true);
            }
        });
        container.addView(btnOpenVibration);

        scroll.addView(container);
        return scroll;
    }

    private ScrollView buildSection8Vibration() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(6), dp(6), dp(6), dp(16));

        TextView tvHint = new TextView(this);
        tvHint.setText("Chọn mức độ rung khi gõ. Mức rung chỉ có tác dụng khi chế độ phản hồi bàn phím đang bật rung.");
        tvHint.setTextColor(Color.LTGRAY);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvHint.setPadding(0, 0, 0, dp(8));
        container.addView(tvHint);

        rgVibrationLevel = new android.widget.RadioGroup(this);
        String[] levelLabels = new String[]{"Mức độ rung nhẹ", "Mức độ rung trung bình", "Mức độ rung mạnh"};
        for (int i = 0; i < levelLabels.length; i++) {
            android.widget.RadioButton radio = new android.widget.RadioButton(this);
            radio.setId(7101 + i);
            radio.setText(levelLabels[i]);
            radio.setTextColor(Color.WHITE);
            radio.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            radio.setPadding(dp(8), dp(12), dp(8), dp(12));
            rgVibrationLevel.addView(radio);
        }
        rgVibrationLevel.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.RadioGroup group, int checkedId) {
                int level = checkedId - 7101;
                if (level < 0 || level > 2 || level == vibrationLevel) return;
                vibrationLevel = level;
                prefs.edit().putInt("vibration_level", vibrationLevel).apply();
                vibrateForLevel(group, 60L);
                speak("Đã chọn " + vibrationLevelName(vibrationLevel), true);
            }
        });
        container.addView(rgVibrationLevel);

        Button btnTestVibration = new Button(this);
        btnTestVibration.setAllCaps(false);
        btnTestVibration.setText("Thử rung");
        btnTestVibration.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                vibrateForLevel(v, 60L);
                if (feedbackMode == FEEDBACK_SOUND_ONLY) {
                    speak("Lưu ý: chế độ phản hồi bàn phím hiện chỉ dùng âm thanh nên khi gõ sẽ không rung.", true);
                } else {
                    speak(vibrationLevelName(vibrationLevel), true);
                }
            }
        });
        container.addView(btnTestVibration);

        refreshVibrationSection();

        scroll.addView(container);
        return scroll;
    }

    private String vibrationLevelName(int level) {
        if (level == 0) return "mức độ rung nhẹ";
        if (level == 2) return "mức độ rung mạnh";
        return "mức độ rung trung bình";
    }

    private void refreshVibrationSection() {
        vibrationLevel = prefs.getInt("vibration_level", 1);
        if (rgVibrationLevel != null) {
            rgVibrationLevel.check(7101 + vibrationLevel);
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

    private ScrollView buildSection5Sound() {

        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(6), dp(6), dp(6), dp(16));

        TextView tvHint = new TextView(this);
        tvHint.setText("Chọn một tệp âm thanh từ quản lý tệp tin để phát mỗi khi gõ. Nếu không chọn, ứng dụng dùng âm thanh hệ thống mặc định.");
        tvHint.setTextColor(Color.LTGRAY);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvHint.setPadding(0, 0, 0, dp(8));
        container.addView(tvHint);

        tvCustomSoundName = new TextView(this);
        tvCustomSoundName.setTextColor(Color.WHITE);
        tvCustomSoundName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvCustomSoundName.setPadding(0, 0, 0, dp(8));
        container.addView(tvCustomSoundName);
        updateCustomSoundLabel();

        Button btnPickSound = new Button(this);
        btnPickSound.setAllCaps(false);
        btnPickSound.setText("Chọn tệp âm thanh từ quản lý tệp tin");
        btnPickSound.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickCustomSound();
            }
        });
        container.addView(btnPickSound);

        Button btnPreviewSound = new Button(this);
        btnPreviewSound.setAllCaps(false);
        btnPreviewSound.setText("Nghe thử âm thanh khi gõ");
        btnPreviewSound.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                previewTypingSound();
            }
        });
        container.addView(btnPreviewSound);

        Button btnUseSystemSound = new Button(this);
        btnUseSystemSound.setAllCaps(false);
        btnUseSystemSound.setText("Dùng lại âm thanh hệ thống mặc định");
        btnUseSystemSound.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                useSystemSoundAgain();
            }
        });
        container.addView(btnUseSystemSound);

        addSoundSlotControls(container, SOUND_SLOT_CAPITAL, "Âm thanh khi viết hoa");
        addSoundSlotControls(container, SOUND_SLOT_SPACE, "Âm thanh khi viết dấu cách");

        scroll.addView(container);
        return scroll;
    }

    private String copySoundToPrivateStorage(Uri uri, String slotKey) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            File dir = new File(getFilesDir(), "sounds_" + slotKey);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File[] oldFiles = dir.listFiles();
            if (oldFiles != null) {
                for (int i = 0; i < oldFiles.length; i++) {
                    oldFiles[i].delete();
                }
            }
            String name = getSoundDisplayName(uri);
            if (name == null || name.length() == 0) {
                name = "sound";
            }
            name = name.replace("/", "_");
            File target = new File(dir, name);
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            out = new FileOutputStream(target);
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return Uri.fromFile(target).toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void migrateSoundsToPrivateStorage() {
        if (customSoundUri != null && customSoundUri.startsWith("content:")) {
            String copied = copySoundToPrivateStorage(Uri.parse(customSoundUri), "typing");
            if (copied != null) {
                customSoundUri = copied;
                prefs.edit().putString("custom_sound_uri", copied).apply();
            }
        }
        if (capitalSlot.uri != null && capitalSlot.uri.startsWith("content:")) {
            String copied = copySoundToPrivateStorage(Uri.parse(capitalSlot.uri), "capital");
            if (copied != null) {
                capitalSlot.uri = copied;
                prefs.edit().putString("custom_sound_capital_uri", copied).apply();
            }
        }
        if (spaceSlot.uri != null && spaceSlot.uri.startsWith("content:")) {
            String copied = copySoundToPrivateStorage(Uri.parse(spaceSlot.uri), "space");
            if (copied != null) {
                spaceSlot.uri = copied;
                prefs.edit().putString("custom_sound_space_uri", copied).apply();
            }
        }
    }

    private SoundSlot slotFor(int soundSlot) {

        return soundSlot == SOUND_SLOT_CAPITAL ? capitalSlot : spaceSlot;
    }

    private String slotLabelName(int soundSlot) {
        return soundSlot == SOUND_SLOT_CAPITAL ? "viết hoa" : "dấu cách";
    }

    private void addSoundSlotControls(LinearLayout container, final int soundSlot, String title) {
        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.parseColor("#81C784"));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvTitle.setPadding(0, dp(14), 0, dp(4));
        container.addView(tvTitle);

        TextView tvName = new TextView(this);
        tvName.setTextColor(Color.WHITE);
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvName.setPadding(0, 0, 0, dp(8));
        container.addView(tvName);
        if (soundSlot == SOUND_SLOT_CAPITAL) {
            tvCapitalSoundName = tvName;
        } else {
            tvSpaceSoundName = tvName;
        }
        updateSlotLabel(soundSlot);

        Button btnPick = new Button(this);
        btnPick.setAllCaps(false);
        btnPick.setText("Chọn tệp âm thanh cho " + slotLabelName(soundSlot));
        btnPick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickSoundForSlot(soundSlot == SOUND_SLOT_CAPITAL ? REQUEST_PICK_SOUND_CAPITAL : REQUEST_PICK_SOUND_SPACE);
            }
        });
        container.addView(btnPick);

        Button btnPreview = new Button(this);
        btnPreview.setAllCaps(false);
        btnPreview.setText("Nghe thử âm thanh " + slotLabelName(soundSlot));
        btnPreview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                previewSlotSound(soundSlot);
            }
        });
        container.addView(btnPreview);

        Button btnReset = new Button(this);
        btnReset.setAllCaps(false);
        btnReset.setText("Dùng lại âm thanh hệ thống cho " + slotLabelName(soundSlot));
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetSlotSound(soundSlot);
            }
        });
        container.addView(btnReset);
    }

    private void updateSlotLabel(int soundSlot) {
        TextView target = soundSlot == SOUND_SLOT_CAPITAL ? tvCapitalSoundName : tvSpaceSoundName;
        if (target == null) return;
        SoundSlot slot = slotFor(soundSlot);
        if (slot.uri != null && slot.uri.length() > 0) {
            target.setText("Đang dùng âm thanh tùy chỉnh: " + getSoundDisplayName(Uri.parse(slot.uri)));
        } else {
            target.setText("Đang dùng âm thanh hệ thống mặc định");
        }
    }

    private void pickSoundForSlot(int requestCode) {
        try {
            Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            startActivityForResult(intent, requestCode);
        } catch (Throwable t) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setType("audio/*");
                startActivityForResult(fallback, requestCode);
            } catch (Throwable t2) {
                Toast.makeText(this, "Không mở được quản lý tệp tin", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void handleSlotPicked(int soundSlot, int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            speak("Đã hủy chọn âm thanh", true);
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        SoundSlot slot = slotFor(soundSlot);
        releaseSlot(slot);
        String storedSlot = copySoundToPrivateStorage(uri, soundSlot == SOUND_SLOT_CAPITAL ? "capital" : "space");
        slot.uri = storedSlot != null ? storedSlot : uri.toString();
        slot.failedNotified = false;
        savePhoneOptions();
        updateSlotLabel(soundSlot);
        playSlot(slot);
        speak("Đã chọn âm thanh " + slotLabelName(soundSlot) + ": " + getSoundDisplayName(uri), false);
    }

    private void previewSlotSound(int soundSlot) {
        if (!playSlot(slotFor(soundSlot)) && toneGenerator != null) {
            try {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 120);
            } catch (Throwable ignored) {
            }
        }
    }

    private void resetSlotSound(int soundSlot) {
        SoundSlot slot = slotFor(soundSlot);
        releaseSlot(slot);
        slot.uri = "";
        savePhoneOptions();
        updateSlotLabel(soundSlot);
        speak("Đã dùng lại âm thanh hệ thống mặc định cho " + slotLabelName(soundSlot), true);
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
            if (!slot.failedNotified) {
                slot.failedNotified = true;
                Toast.makeText(this, "Không phát được âm thanh tùy chỉnh: " + t.getClass().getSimpleName()
                        + " " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
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

    private void updateCustomSoundLabel() {

        if (tvCustomSoundName == null) return;
        if (useCustomSound && customSoundUri != null && customSoundUri.length() > 0) {
            tvCustomSoundName.setText("Đang dùng âm thanh tùy chỉnh: " + getSoundDisplayName(Uri.parse(customSoundUri)));
        } else {
            tvCustomSoundName.setText("Đang dùng âm thanh hệ thống mặc định");
        }
    }

    private String getSoundDisplayName(Uri uri) {
        String name = null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    name = cursor.getString(index);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (name == null || name.length() == 0) {
            name = uri.getLastPathSegment();
        }
        return name != null ? name : "tệp âm thanh";
    }

    private void pickCustomSound() {
        try {
            Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            startActivityForResult(intent, REQUEST_PICK_SOUND);
        } catch (Throwable t) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setType("audio/*");
                startActivityForResult(fallback, REQUEST_PICK_SOUND);
            } catch (Throwable t2) {
                Toast.makeText(this, "Không mở được quản lý tệp tin", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_ALPHABET_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                handleImportedAlphabetFile(data.getData());
            } else {
                speak("Đã hủy chọn tệp", true);
            }
            return;
        }
        if (requestCode == REQUEST_EXPORT_ALPHABET_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                writeAlphabetToUri(data.getData());
            } else {
                speak("Đã hủy lưu tệp", true);
            }
            return;
        }
        if (requestCode == REQUEST_IMPORT_EMOJI_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                handleImportedEmojiFile(data.getData());
            } else {
                speak("Đã hủy chọn tệp", true);
            }
            return;
        }
        if (requestCode == REQUEST_EXPORT_EMOJI_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                writeEmojiToUri(data.getData());
            } else {
                speak("Đã hủy lưu tệp", true);
            }
            return;
        }
        if (requestCode == REQUEST_PICK_SOUND_CAPITAL) {
            handleSlotPicked(SOUND_SLOT_CAPITAL, resultCode, data);
            return;
        }
        if (requestCode == REQUEST_PICK_SOUND_SPACE) {
            handleSlotPicked(SOUND_SLOT_SPACE, resultCode, data);
            return;
        }
        if (requestCode != REQUEST_PICK_SOUND) return;

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            speak("Đã hủy chọn âm thanh", true);
            return;
        }

        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }

        String storedTyping = copySoundToPrivateStorage(uri, "typing");
        customSoundUri = storedTyping != null ? storedTyping : uri.toString();
        customSoundFailedNotified = false;
        useCustomSound = true;
        releaseCustomSoundPlayer();
        savePhoneOptions();
        updateCustomSoundLabel();
        playCustomSound();
        speak("Đã chọn âm thanh " + getSoundDisplayName(uri), false);
    }

    private void useSystemSoundAgain() {
        useCustomSound = false;
        customSoundUri = "";
        releaseCustomSoundPlayer();
        savePhoneOptions();
        updateCustomSoundLabel();
        speak("Đã dùng lại âm thanh hệ thống mặc định", true);
    }

    private void previewTypingSound() {
        if (useCustomSound && customSoundUri != null && customSoundUri.length() > 0) {
            playCustomSound();
        } else if (toneGenerator != null) {
            try {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
            } catch (Throwable ignored) {
            }
        }
    }

    private void playCustomSound() {
        if (customSoundUri == null || customSoundUri.length() == 0) return;
        MediaPlayer newPlayer = null;
        try {
            if (customSoundPlayer == null) {
                newPlayer = new MediaPlayer();
                newPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                newPlayer.setDataSource(this, Uri.parse(customSoundUri));
                newPlayer.prepare();
                customSoundPlayer = newPlayer;
            }
            if (customSoundPlayer.isPlaying()) {
                customSoundPlayer.seekTo(0);
            } else {
                customSoundPlayer.start();
            }
        } catch (Throwable t) {
            if (newPlayer != null && newPlayer != customSoundPlayer) {
                try {
                    newPlayer.release();
                } catch (Throwable ignored) {
                }
            }
            releaseCustomSoundPlayer();
            if (!customSoundFailedNotified) {
                customSoundFailedNotified = true;
                Toast.makeText(this, "Không phát được âm thanh tùy chỉnh: " + t.getClass().getSimpleName()
                        + " " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
            if (toneGenerator != null) {
                try {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 40);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void releaseCustomSoundPlayer() {
        if (customSoundPlayer != null) {
            try {
                customSoundPlayer.release();
            } catch (Throwable ignored) {
            }
            customSoundPlayer = null;
        }
    }

    private void switchSettingsTab(int index) {

currentSettingsTab = index;
boolean sectionOpen = index >= 1 && index <= 8;
if (tabContentHolder != null) {
tabContentHolder.setVisibility(sectionOpen ? View.VISIBLE : View.GONE);
}
if (mainScreenContainer != null) {
for (int i = 0; i < mainScreenContainer.getChildCount(); i++) {
View child = mainScreenContainer.getChildAt(i);
if (child != tabContentHolder) {
child.setVisibility(sectionOpen ? View.GONE : View.VISIBLE);
}
}
}
sectionGestureLayout.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
sectionAlphabetLayout.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
sectionPhoneOptionsLayout.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
sectionSoundMenuLayout.setVisibility(index == 4 ? View.VISIBLE : View.GONE);
sectionSoundLayout.setVisibility(index == 7 ? View.VISIBLE : View.GONE);
sectionVibrationLayout.setVisibility(index == 8 ? View.VISIBLE : View.GONE);
sectionEmojiLayout.setVisibility(index == 6 ? View.VISIBLE : View.GONE);


        btnTabGesture.setBackgroundColor(index == 1 ? Color.parseColor("#2E7D32") : Color.parseColor("#333333"));
        btnTabAlphabet.setBackgroundColor(index == 2 ? Color.parseColor("#2E7D32") : Color.parseColor("#333333"));
        btnTabPhoneOptions.setBackgroundColor(index == 3 ? Color.parseColor("#2E7D32") : Color.parseColor("#333333"));
        btnTabSound.setBackgroundColor((index == 4 || index == 7 || index == 8) ? Color.parseColor("#2E7D32") : Color.parseColor("#333333"));
        btnTabEmoji.setBackgroundColor(index == 6 ? Color.parseColor("#2E7D32") : Color.parseColor("#333333"));

        if (index == 1) refreshGestureListView();
        else if (index == 2) refreshAlphabetListView();
        else if (index == 6) refreshEmojiListView();
        else if (index == 8) refreshVibrationSection();
    }

    private void enterFullScreenKeyboardMode() {
        isKeyboardActive = true;
        clearPendingDots();

        if (autoCapitalize && textBuffer.length() == 0) {
            nextCharCapital = true;
        }

        mainScreenContainer.setVisibility(View.GONE);
        fullScreenKeyboardContainer.setVisibility(View.VISIBLE);
        brailleCanvasView.invalidate();

        triggerFeedback(brailleCanvasView, 60L, ToneGenerator.TONE_PROP_ACK, 120);
        speak("Bàn phím đã sẵn sàng. Đầu loa trên là 1, 2, 3. Cổng sạc dưới là 4, 5, 6. Vuốt chấm 4 xuống để cách, vuốt chấm 4 lên để xóa, vuốt chấm 5 sang phải hoặc bấm nút Quay lại khi viết xong.", true);
    }

    private void exitFullScreenKeyboardMode() {
        mainHandler.removeCallbacks(singleFingerCommitRunnable);
        commitPendingDotsNow();

        isKeyboardActive = false;
        fullScreenKeyboardContainer.setVisibility(View.GONE);
        mainScreenContainer.setVisibility(View.VISIBLE);

        updateVirtualInputDisplay();
        triggerFeedback(mainScreenContainer, 55L, ToneGenerator.TONE_PROP_BEEP2, 100);

        String result = textBuffer.toString().trim();
        if (result.length() > 0) {
            speak("Đã đóng bàn phím. Nội dung đã viết: " + result, true);
        } else {
            speak("Đã đóng bàn phím. Ô nhập liệu đang trống.", true);
        }
    }

    // Android 11-12 still dispatches onBackPressed(). On Android 13+ we
    // register the equivalent predictive-back callback in onCreate().
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        handleBrailleBack();
    }

    private void handleBrailleBack() {
if (isKeyboardActive) {
exitFullScreenKeyboardMode();
return;
}
if (currentSettingsTab == 7 || currentSettingsTab == 8) {
switchSettingsTab(4);
speak("Đã quay lại menu Âm thanh và Rung", true);
return;
}
if (currentSettingsTab != 0) {
switchSettingsTab(0);
speak("Đã quay lại menu chính", true);
return;
}
finish();
}


    private void updateVirtualInputDisplay() {
        if (tvVirtualInputDisplay == null) return;
        String content = textBuffer.toString();
        if (content.length() == 0) {
            tvVirtualInputDisplay.setText("Chưa có văn bản (Bấm vào đây để mở bàn phím chữ nổi)");
            tvVirtualInputDisplay.setContentDescription("Ô nhập liệu ảo đang trống. Bấm vào đây để mở bàn phím chữ nổi.");
        } else {
            int safeCursor = Math.max(0, Math.min(cursorPos, content.length()));
            String visual = content.substring(0, safeCursor) + "|" + content.substring(safeCursor);
            tvVirtualInputDisplay.setText(visual);
            tvVirtualInputDisplay.setContentDescription("Ô nhập liệu ảo. Nội dung: " + content + ". Bấm vào đây để mở bàn phím chữ nổi.");
        }
    }

    private LinearLayout buildSection2Gestures() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView tvHint = new TextView(this);
        tvHint.setText("Vuốt Sang Phải / Vuốt Sang Trái là thao tác chính. Vuốt Lên / Vuốt Xuống là tính năng phụ. Bấm vào cử chỉ để đổi:");
        tvHint.setTextColor(Color.LTGRAY);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvHint.setPadding(0, 0, 0, dp(4));
        layout.addView(tvHint);

        Button btnResetGes = new Button(this);
        btnResetGes.setText("Khôi phục bộ cử chỉ mặc định");
        btnResetGes.setAllCaps(false);
        btnResetGes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetDefaultGestures();
                saveGestureSettings();
                refreshGestureListView();
                speak("Đã khôi phục toàn bộ cử chỉ về mặc định", true);
            }
        });
        layout.addView(btnResetGes);

        lvGestures = new ListView(this);
        gestureListAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                gestureDisplayList
        ) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = (TextView) v.findViewById(android.R.id.text1);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                return v;
            }
        };
        lvGestures.setAdapter(gestureListAdapter);

        lvGestures.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < gestureKeysList.size()) {
                    showChangeGestureDialog(gestureKeysList.get(position));
                }
            }
        });

        layout.addView(lvGestures, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        refreshGestureListView();
        return layout;
    }

    private void refreshGestureListView() {
        gestureKeysList.clear();
        gestureDisplayList.clear();
        for (Map.Entry<String, String> entry : gestureNameMap.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("_LONG") && !distinguishSwipeLength) continue;
            String gesName = entry.getValue();
            String actCode = gestureActionMap.get(key);
            String actName = key.endsWith("_LONG") && actCode == null
                    ? "Giống vuốt ngắn"
                    : (actionNameMap.containsKey(actCode) ? actionNameMap.get(actCode) : actCode);
            gestureKeysList.add(key);
            gestureDisplayList.add(gesName + "  ➔  " + actName);
        }
        if (gestureListAdapter != null) {
            gestureListAdapter.notifyDataSetChanged();
        }
    }

    private void showChangeGestureDialog(final String gestureKey) {
        final String gestureTitle = gestureNameMap.get(gestureKey);
        final List<String> actionCodes = new ArrayList<String>(actionNameMap.keySet());
        if (gestureKey.endsWith("_LONG")) actionCodes.add(0, "FOLLOW_SHORT");
        final String[] actionLabels = new String[actionCodes.size()];
        int currentIdx = 0;
        String currentAction = gestureActionMap.get(gestureKey);

        for (int i = 0; i < actionCodes.size(); i++) {
            String code = actionCodes.get(i);
            actionLabels[i] = "FOLLOW_SHORT".equals(code) ? "Giống vuốt ngắn" : actionNameMap.get(code);
            if (code.equals(currentAction)) {
                currentIdx = i;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Chọn chức năng cho: " + gestureTitle);
        builder.setSingleChoiceItems(actionLabels, currentIdx, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String chosenCode = actionCodes.get(which);
                String chosenLabel = actionLabels[which];
                if ("FOLLOW_SHORT".equals(chosenCode)) gestureActionMap.remove(gestureKey);
                else gestureActionMap.put(gestureKey, chosenCode);
                saveGestureSettings();
                refreshGestureListView();
                speak("Đã gán " + gestureTitle + " thành " + chosenLabel, true);
                dialog.dismiss();
            }
        });
        builder.setNegativeButton("Hủy", null);
        builder.show();
    }

    private LinearLayout buildSection3Alphabet() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView tvHint = new TextView(this);
        tvHint.setText("Bấm vào chữ cái bất kỳ để sửa số chấm (Ví dụ: chữ i nhập 24). Nhấn giữ một chữ cái để xóa khỏi danh sách:");
        tvHint.setTextColor(Color.LTGRAY);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvHint.setPadding(0, 0, 0, dp(4));
        layout.addView(tvHint);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnAddChar = new Button(this);
        btnAddChar.setText("+ Thêm ký tự mới");
        btnAddChar.setAllCaps(false);
        btnAddChar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddCharacterDialog();
            }
        });

        Button btnResetAlpha = new Button(this);
        btnResetAlpha.setText("Khôi phục mặc định");
        btnResetAlpha.setAllCaps(false);
        btnResetAlpha.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetDefaultVietnameseBraille();
                saveAlphabetSettings();
                refreshAlphabetListView();
                speak("Đã khôi phục bảng chữ nổi tiếng Việt mặc định", true);
            }
        });

        btnRow.addView(btnAddChar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(btnResetAlpha, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        layout.addView(btnRow);

        Button btnExportAlphaFile = new Button(this);
        btnExportAlphaFile.setText("Xuất ra tệp (lưu vào Download)");
        btnExportAlphaFile.setAllCaps(false);
        btnExportAlphaFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportAlphabetToFile();
            }
        });

        Button btnImportAlphaFile = new Button(this);
        btnImportAlphaFile.setText("Nhập từ tệp");
        btnImportAlphaFile.setAllCaps(false);
        btnImportAlphaFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importAlphabetFromFile();
            }
        });

        layout.addView(btnExportAlphaFile, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(btnImportAlphaFile, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));


        lvAlphabet = new ListView(this);
        alphabetListAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                alphabetDisplayList
        ) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = (TextView) v.findViewById(android.R.id.text1);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                return v;
            }
        };
        lvAlphabet.setAdapter(alphabetListAdapter);

        lvAlphabet.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < alphabetKeysList.size()) {
                    showEditCharacterDotsDialog(alphabetKeysList.get(position));
                }
            }
        });

        lvAlphabet.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < alphabetKeysList.size()) {
                    showDeleteCharacterDialog(alphabetKeysList.get(position));
                }
                return true;
            }
        });

        layout.addView(lvAlphabet, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        refreshAlphabetListView();
        return layout;
    }

    private void refreshAlphabetListView() {
        alphabetKeysList.clear();
        alphabetDisplayList.clear();
        for (Map.Entry<String, String> entry : charToDotsMap.entrySet()) {
            String ch = entry.getKey();
            String dots = entry.getValue();
            alphabetKeysList.add(ch);
            alphabetDisplayList.add("Chữ [" + ch + "]   ➔   Chấm: " + dots);
        }
        if (alphabetListAdapter != null) {
            alphabetListAdapter.notifyDataSetChanged();
        }
    }

    private void showDeleteCharacterDialog(final String charKey) {
        String dots = charToDotsMap.get(charKey);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Xóa ký tự: " + charKey);
        builder.setMessage("Bạn có chắc muốn xóa '" + charKey + "' (chấm " + (dots != null ? dots : "") + ") khỏi bảng chữ cái không?");
        builder.setPositiveButton("Xóa", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                charToDotsMap.remove(charKey);
                saveAlphabetSettings();
                refreshAlphabetListView();
                speak("Đã xóa " + charKey + " khỏi bảng chữ cái", true);
            }
        });
        builder.setNegativeButton("Hủy", null);
        builder.show();
    }

    private String buildAlphabetExportText() {
        StringBuilder sb = new StringBuilder();
        sb.append(ALPHABET_EXPORT_HEADER).append("\n");
        for (Map.Entry<String, String> entry : charToDotsMap.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.length() == 0 || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) continue;
            String dots = normalizeDotsString(entry.getValue());
            if (dots.length() == 0) continue;
            sb.append(key).append("=").append(dots).append("\n");
        }
        return sb.toString();
    }

    private LinkedHashMap<String, String> parseAlphabetExportText(String text) {
        if (text == null) return null;
        String[] lines = text.split("\\r?\\n");
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        boolean headerFound = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            if (!headerFound) {
                if (line.equals(ALPHABET_EXPORT_HEADER)) {
                    headerFound = true;
                }
                continue;
            }
            int eq = line.lastIndexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq);
            String dots = normalizeDotsString(line.substring(eq + 1));
            if (key.length() == 0 || dots.length() == 0) continue;
            result.put(key, dots);
        }
        if (!headerFound || result.isEmpty()) return null;
        return result;
    }

    private void exportAlphabetToFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "BangChuCaiBraille.txt");
            startActivityForResult(intent, REQUEST_EXPORT_ALPHABET_FILE);
            speak("Hãy chọn thư mục Download hoặc nơi muốn lưu tệp, rồi bấm Lưu", true);
        } catch (Throwable t) {
            speak("Không mở được hộp thoại lưu tệp", true);
            Toast.makeText(this, "Không mở được hộp thoại lưu tệp", Toast.LENGTH_LONG).show();
        }
    }

    private void writeAlphabetToUri(Uri uri) {
        java.io.OutputStream out = null;
        try {
            out = getContentResolver().openOutputStream(uri);
            if (out == null) {
                throw new java.io.IOException("Không mở được luồng ghi");
            }
            out.write(buildAlphabetExportText().getBytes("UTF-8"));
            out.flush();
            speak("Đã lưu tệp bảng chữ cái. Bạn có thể mở thư mục chứa tệp để chia sẻ.", true);
            Toast.makeText(this, "Đã lưu tệp bảng chữ cái", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            speak("Không lưu được tệp", true);
            Toast.makeText(this, "Không lưu được tệp", Toast.LENGTH_LONG).show();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void importAlphabetFromFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_IMPORT_ALPHABET_FILE);
            speak("Hãy chọn tệp bảng chữ cái đã lưu", true);
        } catch (Throwable t) {
            speak("Không mở được hộp thoại chọn tệp", true);
            Toast.makeText(this, "Không mở được hộp thoại chọn tệp", Toast.LENGTH_LONG).show();
        }
    }

    private String readTextFromUri(Uri uri) {
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 1024 * 1024) return null;
            }
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void handleImportedAlphabetFile(Uri uri) {
        String text = readTextFromUri(uri);
        if (text == null) {
            speak("Không đọc được tệp", true);
            Toast.makeText(this, "Không đọc được tệp", Toast.LENGTH_LONG).show();
            return;
        }
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        final LinkedHashMap<String, String> parsed = parseAlphabetExportText(text);
        if (parsed == null) {
            speak("Tệp không đúng định dạng bảng chữ cái", true);
            Toast.makeText(this, "Tệp không đúng định dạng bảng chữ cái!", Toast.LENGTH_LONG).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Nhập bảng chữ cái từ tệp");
        builder.setMessage("Tệp có " + parsed.size() + " ký tự. Chọn Gộp để giữ các chữ hiện có, hoặc Thay thế toàn bộ để dùng đúng bảng chữ trong tệp.");
        builder.setPositiveButton("Gộp", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                applyImportedAlphabet(parsed, false);
            }
        });
        builder.setNeutralButton("Thay thế toàn bộ", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                applyImportedAlphabet(parsed, true);
            }
        });
        builder.setNegativeButton("Hủy", null);
        builder.show();
    }

    private void applyImportedAlphabet(LinkedHashMap<String, String> imported, boolean replaceAll) {
        if (imported == null || imported.isEmpty()) {
            speak("Dữ liệu bảng chữ cái không hợp lệ", true);
            Toast.makeText(this, "Dữ liệu không đúng định dạng bảng chữ cái!", Toast.LENGTH_LONG).show();
            return;
        }
        if (replaceAll) {
            charToDotsMap.clear();
        }
        for (Map.Entry<String, String> entry : imported.entrySet()) {
            charToDotsMap.put(entry.getKey(), entry.getValue());
        }
        saveAlphabetSettings();
        refreshAlphabetListView();
        speak((replaceAll ? "Đã thay thế bảng chữ cái bằng " : "Đã gộp thêm ") + imported.size() + " ký tự", true);
    }

    private void showEditCharacterDotsDialog(final String charKey) {

        String currentDots = charToDotsMap.get(charKey);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Sửa số chấm cho: " + charKey);
        builder.setMessage("Bạn muốn nhập số chấm nào cho '" + charKey + "'? (Chỉ nhập các số từ 1 đến 6, ví dụ: 24)");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(currentDots != null ? currentDots : "");
        input.setSelection(input.getText().length());
        builder.setView(input);

        builder.setPositiveButton("Lưu", null);
        builder.setNegativeButton("Hủy", null);
        final AlertDialog editDialog = builder.create();
        editDialog.show();
        editDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String normalized = normalizeDotsString(input.getText().toString().trim());
                if (normalized.length() == 0) {
                    speak("Số chấm không hợp lệ, hãy nhập từ 1 đến 6", true);
                    Toast.makeText(MainActivity.this, "Chỉ nhập các số từ 1 đến 6!", Toast.LENGTH_SHORT).show();
                    return;
                }
                String conflictKey = findDotsConflict(normalized, charKey);
                if (conflictKey != null) {
                    String errorMessage = "Không thể dùng: tổ hợp chấm " + normalized
                            + " đã được dùng cho '" + conflictKey + "'. Hãy nhập tổ hợp khác.";
                    speak(errorMessage, true);
                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                    return;
                }
                charToDotsMap.put(charKey, normalized);
                saveAlphabetSettings();
                refreshAlphabetListView();
                editDialog.dismiss();
                speak("Đã lưu " + charKey + " là chấm " + normalized, true);
            }
        });
    }

    private void showAddCharacterDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));

        final EditText edtChar = new EditText(this);
        edtChar.setHint("Nhập chữ cái hoặc ký tự (ví dụ: i)");
        box.addView(edtChar);

        final EditText edtDots = new EditText(this);
        edtDots.setHint("Nhập số chấm từ 1 đến 6 (ví dụ: 24)");
        edtDots.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(edtDots);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Thêm ký tự mới");
        builder.setView(box);
        builder.setPositiveButton("Lưu", null);
        builder.setNegativeButton("Hủy", null);
        final AlertDialog addDialog = builder.create();
        addDialog.show();
        addDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ch = edtChar.getText().toString().trim();
                String dots = normalizeDotsString(edtDots.getText().toString().trim());
                if (ch.length() == 0 || dots.length() == 0) {
                    Toast.makeText(MainActivity.this, "Vui lòng nhập đầy đủ chữ và số chấm!", Toast.LENGTH_SHORT).show();
                    return;
                }
                String conflictKey = findDotsConflict(dots, ch);
                if (conflictKey != null) {
                    String errorMessage = "Không thể thêm '" + ch + "': tổ hợp chấm " + dots
                            + " đã được dùng cho '" + conflictKey + "'. Hãy nhập tổ hợp khác.";
                    speak(errorMessage, true);
                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                    return;
                }
                charToDotsMap.put(ch, dots);
                saveAlphabetSettings();
                refreshAlphabetListView();
                addDialog.dismiss();
                speak("Đã thêm " + ch + " với chấm " + dots, true);
            }
        });
    }

    private String findDotsConflict(String normalizedDots, String exceptKey) {
        for (Map.Entry<String, String> other : charToDotsMap.entrySet()) {
            if (!other.getKey().equals(exceptKey) && normalizedDots.equals(normalizeDotsString(other.getValue()))) {
                return other.getKey();
            }
        }
        return null;
    }

    private void fixHyphenDotsConflict() {
        String hyphenDots = charToDotsMap.get("-");
        String tildeDots = charToDotsMap.get("dấu ngã");
        if (hyphenDots != null && tildeDots != null
                && normalizeDotsString(hyphenDots).equals(normalizeDotsString(tildeDots))
                && findDotsConflict("356", "-") == null) {
            charToDotsMap.put("-", "356");
        }
    }

    private ScrollView buildSection4PhoneOptions() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(6), dp(6), dp(6), dp(16));

        Button btnEnableKeyboard = new Button(this);
        btnEnableKeyboard.setAllCaps(false);
        btnEnableKeyboard.setText("Bước 1: Bật bàn phím chữ nổi trong Cài đặt");
        btnEnableKeyboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEnableKeyboardSettingsLocal();
            }
        });
        container.addView(btnEnableKeyboard);

        Button btnPickKeyboard = new Button(this);
        btnPickKeyboard.setAllCaps(false);
        btnPickKeyboard.setText("Bước 2: Chọn làm bàn phím mặc định");
        btnPickKeyboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showKeyboardPickerLocal();
            }
        });
        container.addView(btnPickKeyboard);

        CheckBox cbPerDot = createOptionCheckBox(

                "Vuốt cử chỉ theo từng chấm (Vuốt Sang Phải/Sang Trái cho thao tác chính, Lên/Xuống cho tính năng phụ)",
                usePerDotGestures,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        usePerDotGestures = isChecked;
                        savePhoneOptions();
                        speak(isChecked ? "Đã bật vuốt cử chỉ theo từng chấm" : "Đã chuyển sang vuốt chung toàn màn hình", true);
                    }
                });
        container.addView(cbPerDot);

        CheckBox cbSlate = createOptionCheckBox(
                "Chế độ Bảng và Dùi (Đảo cụm chấm 1-2-3 và 4-5-6)",
                slateAndStylusMode,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        slateAndStylusMode = isChecked;
                        savePhoneOptions();
                        speak(isChecked ? "Đã bật chế độ Bảng và Dùi, đảo cụm 1 2 3 với 4 5 6" : "Đã tắt chế độ Bảng và Dùi", true);
                    }
                });
        container.addView(cbSlate);

        CheckBox cbInvert = createOptionCheckBox(
                "Đảo ngược đầu điện thoại 180 độ",
                invertKeyboard180,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        invertKeyboard180 = isChecked;
                        savePhoneOptions();
                        speak(isChecked ? "Đã đảo ngược bàn phím 180 độ" : "Đã trả về chiều bàn phím chuẩn", true);
                    }
                });
        container.addView(cbInvert);

        CheckBox cbAutoCap = createOptionCheckBox(
                "Tự động viết hoa chữ cái đầu câu",
                autoCapitalize,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        autoCapitalize = isChecked;
                        savePhoneOptions();
                        speak(isChecked ? "Đã bật tự động viết hoa đầu câu" : "Đã tắt tự động viết hoa", true);
                    }
                });
        container.addView(cbAutoCap);

        CheckBox cbDoubleSpace = createOptionCheckBox(
                "Gõ 2 lần dấu cách liên tiếp để chấm câu (. )",
                doubleSpacePeriod,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        doubleSpacePeriod = isChecked;
                        savePhoneOptions();
                        speak(isChecked ? "Đã bật gõ 2 lần dấu cách thành dấu chấm câu" : "Đã tắt gõ 2 lần dấu cách", true);
                    }
                });
        container.addView(cbDoubleSpace);

        CheckBox cbSpeakPassword = createOptionCheckBox(
                "Đọc từng ký tự khi gõ trong ô mật khẩu (tắt: chỉ đọc Sao)",
                speakPasswordChars,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        speakPasswordChars = isChecked;
                        savePhoneOptions();
                        speak(isChecked ? "Đã bật đọc ký tự trong ô mật khẩu" : "Đã tắt đọc ký tự trong ô mật khẩu", true);
                    }
                });
        container.addView(cbSpeakPassword);

        CheckBox cbPrivacy = createOptionCheckBox(

                "Màn hình Tối (Làm tối đen hoàn toàn khi mở bàn phím)",
                privacyScreenActive,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        privacyScreenActive = isChecked;
                        savePhoneOptions();
                        speak(isChecked ? "Đã bật màn hình tối" : "Đã chuyển về màn hình sáng", true);
                    }
                });
        container.addView(cbPrivacy);

        Button btnSpeed = new Button(this);
        btnSpeed.setAllCaps(false);
        btnSpeed.setText("Tăng tốc khi nhập");
        btnSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showTypingSpeedSettings(); }
        });
        container.addView(btnSpeed);

        Button btnResetGrid = new Button(this);

        btnResetGrid.setText("Dùng lại lưới cố định (Đầu loa 1-2-3 & Cổng sạc 4-5-6)");
        btnResetGrid.setAllCaps(false);
        btnResetGrid.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                useCalibratedPoints = false;
                calibrationDirty = true;
                savePhoneOptions();
                speak("Đã chuyển về lưới cố định theo đầu loa trên 1 2 3 và cổng sạc dưới 4 5 6", true);
            }
        });
        container.addView(btnResetGrid);

        scroll.addView(container);
return scroll;
}


    private void openEnableKeyboardSettingsLocal() {
        try {
            Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "Không thể mở cài đặt bàn phím", Toast.LENGTH_SHORT).show();
        }
    }

    private void showKeyboardPickerLocal() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showInputMethodPicker();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Không thể mở hộp thoại chọn bàn phím", Toast.LENGTH_SHORT).show();
        }
    }


    private interface SpeedSliderListener {
        void onValueChanged(int value);
    }

    private SeekBar addSpeedSlider(LinearLayout parent, final String title, final String unit,
            int minimum, int maximum, int value, final SpeedSliderListener listener) {
        final TextView label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setPadding(dp(8), dp(12), dp(8), dp(2));
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(label);
        SeekBar bar = new SeekBar(this);
        bar.setMin(minimum);
        bar.setMax(maximum);
        bar.setProgress(value);
        bar.setContentDescription(title);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(title + ": " + progress + " " + unit);
                if (fromUser) {
                    listener.onValueChanged(progress);
                    savePhoneOptions();
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        label.setText(title + ": " + value + " " + unit);
        parent.addView(bar);
        return bar;
    }

    private void showTypingSpeedSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(18));
        scroll.addView(content);
        final SeekBar letter = addSpeedSlider(content, "Độ trễ nhập chữ", "ms",
                BrailleSpeedSettings.MIN_LETTER_DELAY_MS, BrailleSpeedSettings.MAX_LETTER_DELAY_MS,
                (int) letterDelayMs, new SpeedSliderListener() {
                    @Override
                    public void onValueChanged(int value) { letterDelayMs = value; }
                });
        final SeekBar shortDistance = addSpeedSlider(content,
                "Khoảng cách tối thiểu để nhận vuốt", "dp",
                BrailleSpeedSettings.MIN_SWIPE_DISTANCE_DP, BrailleSpeedSettings.MAX_SWIPE_DISTANCE_DP,
                swipeDistanceDp, new SpeedSliderListener() {
                    @Override
                    public void onValueChanged(int value) { swipeDistanceDp = value; }
                });
        final SeekBar time = addSpeedSlider(content, "Thời gian nhận cử chỉ", "ms",
                BrailleSpeedSettings.MIN_GESTURE_DELAY_MS, BrailleSpeedSettings.MAX_GESTURE_DELAY_MS,
                (int) gestureDelayMs, new SpeedSliderListener() {
                    @Override
                    public void onValueChanged(int value) { gestureDelayMs = value; }
                });
        final CheckBox distinct = createOptionCheckBox("Phân biệt vuốt ngắn và vuốt dài",
                distinguishSwipeLength, new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean checked) {
                        distinguishSwipeLength = checked;
                        savePhoneOptions();
                        refreshGestureListView();
                    }
                });
        content.addView(distinct);
        final SeekBar longDistance = addSpeedSlider(content, "Ngưỡng vuốt dài", "dp",
                BrailleSpeedSettings.MIN_LONG_SWIPE_DISTANCE_DP,
                BrailleSpeedSettings.MAX_LONG_SWIPE_DISTANCE_DP, longSwipeDistanceDp,
                new SpeedSliderListener() {
                    @Override
                    public void onValueChanged(int value) { longSwipeDistanceDp = value; }
                });
        TextView explanation = new TextView(this);
        explanation.setText("Vuốt ngắn dùng chức năng hiện có. Khi bật, gán chức năng vuốt dài ở Cài đặt cử chỉ. Vuốt dài chưa được gán sẽ hoạt động như vuốt ngắn.");
        explanation.setTextColor(Color.WHITE);
        explanation.setPadding(dp(8), dp(12), dp(8), dp(12));
        content.addView(explanation);
        Button reset = new Button(this);
        reset.setAllCaps(false);
        reset.setText("Khôi phục tốc độ mặc định");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                letterDelayMs = BrailleSpeedSettings.DEFAULT_LETTER_DELAY_MS;
                gestureDelayMs = BrailleSpeedSettings.DEFAULT_GESTURE_DELAY_MS;
                swipeDistanceDp = BrailleSpeedSettings.DEFAULT_SWIPE_DISTANCE_DP;
                longSwipeDistanceDp = BrailleSpeedSettings.DEFAULT_LONG_SWIPE_DISTANCE_DP;
                distinguishSwipeLength = false;
                letter.setProgress((int) letterDelayMs);
                shortDistance.setProgress(swipeDistanceDp);
                time.setProgress((int) gestureDelayMs);
                longDistance.setProgress(longSwipeDistanceDp);
                distinct.setChecked(false);
                savePhoneOptions();
            }
        });
        content.addView(reset);
        new AlertDialog.Builder(this).setTitle("Tăng tốc khi nhập")
                .setView(scroll).setPositiveButton("Đóng", null).show();
    }

    private CheckBox createOptionCheckBox(String text, boolean checked, CompoundButton.OnCheckedChangeListener listener) {

        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextColor(Color.WHITE);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cb.setPadding(dp(8), dp(10), dp(8), dp(10));
        cb.setChecked(checked);
        cb.setOnCheckedChangeListener(listener);
        return cb;
    }

    private void addDotToPendingChord(int dotNumber, View sourceView) {
        if (dotNumber < 1 || dotNumber > 6) return;

        mainHandler.removeCallbacks(singleFingerCommitRunnable);
        if (!pendingChordDots[dotNumber]) {
            pendingChordDots[dotNumber] = true;
            triggerFeedback(sourceView, 22L, ToneGenerator.TONE_PROP_BEEP, 40);
            if (brailleCanvasView != null) brailleCanvasView.invalidate();
        }
        mainHandler.postDelayed(singleFingerCommitRunnable, letterDelayMs);
    }

    private void clearPendingDots() {
        mainHandler.removeCallbacks(singleFingerCommitRunnable);
        for (int i = 1; i <= 6; i++) {
            pendingChordDots[i] = false;
        }
        if (brailleCanvasView != null) brailleCanvasView.invalidate();
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
        if (brailleCanvasView != null) brailleCanvasView.invalidate();

        String dotsKey = sb.toString();
        if (dotsKey.length() == 0) return;

        processBrailleChord(dotsKey);
    }

    private void processBrailleChord(String dotsKey) {
        String matched = dotsToCharMap.get(dotsKey);
        if (matched == null && "3456".equals(dotsKey)) {
            matched = "dấu báo số";
        }
        if (matched == null) {
            triggerFeedback(brailleCanvasView, 70L, ToneGenerator.TONE_SUP_ERROR, 110);
            speak("Chưa có chấm " + dotsKey, true);
            return;
        }

        triggerFeedback(brailleCanvasView, 40L, ToneGenerator.TONE_PROP_ACK, 65,
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
                insertTextAtCursor(digit);
                if (echoMode == ECHO_CHAR_AND_WORD || echoMode == ECHO_CHAR_ONLY) {
                    speak(digit, true);
                }
                return;
            } else {
                numberModeActive = false;
            }
        }

        if (isVietnameseToneKey(matched)) {
            carriedToneName = matched;
            carriedToneCursor = cursorPos;
            boolean applied = applyCarriedToneToWord();
            carriedToneUsed = applied;
            if (applied) {
                String word = getWordAroundCursor();
                speakTyping(matched + ", " + word, true);
            } else {
                speakTyping(matched + ", hãy viết chữ cái", true);
            }
            updateVirtualInputDisplay();
            return;
        }

        String outputChar = matched;
        if (nextCharCapital || capsLockWord || capsLockSticky) {
            outputChar = outputChar.toUpperCase(new Locale("vi", "VN"));
            nextCharCapital = false;
        }

        boolean carryTone = carriedToneName != null
                && carriedToneCursor == cursorPos
                && outputChar.length() == 1
                && Character.isLetter(outputChar.charAt(0));

        insertTextAtCursor(outputChar);

        String echoText = outputChar;
        if (carryTone) {
            if (applyCarriedToneToWord()) {
                carriedToneUsed = true;
            }
            carriedToneCursor = cursorPos;
            if (cursorPos > 0 && cursorPos <= textBuffer.length()) {
                echoText = String.valueOf(textBuffer.charAt(cursorPos - 1));
            }
            updateVirtualInputDisplay();
        } else {
            carriedToneName = null;
            carriedToneCursor = -1;
        }

        if (echoMode == ECHO_CHAR_AND_WORD || echoMode == ECHO_CHAR_ONLY) {
            speak(echoText, true);
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

    private void insertTextAtCursor(String str) {
        navigationState.reset();
        cursorPos = Math.max(0, Math.min(cursorPos, textBuffer.length()));
        textBuffer.insert(cursorPos, str);
        cursorPos += str.length();
        updateVirtualInputDisplay();
    }

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

    private boolean applyCarriedToneToWord() {
        int toneIndex = toneIndexOf(carriedToneName);
        if (toneIndex < 0) return false;

        int end = Math.max(0, Math.min(cursorPos, textBuffer.length()));
        int start = end;
        while (start > 0) {
            char ch = textBuffer.charAt(start - 1);
            if (ch == ' ' || ch == '\n') break;
            start--;
        }
        if (start >= end) return false;

        int len = end - start;
        int[] rows = new int[len];
        for (int i = 0; i < len; i++) {
            rows[i] = vowelRowOf(textBuffer.charAt(start + i));
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
            char prev = Character.toLowerCase(textBuffer.charAt(start + first - 1));
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
            char c = textBuffer.charAt(start + i);
            if (rows[i] >= 0) {
                int col = (i == target) ? toneIndex : 0;
                String rep = VIET_VOWELS[rows[i]][col];
                if (Character.isUpperCase(c)) rep = rep.toUpperCase(new Locale("vi", "VN"));
                sb.append(rep);
            } else {
                sb.append(c);
            }
        }
        textBuffer.replace(start, end, sb.toString());
        return true;
    }

    private boolean isVietnameseToneKey(String key) {
        return "dấu sắc".equals(key)

                || "dấu huyền".equals(key)
                || "dấu hỏi".equals(key)
                || "dấu ngã".equals(key)
                || "dấu nặng".equals(key);
    }

    private boolean applyVietnameseToneBeforeCursor(String toneName) {
        if (textBuffer.length() == 0 || cursorPos <= 0) return false;
        int toneIndex = -1;
        if ("dấu sắc".equals(toneName)) toneIndex = 1;
        else if ("dấu huyền".equals(toneName)) toneIndex = 2;
        else if ("dấu hỏi".equals(toneName)) toneIndex = 3;
        else if ("dấu ngã".equals(toneName)) toneIndex = 4;
        else if ("dấu nặng".equals(toneName)) toneIndex = 5;
        if (toneIndex == -1) return false;

        String[][] vowelTable = new String[][]{
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

        int safeEnd = Math.min(cursorPos, textBuffer.length());
        int wordStart = safeEnd;
        while (wordStart > 0) {
            char ch = textBuffer.charAt(wordStart - 1);
            if (ch == ' ' || ch == '\n') break;
            wordStart--;
        }

        int targetPos = -1;
        int targetRow = -1;
        boolean targetIsUpper = false;

        for (int i = safeEnd - 1; i >= wordStart; i--) {
            char c = textBuffer.charAt(i);
            String s = String.valueOf(c);
            for (int r = 0; r < vowelTable.length; r++) {
                for (int col = 0; col < 6; col++) {
                    if (vowelTable[r][col].equalsIgnoreCase(s)) {
                        targetPos = i;
                        targetRow = r;
                        targetIsUpper = Character.isUpperCase(c);
                        if (r == 1 || r == 2 || r == 4 || r == 7 || r == 8 || r == 10) {
                            String rep = vowelTable[targetRow][toneIndex];
                            if (targetIsUpper) rep = rep.toUpperCase(new Locale("vi", "VN"));
                            textBuffer.setCharAt(targetPos, rep.charAt(0));
                            return true;
                        }
                        break;
                    }
                }
                if (targetPos == i) break;
            }
            if (targetPos != -1) break;
        }

        if (targetPos != -1 && targetRow != -1) {
            String rep = vowelTable[targetRow][toneIndex];
            if (targetIsUpper) rep = rep.toUpperCase(new Locale("vi", "VN"));
            textBuffer.setCharAt(targetPos, rep.charAt(0));
            return true;
        }
        return false;
    }

    private void dispatchSwipeGesture(int startDot, String direction, boolean twoFingers, boolean longSwipe) {
        clearPendingDots();

        String gestureKey;
        if (twoFingers) {
            if ("DOWN".equals(direction)) gestureKey = GES_GLOBAL_DOWN_2;
            else if ("UP".equals(direction)) gestureKey = GES_GLOBAL_UP_2;
            else if ("LEFT".equals(direction)) gestureKey = GES_GLOBAL_LEFT_2;
            else gestureKey = GES_GLOBAL_RIGHT_2;
        } else if (usePerDotGestures && startDot >= 1 && startDot <= 6) {
            gestureKey = "DOT" + startDot + "_" + direction;
        } else {
            if ("DOWN".equals(direction)) gestureKey = GES_GLOBAL_DOWN_1;
            else if ("UP".equals(direction)) gestureKey = GES_GLOBAL_UP_1;
            else if ("LEFT".equals(direction)) gestureKey = GES_GLOBAL_LEFT_1;
            else gestureKey = GES_GLOBAL_RIGHT_1;
        }

        String actionCode = BrailleSpeedSettings.swipeAction(gestureActionMap, gestureKey,
                distinguishSwipeLength, longSwipe);
        if (actionCode != null) {
            executeAction(actionCode);
        }
    }

    private void executeAction(String actionCode) {
        cursorPos = Math.max(0, Math.min(cursorPos, textBuffer.length()));
        if (!ACT_DELETE_CHAR_AT_CURSOR.equals(actionCode)
                && !ACT_MOVE_CHAR_NEXT.equals(actionCode)
                && !ACT_MOVE_CHAR_PREV.equals(actionCode)
                && !ACT_MOVE_LINE_BOUNDARY_NEXT.equals(actionCode)) {
            navigationState.reset();
        }
        long now = System.currentTimeMillis();
        capitalSignCount = 0;
        carriedToneName = null;
        carriedToneCursor = -1;
        carriedToneUsed = false;

        if (ACT_SPACE.equals(actionCode)) {
            numberModeActive = false;
            capsLockWord = false;

            if (doubleSpacePeriod && (now - lastSpaceTimeMs <= DOUBLE_SPACE_THRESHOLD_MS)
                    && cursorPos >= 2 && textBuffer.charAt(cursorPos - 1) == ' '
                    && textBuffer.charAt(cursorPos - 2) != ' ' && textBuffer.charAt(cursorPos - 2) != '.') {
                textBuffer.replace(cursorPos - 1, cursorPos, ". ");
                cursorPos += 1;
                lastSpaceTimeMs = 0L;
                if (autoCapitalize) nextCharCapital = true;
                triggerFeedback(brailleCanvasView, 45L, ToneGenerator.TONE_PROP_ACK, 70, SOUND_SLOT_SPACE);
                speakTyping("Chấm câu", true);
            } else {
                String completedWord = getWordBeforeCursor();
                insertTextAtCursor(" ");
                lastSpaceTimeMs = now;
                triggerFeedback(brailleCanvasView, 30L, ToneGenerator.TONE_PROP_ACK, 50, SOUND_SLOT_SPACE);
                if ((echoMode == ECHO_CHAR_AND_WORD || echoMode == ECHO_WORD_ONLY) && completedWord.length() > 0) {
                    speak(completedWord, true);
                } else {
                    speakTyping("Dấu cách", true);
                }
            }

        } else if (ACT_BACKSPACE.equals(actionCode)) {
            if (cursorPos > 0 && textBuffer.length() > 0) {
                char deleted = textBuffer.charAt(cursorPos - 1);
                textBuffer.deleteCharAt(cursorPos - 1);
                cursorPos--;
                triggerFeedback(brailleCanvasView, 40L, ToneGenerator.TONE_PROP_NACK, 60);
                speak("Đã xóa " + describeCharForSpeech(deleted), true);
            } else {
                speak("Đầu văn bản, không có ký tự để xóa", true);
            }

        } else if (ACT_DELETE_CHAR_AT_CURSOR.equals(actionCode)) {
            BrailleTextOperations.Range range =
                    navigationState.focusedCharacter(textBuffer.toString(), cursorPos);
            if (!range.isEmpty()) {
                String deleted = textBuffer.substring(range.start, range.end);
                textBuffer.delete(range.start, range.end);
                cursorPos = range.start;
                speak("Đã xóa ký tự " + deleted, true);
            } else {
                speak("Không có ký tự để xóa", true);
            }
            navigationState.reset();

        } else if (ACT_DELETE_WORD_AT_CURSOR.equals(actionCode)) {
            BrailleTextOperations.Range range =
                    BrailleTextOperations.wordAt(textBuffer.toString(), cursorPos);
            if (!range.isEmpty()) {
                String deleted = textBuffer.substring(range.start, range.end);
                textBuffer.delete(range.start, range.end);
                cursorPos = range.start;
                speak("Đã xóa từ " + deleted, true);
            } else {
                speak("Không có từ để xóa", true);
            }
            navigationState.reset();

        } else if (ACT_DELETE_WORD.equals(actionCode)) {
            if (cursorPos > 0 && textBuffer.length() > 0) {
                int start = cursorPos;
                while (start > 0 && textBuffer.charAt(start - 1) == ' ') start--;
                while (start > 0 && textBuffer.charAt(start - 1) != ' ' && textBuffer.charAt(start - 1) != '\n') start--;
                String deletedWord = textBuffer.substring(start, cursorPos).trim();
                textBuffer.delete(start, cursorPos);
                cursorPos = start;
                triggerFeedback(brailleCanvasView, 55L, ToneGenerator.TONE_PROP_NACK, 85);
                speak("Đã xóa từ " + deletedWord, true);
            } else {
                speak("Không có từ để xóa", true);
            }

        } else if (ACT_DELETE_LINE.equals(actionCode)) {
            if (textBuffer.length() == 0) {
                speak("Ô nhập đang trống", true);
                return;
            }
            if (now - lastDeleteLineRequestMs > DOUBLE_SWIPE_CONFIRM_MS) {
                lastDeleteLineRequestMs = now;
                triggerFeedback(brailleCanvasView, 50L, ToneGenerator.TONE_PROP_BEEP2, 90);
                speak("Vuốt thêm 1 lần nữa để xác nhận xóa cả dòng", true);
                return;
            }
            lastDeleteLineRequestMs = 0L;
            int lineStart = getLineStart(cursorPos);
            int lineEnd = getLineEnd(cursorPos);
            String deletedLine = textBuffer.substring(lineStart, lineEnd);
            textBuffer.delete(lineStart, Math.min(textBuffer.length(), lineEnd + 1));
            cursorPos = Math.min(lineStart, textBuffer.length());
            triggerFeedback(brailleCanvasView, 80L, ToneGenerator.TONE_PROP_NACK, 110);
            speak("Đã xóa dòng: " + deletedLine, true);

        } else if (ACT_CLEAR_ALL.equals(actionCode)) {
            if (textBuffer.length() == 0) {
                speak("Ô nhập đang trống", true);
                return;
            }
            if (now - lastClearAllRequestMs > DOUBLE_SWIPE_CONFIRM_MS) {
                lastClearAllRequestMs = now;
                triggerFeedback(brailleCanvasView, 50L, ToneGenerator.TONE_PROP_BEEP2, 90);
                speak("Vuốt thêm 1 lần nữa để xóa toàn bộ văn bản", true);
                return;
            }
            lastClearAllRequestMs = 0L;
            textBuffer.setLength(0);
            cursorPos = 0;
            markPos = -1;
            triggerFeedback(brailleCanvasView, 90L, ToneGenerator.TONE_PROP_NACK, 120);
            speak("Đã xóa toàn bộ văn bản", true);

        } else if (ACT_ENTER.equals(actionCode)) {
            numberModeActive = false;
            capsLockWord = false;
            if (autoCapitalize) nextCharCapital = true;
            insertTextAtCursor("\n");
            triggerFeedback(brailleCanvasView, 45L, ToneGenerator.TONE_PROP_ACK, 70);
            speak("Xuống dòng", true);

        } else if (ACT_MOVE_CHAR_NEXT.equals(actionCode)) {
            if (cursorPos < textBuffer.length()) {
                char c = textBuffer.charAt(cursorPos);
                cursorPos++;
                triggerFeedback(brailleCanvasView, 20L, ToneGenerator.TONE_PROP_BEEP, 35);
                speak(describeCharForSpeech(c), true);
            } else {
                speak("Cuối văn bản", true);
            }

        } else if (ACT_MOVE_CHAR_PREV.equals(actionCode)) {
            if (cursorPos > 0) {
                cursorPos--;
                char c = textBuffer.charAt(cursorPos);
                triggerFeedback(brailleCanvasView, 20L, ToneGenerator.TONE_PROP_BEEP, 35);
                speak(describeCharForSpeech(c), true);
            } else {
                speak("Đầu văn bản", true);
            }

        } else if (ACT_MOVE_WORD_NEXT.equals(actionCode)) {
            if (cursorPos < textBuffer.length()) {
                while (cursorPos < textBuffer.length() && textBuffer.charAt(cursorPos) != ' ' && textBuffer.charAt(cursorPos) != '\n') {
                    cursorPos++;
                }
                while (cursorPos < textBuffer.length() && (textBuffer.charAt(cursorPos) == ' ' || textBuffer.charAt(cursorPos) == '\n')) {
                    cursorPos++;
                }
                triggerFeedback(brailleCanvasView, 25L, ToneGenerator.TONE_PROP_BEEP, 40);
                String w = getWordAroundCursor();
                speak(w.length() > 0 ? w : "Cuối văn bản", true);
            } else {
                speak("Cuối văn bản", true);
            }

        } else if (ACT_MOVE_WORD_PREV.equals(actionCode)) {
            if (cursorPos > 0) {
                while (cursorPos > 0 && (textBuffer.charAt(cursorPos - 1) == ' ' || textBuffer.charAt(cursorPos - 1) == '\n')) {
                    cursorPos--;
                }
                while (cursorPos > 0 && textBuffer.charAt(cursorPos - 1) != ' ' && textBuffer.charAt(cursorPos - 1) != '\n') {
                    cursorPos--;
                }
                triggerFeedback(brailleCanvasView, 25L, ToneGenerator.TONE_PROP_BEEP, 40);
                String w = getWordAroundCursor();
                speak(w.length() > 0 ? w : "Đầu văn bản", true);
            } else {
                speak("Đầu văn bản", true);
            }

        } else if (ACT_MOVE_LINE_NEXT.equals(actionCode)) {
            int lineEnd = getLineEnd(cursorPos);
            if (lineEnd < textBuffer.length()) {
                cursorPos = lineEnd + 1;
                speak(getCurrentLineText(), true);
            } else {
                speak("Dòng cuối cùng", true);
            }

        } else if (ACT_MOVE_LINE_PREV.equals(actionCode)) {
            int lineStart = getLineStart(cursorPos);
            if (lineStart > 0) {
                cursorPos = getLineStart(lineStart - 1);
                speak(getCurrentLineText(), true);
            } else {
                speak("Dòng đầu tiên", true);
            }

        } else if (ACT_MOVE_START.equals(actionCode)) {
            cursorPos = 0;
            speak("Đầu văn bản", true);

        } else if (ACT_MOVE_END.equals(actionCode)) {
            cursorPos = textBuffer.length();
            speak("Cuối văn bản", true);

        } else if (ACT_READ_CHAR.equals(actionCode)) {
            if (cursorPos < textBuffer.length()) {
                speak(describeCharForSpeech(textBuffer.charAt(cursorPos)), true);
            } else if (cursorPos > 0) {
                speak(describeCharForSpeech(textBuffer.charAt(cursorPos - 1)), true);
            } else {
                speak("Trống", true);
            }

        } else if (ACT_READ_WORD.equals(actionCode)) {
            String w = getWordAroundCursor();
            speak(w.length() > 0 ? w : "Chưa có từ nào", true);

        } else if (ACT_READ_LINE.equals(actionCode)) {
            String line = getCurrentLineText();
            speak(line.length() > 0 ? line : "Dòng trống", true);

        } else if (ACT_SPEAK_TEXT.equals(actionCode)) {
            String all = textBuffer.toString().trim();
            speak(all.length() > 0 ? all : "Ô nhập liệu đang trống", true);

        } else if (ACT_SPEAK_STATS.equals(actionCode)) {
            String all = textBuffer.toString();
            int charCount = all.length();
            String trimmed = all.trim();
            int wordCount = trimmed.length() == 0 ? 0 : trimmed.split("\\s+").length;
            int lineCount = all.length() == 0 ? 0 : all.split("\n", -1).length;
            speak("Thống kê: " + charCount + " ký tự, " + wordCount + " từ, " + lineCount + " dòng.", true);

        } else if (ACT_SET_MARK.equals(actionCode)) {
            markPos = cursorPos;
            triggerFeedback(brailleCanvasView, 50L, ToneGenerator.TONE_PROP_ACK, 80);
            speak("Đã cắm mốc chọn văn bản tại vị trí " + markPos + ". Di chuyển con trỏ rồi vuốt chấm 6 xuống để chọn Sao chép hoặc Cắt.", true);

        } else if (ACT_CYCLE_SELECTION_MENU.equals(actionCode)) {
            currentSelectionMenuIndex = (currentSelectionMenuIndex + 1) % selectionMenuOptions.length;
            String optionName = selectionMenuOptions[currentSelectionMenuIndex];
            triggerFeedback(brailleCanvasView, 25L, ToneGenerator.TONE_PROP_BEEP, 45);
            speak("Menu: " + optionName + ". Vuốt chấm 6 sang trái để thực hiện.", true);

        } else if (ACT_EXEC_SELECTION_MENU.equals(actionCode)) {
            executeSelectionMenuItem(currentSelectionMenuIndex);

        } else if (ACT_TOGGLE_PRIVACY.equals(actionCode)) {
            privacyScreenActive = !privacyScreenActive;
            savePhoneOptions();
            if (brailleCanvasView != null) brailleCanvasView.invalidate();
            speak(privacyScreenActive ? "Màn hình tối" : "Màn hình sáng", true);

        } else if (ACT_CYCLE_FEEDBACK.equals(actionCode)) {
            feedbackMode = (feedbackMode + 1) % 4;
            savePhoneOptions();
            String[] names = new String[]{"Rung và Âm thanh", "Chỉ Rung", "Chỉ Âm thanh", "Tắt phản hồi"};
            speak("Phản hồi bàn phím: " + names[feedbackMode], true);

        } else if (ACT_CYCLE_ECHO.equals(actionCode)) {
            echoMode = BrailleKeyboardEchoSettings.cycle(this);
            speak("Chế độ đọc vọng: "
                    + KeyboardEchoSettings.choiceName(getResources(), echoMode), true);

        } else if (ACT_START_CALIBRATION.equals(actionCode)) {
            if (brailleCanvasView != null) {
                brailleCanvasView.startTwoTouchCalibration();
            }

        } else if (ACT_TOGGLE_EMOJI.equals(actionCode)) {
            speak("Chế độ biểu tượng cảm xúc chỉ dùng được khi gõ bằng bàn phím chữ nổi trong ứng dụng khác.", true);

        } else if (ACT_EXIT_KEYBOARD.equals(actionCode)) {
            exitFullScreenKeyboardMode();
            return;
        }

        updateVirtualInputDisplay();
    }

    private void executeSelectionMenuItem(int menuIdx) {
        int start = (markPos >= 0) ? Math.min(markPos, cursorPos) : 0;
        int end = (markPos >= 0) ? Math.max(markPos, cursorPos) : textBuffer.length();
        start = Math.max(0, Math.min(start, textBuffer.length()));
        end = Math.max(0, Math.min(end, textBuffer.length()));

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        switch (menuIdx) {
            case 0: {
                String sub = textBuffer.substring(start, end);
                speak(sub.length() > 0 ? ("Vùng chọn: " + sub) : "Chưa có văn bản trong vùng chọn", true);
                break;
            }
            case 1: {
                String sub = textBuffer.substring(start, end);
                if (sub.length() > 0 && clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("BrailleText", sub));
                    speak("Đã sao chép: " + sub, true);
                } else {
                    speak("Không có nội dung để sao chép", true);
                }
                break;
            }
            case 2: {
                String sub = textBuffer.substring(start, end);
                if (sub.length() > 0 && clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("BrailleText", sub));
                    textBuffer.delete(start, end);
                    cursorPos = start;
                    markPos = -1;
                    speak("Đã cắt: " + sub, true);
                } else {
                    speak("Không có nội dung để cắt", true);
                }
                break;
            }
            case 3: {
                if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                    CharSequence pasted = clipboard.getPrimaryClip().getItemAt(0).getText();
                    if (pasted != null && pasted.length() > 0) {
                        insertTextAtCursor(pasted.toString());
                        speak("Đã dán: " + pasted, true);
                        break;
                    }
                }
                speak("Bộ nhớ tạm đang trống", true);
                break;
            }
            case 4: {
                if (end > start) {
                    String deleted = textBuffer.substring(start, end);
                    textBuffer.delete(start, end);
                    cursorPos = start;
                    markPos = -1;
                    speak("Đã xóa vùng chọn: " + deleted, true);
                } else {
                    speak("Chưa chọn vùng văn bản nào", true);
                }
                break;
            }
            case 5: {
                markPos = 0;
                cursorPos = textBuffer.length();
                speak("Đã chọn toàn bộ văn bản", true);
                break;
            }
            case 6: {
                exitFullScreenKeyboardMode();
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

    private String getWordBeforeCursor() {
        int end = Math.min(cursorPos, textBuffer.length());
        int start = end;
        while (start > 0 && textBuffer.charAt(start - 1) == ' ') start--;
        int wordEnd = start;
        while (start > 0 && textBuffer.charAt(start - 1) != ' ' && textBuffer.charAt(start - 1) != '\n') start--;
        return textBuffer.substring(start, wordEnd);
    }

    private String getWordAroundCursor() {
        if (textBuffer.length() == 0) return "";
        int pos = Math.min(cursorPos, textBuffer.length() - 1);
        if (pos < 0) return "";
        if (textBuffer.charAt(pos) == ' ' && pos > 0) pos--;
        int start = pos;
        while (start > 0 && textBuffer.charAt(start - 1) != ' ' && textBuffer.charAt(start - 1) != '\n') start--;
        int end = pos;
        while (end < textBuffer.length() && textBuffer.charAt(end) != ' ' && textBuffer.charAt(end) != '\n') end++;
        return textBuffer.substring(start, end).trim();
    }

    private int getLineStart(int pos) {
        int p = Math.max(0, Math.min(pos, textBuffer.length()));
        while (p > 0 && textBuffer.charAt(p - 1) != '\n') p--;
        return p;
    }

    private int getLineEnd(int pos) {
        int p = Math.max(0, Math.min(pos, textBuffer.length()));
        while (p < textBuffer.length() && textBuffer.charAt(p) != '\n') p++;
        return p;
    }

    private String getCurrentLineText() {
        int s = getLineStart(cursorPos);
        int e = getLineEnd(cursorPos);
        return textBuffer.substring(s, e);
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

    private class BraillePhoneCanvasView extends View {

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
                    addDotToPendingChord(hoverCurrentDot, BraillePhoneCanvasView.this);
                }
            }
        };

        public BraillePhoneCanvasView(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);

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
            speak("Chế độ hiệu chuẩn 2 lần chạm. Bước 1: Hãy đặt 3 ngón tay chấm 1, 2, 3 lên màn hình và giữ nguyên 1 phẩy 5 giây rồi nhấc lên.", true);
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
                speak("Đã ghi nhận vị trí chấm 1, 2, 3. Hãy nhấc tay lên.", true);
            } else {
                speak("Đã ghi nhận vị trí chấm 4, 5, 6. Hãy nhấc tay lên để hoàn tất.", true);
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
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER) {
                MotionEvent down = MotionEvent.obtain(event.getDownTime(), event.getEventTime(),
                        MotionEvent.ACTION_DOWN, event.getX(), event.getY(), event.getMetaState());
                onTouchEvent(down);
                down.recycle();
                return true;
            } else if (action == MotionEvent.ACTION_HOVER_MOVE) {
                MotionEvent move = MotionEvent.obtain(event.getDownTime(), event.getEventTime(),
                        MotionEvent.ACTION_MOVE, event.getX(), event.getY(), event.getMetaState());
                onTouchEvent(move);
                move.recycle();
                return true;
            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                MotionEvent up = MotionEvent.obtain(event.getDownTime(), event.getEventTime(),
                        MotionEvent.ACTION_UP, event.getX(), event.getY(), event.getMetaState());
                onTouchEvent(up);
                up.recycle();
                return true;
            }
            return super.onHoverEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
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

                            if (calibrationStep == CAL_NONE && !chordCommittedOnTouch
                                    && (Math.abs(dx) > dp(24) || Math.abs(dy) > dp(24))) {
                                
                            }

                            if (calibrationStep == CAL_NONE && !dotCommittedDuringExplore
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
                    activeFingers.remove(pid);
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
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
                speak("Bước 2: Hãy đặt tiếp 3 ngón tay chấm 4, 5, 6 lên màn hình và giữ 1 phẩy 5 giây.", false);
            } else if (calibrationStep == CAL_STEP_456) {
                calibrationStep = CAL_NONE;
                useCalibratedPoints = true;
                calibrationDirty = true;
                savePhoneOptions();
                speak("Đã hoàn tất hiệu chuẩn 6 ngón tay. Bạn có thể bắt đầu gõ ngay.", false);
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
protected void onResume() {
    super.onResume();
    if (!BrailleMeridianBridge.isAvailable()) finish();
}

    @Override
protected void onDestroy() {
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        && brailleBackCallback != null) {
    getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(brailleBackCallback);
    brailleBackCallback = null;
}
mainHandler.removeCallbacks(singleFingerCommitRunnable);
if (toneGenerator != null) {
try {
toneGenerator.release();
} catch (Throwable ignored) {
}
}
releaseCustomSoundPlayer();
releaseSlot(capitalSlot);
releaseSlot(spaceSlot);
super.onDestroy();
}
}
