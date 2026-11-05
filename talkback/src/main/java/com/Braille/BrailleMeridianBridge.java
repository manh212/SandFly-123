package com.Braille;

import android.os.Build;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.pause.PauseController;
import com.google.android.accessibility.utils.output.FeedbackItem;
import com.google.android.accessibility.utils.output.SpeechController;

/** The Braille IME may only work while Meridian's screen reader is active. */
public final class BrailleMeridianBridge {
    private BrailleMeridianBridge() {}

    public static boolean isAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && TalkBackService.isServiceActive()
                && !PauseController.isPaused();
    }

    /** Use the existing Meridian speech queue. Never construct a TextToSpeech instance. */
    public static void speak(String text, boolean interrupt) {
        if (!isAvailable() || text == null || text.isEmpty()) return;
        TalkBackService service = TalkBackService.getInstance();
        if (service == null) return;
        SpeechController.SpeakOptions options = SpeechController.SpeakOptions.create()
                .setFlags(FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE
                        | FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_MICROPHONE_ACTIVE)
                .setQueueMode(interrupt
                        ? SpeechController.QUEUE_MODE_INTERRUPT
                        : SpeechController.QUEUE_MODE_QUEUE);
        service.getSpeechController().speak(text, null, options);
    }
}
