package com.Braille;

public final class BraillePracticeMode {
    public static final String PREF_KEY = "braille_practice_mode";

    private BraillePracticeMode() {}

    public static String describeDots(String dots) {
        String normalized = BrailleChordMapValidator.normalizeDots(dots);
        if (normalized.length() == 0) return "";
        StringBuilder spoken = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            if (i > 0) spoken.append(", ");
            spoken.append("Chấm ").append(normalized.charAt(i));
        }
        return spoken.toString();
    }
}
