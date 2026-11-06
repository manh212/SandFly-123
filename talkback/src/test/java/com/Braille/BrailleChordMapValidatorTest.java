package com.Braille;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import org.junit.Test;

public class BrailleChordMapValidatorTest {
    @Test
    public void normalizeTreatsAnyOrderingAsSameChord() {
        assertEquals("12", BrailleChordMapValidator.normalizeDots("21"));
        assertEquals("123", BrailleChordMapValidator.normalizeDots("3122"));
        assertEquals("", BrailleChordMapValidator.normalizeDots("1a"));
        assertEquals("", BrailleChordMapValidator.normalizeDots("7"));
    }

    @Test
    public void alphabetDoesNotOverwriteExistingChord() {
        LinkedHashMap<String, String> letters = new LinkedHashMap<>();
        letters.put("a", "1");
        assertEquals("a", BrailleChordMapValidator.findConflict(letters, "1", "b"));
        assertFalse(BrailleChordMapValidator.putIfUnique(letters, "b", "1"));
        assertEquals("a", BrailleChordMapValidator.findConflict(letters, "1", "b"));
        assertEquals(1, letters.size());
        assertTrue(BrailleChordMapValidator.putIfUnique(letters, "a", "1"));
        assertTrue(BrailleChordMapValidator.putIfUnique(letters, "b", "21"));
        assertEquals("12", letters.get("b"));
        assertNull(BrailleChordMapValidator.findConflict(letters, "21", "b"));
    }

    @Test
    public void emojiChordsAreUniqueWithinEmojiTable() {
        LinkedHashMap<String, String> emojis = new LinkedHashMap<>();
        emojis.put("😀", "1");
        assertFalse(BrailleChordMapValidator.putIfUnique(emojis, "😢", "1"));
        assertEquals("😀", BrailleChordMapValidator.findConflict(emojis, "1", "😢"));
        assertTrue(BrailleChordMapValidator.putIfUnique(emojis, "😢", "2"));
    }

    @Test
    public void practiceModeSpeaksEachDotInOrder() {
        assertEquals("Chấm 1, Chấm 3, Chấm 5", BraillePracticeMode.describeDots("531"));
        assertEquals("", BraillePracticeMode.describeDots(""));
        assertEquals("", BraillePracticeMode.describeDots("7"));
    }
}
