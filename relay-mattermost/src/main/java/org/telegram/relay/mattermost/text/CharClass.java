package org.telegram.relay.mattermost.text;

/**
 * Character classes of the markdown parser, over code points. {@code -1} stands for "no character"
 * (start or end of the text). Matches the Swift parser's Unicode properties.
 */
final class CharClass {
    private CharClass() {
    }

    static final int NONE = -1;

    static boolean isASCIILetter(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    static boolean isASCIIDigit(int c) {
        return c >= '0' && c <= '9';
    }

    static boolean isASCIIAlphanumeric(int c) {
        return isASCIILetter(c) || isASCIIDigit(c);
    }

    static boolean isASCIIPunctuation(int c) {
        return (c >= 0x21 && c <= 0x2F) || (c >= 0x3A && c <= 0x40) || (c >= 0x5B && c <= 0x60) || (c >= 0x7B && c <= 0x7E);
    }

    /** Unicode {@code White_Space}. {@link #NONE} (start or end of the text) counts as whitespace, as in CommonMark. */
    static boolean isWhitespace(int c) {
        if (c == NONE) {
            return true;
        }
        return isWhiteSpaceProperty(c);
    }

    /** Unicode {@code White_Space} (no special case for {@link #NONE}). */
    static boolean isWhiteSpaceProperty(int c) {
        return (c >= 0x09 && c <= 0x0D) || c == 0x20 || c == 0x85 || c == 0xA0 || c == 0x1680
            || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
    }

    /** CommonMark "Unicode punctuation": ASCII punctuation or general category P* / S*. */
    static boolean isPunctuation(int c) {
        if (c == NONE) {
            return false;
        }
        if (c < 0x80) {
            return isASCIIPunctuation(c);
        }
        switch (Character.getType(c)) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
            case Character.MATH_SYMBOL:
            case Character.CURRENCY_SYMBOL:
            case Character.MODIFIER_SYMBOL:
            case Character.OTHER_SYMBOL:
                return true;
            default:
                return false;
        }
    }

    static boolean isAlphabetic(int c) {
        return c != NONE && Character.isAlphabetic(c);
    }

    /** Has a numeric type (general category Nd, Nl or No). */
    static boolean isNumeric(int c) {
        if (c == NONE) {
            return false;
        }
        int type = Character.getType(c);
        return type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER;
    }

    static boolean isNonspacingMark(int c) {
        return c != NONE && Character.getType(c) == Character.NON_SPACING_MARK;
    }

    static boolean isControl(int c) {
        return c != NONE && Character.getType(c) == Character.CONTROL;
    }

    /** Swift's {@code CharacterSet.whitespaces}: general category Zs plus tab. */
    static boolean isInlineWhitespace(int c) {
        return c == '\t' || (c != NONE && Character.getType(c) == Character.SPACE_SEPARATOR);
    }

    /** Trims {@link #isInlineWhitespace} from both ends. */
    static String trimInlineWhitespace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end) {
            int c = s.codePointAt(start);
            if (!isInlineWhitespace(c)) {
                break;
            }
            start += Character.charCount(c);
        }
        while (end > start) {
            int c = s.codePointBefore(end);
            if (!isInlineWhitespace(c)) {
                break;
            }
            end -= Character.charCount(c);
        }
        return s.substring(start, end);
    }

    /** Trims {@link #isWhiteSpaceProperty} (whitespace and newlines) from both ends. */
    static String trimWhitespaceAndNewlines(String s) {
        int start = 0;
        int end = s.length();
        while (start < end) {
            int c = s.codePointAt(start);
            if (!isWhiteSpaceProperty(c)) {
                break;
            }
            start += Character.charCount(c);
        }
        while (end > start) {
            int c = s.codePointBefore(end);
            if (!isWhiteSpaceProperty(c)) {
                break;
            }
            end -= Character.charCount(c);
        }
        return s.substring(start, end);
    }

    static int[] codePoints(String s) {
        int[] result = new int[s.codePointCount(0, s.length())];
        int index = 0;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            result[index++] = c;
            i += Character.charCount(c);
        }
        return result;
    }

    static String string(int[] codePoints, int start, int end) {
        return new String(codePoints, start, end - start);
    }

    /**
     * Approximate number of user-perceived characters (grapheme clusters), for aligning table
     * columns: combining marks, variation selectors, skin tone modifiers, ZWJ-joined parts and the
     * second regional indicator of a flag do not count. Deterministic on every JVM and Android version.
     */
    static int displayLength(String s) {
        int count = 0;
        boolean afterJoiner = false;
        boolean openFlag = false;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c == 0x200D) {
                afterJoiner = true;
                continue;
            }
            int type = Character.getType(c);
            boolean extending = type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK
                || (c >= 0xFE00 && c <= 0xFE0F) || (c >= 0x1F3FB && c <= 0x1F3FF) || (c >= 0xE0020 && c <= 0xE007F);
            if (extending) {
                continue;
            }
            if (afterJoiner) {
                afterJoiner = false;
                continue;
            }
            if (c >= 0x1F1E6 && c <= 0x1F1FF) {
                if (openFlag) {
                    openFlag = false;
                    continue;
                }
                openFlag = true;
            } else {
                openFlag = false;
            }
            count++;
        }
        return count;
    }
}
