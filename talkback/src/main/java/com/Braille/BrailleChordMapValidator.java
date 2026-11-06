package com.Braille;

import java.util.Map;

public final class BrailleChordMapValidator {
    private BrailleChordMapValidator() {}

    public static String normalizeDots(String raw) {
        if (raw == null || raw.length() == 0) return "";
        boolean[] present = new boolean[7];
        for (int i = 0; i < raw.length(); i++) {
            char dot = raw.charAt(i);
            if (dot < '1' || dot > '6') return "";
            present[dot - '0'] = true;
        }
        StringBuilder result = new StringBuilder(6);
        for (int dot = 1; dot <= 6; dot++) {
            if (present[dot]) result.append(dot);
        }
        return result.toString();
    }

    public static String findConflict(Map<String, String> entries, String dots, String exceptKey) {
        String normalized = normalizeDots(dots);
        if (normalized.length() == 0) return null;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (entry.getKey().equals(exceptKey)) continue;
            if (normalized.equals(normalizeDots(entry.getValue()))) return entry.getKey();
        }
        return null;
    }

    public static boolean putIfUnique(Map<String, String> entries, String key, String dots) {
        String normalized = normalizeDots(dots);
        if (key == null || key.length() == 0 || normalized.length() == 0
                || findConflict(entries, normalized, key) != null) return false;
        entries.put(key, normalized);
        return true;
    }
}
