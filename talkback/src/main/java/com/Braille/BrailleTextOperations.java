package com.Braille;

import java.text.BreakIterator;
import java.util.Locale;

public final class BrailleTextOperations {
    private BrailleTextOperations() {}

    public static final class Range {
        public final int start;
        public final int end;

        public Range(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public boolean isEmpty() {
            return start >= end;
        }
    }

    public static int lineStart(String text, int position) {
        int start = Math.max(0, Math.min(position, text.length()));
        while (start > 0 && text.charAt(start - 1) != '\n') start--;
        return start;
    }

    public static int lineEnd(String text, int position) {
        int end = Math.max(0, Math.min(position, text.length()));
        while (end < text.length() && text.charAt(end) != '\n') end++;
        return end;
    }

    public static Range characterAt(String text, int cursor, boolean preceding) {
        if (text.isEmpty()) return new Range(0, 0);
        int position = Math.max(0, Math.min(cursor, text.length()));
        BreakIterator boundaries = BreakIterator.getCharacterInstance(new Locale("vi", "VN"));
        boundaries.setText(text);
        if (preceding || position == text.length()) {
            if (position == 0) return new Range(0, 0);
            int start = boundaries.preceding(position);
            if (start == BreakIterator.DONE) return new Range(0, 0);
            int end = boundaries.following(start);
            return new Range(start, end == BreakIterator.DONE ? text.length() : end);
        }
        int start = boundaries.isBoundary(position) ? position : boundaries.preceding(position);
        if (start == BreakIterator.DONE) start = 0;
        int end = boundaries.following(start);
        return new Range(start, end == BreakIterator.DONE ? text.length() : end);
    }

    public static Range wordAt(String text, int cursor) {
        if (text.isEmpty()) return new Range(0, 0);
        int position = Math.max(0, Math.min(cursor, text.length()));
        if (position == text.length() || Character.isWhitespace(text.charAt(position))) {
            if (position > 0 && !Character.isWhitespace(text.charAt(position - 1))) {
                position--;
            } else {
                while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                    position++;
                }
                if (position == text.length()) return new Range(position, position);
            }
        }
        int start = position;
        int end = position;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        return new Range(start, end);
    }

    public static final class NavigationState {
        private int lastCharacterCursor = -1;
        private boolean previousCharacterIsFocused;
        private int lastBoundaryCursor = -1;
        private boolean lastBoundaryWasEnd;

        public void reset() {
            lastCharacterCursor = -1;
            previousCharacterIsFocused = false;
            lastBoundaryCursor = -1;
            lastBoundaryWasEnd = false;
        }

        public void recordCharacterMovement(int newCursor, boolean forward) {
            lastCharacterCursor = newCursor;
            previousCharacterIsFocused = forward;
            lastBoundaryCursor = -1;
        }

        public Range focusedCharacter(String text, int cursor) {
            return characterAt(text, cursor,
                    lastCharacterCursor == cursor && previousCharacterIsFocused);
        }

        public int nextLineBoundary(String text, int cursor) {
            lastCharacterCursor = -1;
            int start = lineStart(text, cursor);
            int end = lineEnd(text, cursor);
            if (lastBoundaryCursor != cursor) {
                lastBoundaryCursor = start;
                lastBoundaryWasEnd = false;
                return start;
            }
            if (!lastBoundaryWasEnd) {
                lastBoundaryCursor = end;
                lastBoundaryWasEnd = true;
                return end;
            }
            if (end >= text.length()) return end;
            lastBoundaryCursor = end + 1;
            lastBoundaryWasEnd = false;
            return end + 1;
        }
    }
}
