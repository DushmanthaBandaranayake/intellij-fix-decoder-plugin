package com.dush.intellij.fixdecoder.core.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Finds FIX messages in arbitrary text and splits them into fields.
 * <p>
 * A message either starts with {@code 8=FIX} and has at least two fields, or is a "fragment" of at least three fields
 * where most tags are known to the dictionaries. All fields of one message use the same delimiter: SOH,
 * {@code ^A}, {@code |} or {@code ^}. A message never spans a line break, and ends after its CheckSum (10) field.
 */
public final class FixScanner {
    public static final String SOH = "\u0001";
    /** Stands for a part of the message that is not known, such as a method call in a Java concatenation. */
    public static final char UNKNOWN = '…';

    private static final int MAX_TAG_DIGITS = 6;

    private FixScanner() {
    }

    /** A message found in a text: its range and fields (with offsets into the whole text). */
    public static final class Span {
        private final int start;
        private final int end;
        private final String delimiter;
        private final List<RawField> fields;

        Span(int start, int end, String delimiter, List<RawField> fields) {
            this.start = start;
            this.end = end;
            this.delimiter = delimiter;
            this.fields = fields;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }

        public String delimiter() {
            return delimiter;
        }

        public List<RawField> fields() {
            return fields;
        }
    }

    /** Every message in {@code text}, in order and not overlapping. */
    public static List<Span> scan(CharSequence text, IntPredicate knownTag) {
        List<Span> out = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (isDigit(text.charAt(i)) && canStartAt(text, i)) {
                Span span = tryAt(text, i, knownTag);
                if (span != null) {
                    out.add(span);
                    i = span.end();
                    continue;
                }
            }
            i++;
        }
        return out;
    }

    /** The first message in {@code text}, or null. */
    public static Span first(CharSequence text, IntPredicate knownTag) {
        int n = text.length();
        for (int i = 0; i < n; i++) {
            if (isDigit(text.charAt(i)) && canStartAt(text, i)) {
                Span span = tryAt(text, i, knownTag);
                if (span != null) return span;
            }
        }
        return null;
    }

    private static boolean canStartAt(CharSequence text, int i) {
        if (i == 0) return true;
        char p = text.charAt(i - 1);
        return !Character.isLetterOrDigit(p) && p != '=' && p != '.' && p != '_' && p != '-';
    }

    private static Span tryAt(CharSequence text, int start, IntPredicate knownTag) {
        int n = text.length();
        int eq = tagEnd(text, start);
        if (eq < 0) return null;
        // The first delimiter-like character after the first value decides the delimiter.
        int p = eq + 1;
        while (p < n && !isDelimiterChar(text.charAt(p)) && !isLineBreak(text.charAt(p))) p++;
        if (p >= n || isLineBreak(text.charAt(p))) return null;
        String delimiter = delimiterAt(text, p);

        List<RawField> fields = new ArrayList<>();
        int pos = start;
        int end = start;
        while (pos < n) {
            int e = tagEnd(text, pos);
            if (e < 0) break;
            int tag = Integer.parseInt(text.subSequence(pos, e).toString());
            int valueStart = e + 1;
            int valueEnd = valueStart;
            while (valueEnd < n && !startsWith(text, valueEnd, delimiter) && !isLineBreak(text.charAt(valueEnd))) valueEnd++;
            if (tag == 10) valueEnd = Math.min(valueEnd, checksumEnd(text, valueStart, valueEnd));
            boolean delimited = startsWith(text, valueEnd, delimiter);
            if (!delimited && !SOH.equals(delimiter)) {
                // The last field has no delimiter after it: in text such as a log line, the value ends at a space.
                int ws = valueStart;
                while (ws < valueEnd && !Character.isWhitespace(text.charAt(ws))) ws++;
                valueEnd = ws;
            }
            int delimiterEnd = delimited ? valueEnd + delimiter.length() : valueEnd;
            fields.add(new RawField(fields.size(), tag, text.subSequence(valueStart, valueEnd).toString(), pos, valueStart, valueEnd, delimiterEnd));
            end = delimiterEnd;
            if (!delimited || tag == 10) break;
            pos = delimiterEnd;
        }
        if (!accept(fields, knownTag)) return null;
        return new Span(start, end, delimiter, fields);
    }

    private static boolean accept(List<RawField> fields, IntPredicate knownTag) {
        if (fields.size() >= 2 && fields.get(0).tag() == 8 && fields.get(0).value().startsWith("FIX")) return true;
        if (fields.size() < 3) return false;
        int known = 0;
        for (RawField f : fields) if (knownTag.test(f.tag())) known++;
        return known * 3 >= fields.size() * 2;
    }

    /** A CheckSum value is three digits; anything after that (in plain text) is not part of the message. */
    private static int checksumEnd(CharSequence text, int valueStart, int valueEnd) {
        int i = valueStart;
        while (i < valueEnd && i - valueStart < 3 && isDigit(text.charAt(i))) i++;
        return i == valueStart ? valueEnd : i;
    }

    /** Offset of the {@code =} after a tag that starts at {@code pos}, or -1. */
    private static int tagEnd(CharSequence text, int pos) {
        int n = text.length();
        int i = pos;
        while (i < n && i - pos <= MAX_TAG_DIGITS && isDigit(text.charAt(i))) i++;
        if (i == pos || i - pos > MAX_TAG_DIGITS || i >= n || text.charAt(i) != '=') return -1;
        if (text.charAt(pos) == '0') return -1;
        return i;
    }

    private static String delimiterAt(CharSequence text, int p) {
        char c = text.charAt(p);
        if (c == '\u0001') return SOH;
        if (c == '^') return p + 1 < text.length() && text.charAt(p + 1) == 'A' ? "^A" : "^";
        return "|";
    }

    private static boolean isDelimiterChar(char c) {
        return c == '\u0001' || c == '|' || c == '^';
    }

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean startsWith(CharSequence text, int at, String s) {
        if (at + s.length() > text.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (text.charAt(at + i) != s.charAt(i)) return false;
        }
        return true;
    }
}
