package com.Braille;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BrailleSpeedSettingsTest {
    @Test
    public void defaultDelayAndLegacyRange() {
        SharedPreferences p = RuntimeEnvironment.getApplication()
                .getSharedPreferences("braille_speed_test", Context.MODE_PRIVATE);
        p.edit().clear().commit();
        assertEquals(50, BrailleSpeedSettings.letterDelay(p));
        p.edit().putInt(BrailleSpeedSettings.KEY_LETTER_DELAY, 500).commit();
        assertEquals(300, BrailleSpeedSettings.letterDelay(p));
        p.edit().putInt(BrailleSpeedSettings.KEY_LETTER_DELAY, 0).commit();
        assertEquals(1, BrailleSpeedSettings.letterDelay(p));
    }

    @Test
    public void independentlyAdjustsSwipeDistanceAndTime() {
        assertFalse(BrailleSpeedSettings.isSwipe(40, 0, 200, 1000, 68));
        assertTrue(BrailleSpeedSettings.isSwipe(40, 0, 200, 1000, 24));
        assertFalse(BrailleSpeedSettings.isSwipe(80, 0, 1000, 1000, 68));
        assertTrue(BrailleSpeedSettings.isSwipe(80, 0, 999, 1000, 68));
        assertFalse(BrailleSpeedSettings.isLongSwipe(90, 0, 160));
        assertTrue(BrailleSpeedSettings.isLongSwipe(160, 0, 160));
    }

    @Test
    public void independentLongSwipeMappingFallsBackWhenNotSet() {
        Map<String, String> m = new HashMap<>();
        m.put("DOT4_UP", "BACKSPACE");
        assertEquals("BACKSPACE", BrailleSpeedSettings.swipeAction(m, "DOT4_UP", true, true));
        m.put("DOT4_UP_LONG", "DELETE_WORD");
        assertEquals("BACKSPACE", BrailleSpeedSettings.swipeAction(m, "DOT4_UP", false, true));
        assertEquals("BACKSPACE", BrailleSpeedSettings.swipeAction(m, "DOT4_UP", true, false));
        assertEquals("DELETE_WORD", BrailleSpeedSettings.swipeAction(m, "DOT4_UP", true, true));
    }
}
