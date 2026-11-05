package com.Braille;

import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

public final class BrailleInputWriter {
    private BrailleInputWriter() {}

    public static boolean commit(InputConnection connection, String text, boolean keyEventsFirst) {
        if (connection == null || text == null) return false;
        if (text.isEmpty()) return true;
        if (keyEventsFirst && sendKeys(connection, text)) return true;
        if (connection.commitText(text, 1)) return true;
        return !keyEventsFirst && sendKeys(connection, text);
    }

    private static boolean sendKeys(InputConnection connection, String text) {
        try {
            KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                    .getEvents(text.toCharArray());
            if (events != null && events.length > 0) {
                boolean acceptedKeyDown = false;
                for (KeyEvent event : events) {
                    boolean accepted = connection.sendKeyEvent(event);
                    if (accepted && event.getAction() == KeyEvent.ACTION_DOWN) {
                        acceptedKeyDown = true;
                    }
                    if (!accepted) return acceptedKeyDown;
                }
                return acceptedKeyDown;
            }
            return connection.sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(),
                    text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
