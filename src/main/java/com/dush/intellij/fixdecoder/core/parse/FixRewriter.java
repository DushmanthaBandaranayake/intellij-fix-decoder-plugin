package com.dush.intellij.fixdecoder.core.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Computes changes to a message as edits of its text. The IDE side maps each edit back to the document,
 * which keeps every other character (and the escapes in a Java literal) as it was.
 */
public final class FixRewriter {

    private FixRewriter() {
    }

    /** Replace {@code [start, end)} of the message text with {@code text} (an insertion when start == end). */
    public static final class Edit {
        private final int start;
        private final int end;
        private final String text;

        public Edit(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }

        public String text() {
            return text;
        }

        @Override
        public String toString() {
            return "[" + start + "," + end + ")->" + text;
        }
    }

    /** Applies edits to a string (for tests and for text that is not in a document). */
    public static String apply(String text, List<Edit> edits) {
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort((a, b) -> Integer.compare(b.start, a.start));
        StringBuilder sb = new StringBuilder(text);
        for (Edit e : sorted) sb.replace(e.start, e.end, e.text);
        return sb.toString();
    }

    /** True when BodyLength and CheckSum can be computed: a complete message with no unknown parts. */
    public static boolean canFixLengthAndChecksum(FixMessage m) {
        return m.isComplete() && !m.hasUnknownParts();
    }

    /**
     * Edits that make BodyLength (9) and CheckSum (10) correct. A missing 9 is inserted after 8, and a missing 10 is
     * appended. Returns an empty list when both are already right, or when the message is incomplete.
     */
    public static List<Edit> fixLengthAndChecksum(FixMessage m) {
        if (!canFixLengthAndChecksum(m)) return Collections.emptyList();
        List<RawField> fields = m.fields();
        String d = m.delimiter();
        List<Edit> edits = new ArrayList<>();
        String length = String.valueOf(FixValidator.bodyLength(fields));
        RawField nine = fields.size() > 1 && fields.get(1).tag() == 9 ? fields.get(1) : null;
        if (nine == null) {
            RawField eight = fields.get(0);
            String prefix = eight.hasDelimiter() ? "" : d;
            edits.add(new Edit(eight.delimiterEnd(), eight.delimiterEnd(), prefix + "9=" + length + (eight.hasDelimiter() ? d : "")));
        } else if (!nine.value().equals(length)) {
            edits.add(new Edit(nine.valueStart(), nine.end(), length));
        }
        RawField ten = FixValidator.checksumField(fields);
        String sum = FixValidator.checksum(fields, ten, length);
        if (nine == null) {
            // the inserted 9 field adds its bytes, and one delimiter
            sum = checksumWithInserted(fields, ten, "9=" + length);
        }
        if (ten == null) {
            RawField last = fields.get(fields.size() - 1);
            String prefix = last.hasDelimiter() ? "" : d;
            edits.add(new Edit(last.delimiterEnd(), last.delimiterEnd(), prefix + "10=" + sum + d));
        } else if (!ten.value().equals(sum)) {
            edits.add(new Edit(ten.valueStart(), ten.end(), sum));
        }
        return edits;
    }

    private static String checksumWithInserted(List<RawField> fields, RawField ten, String inserted) {
        int base = Integer.parseInt(FixValidator.checksum(fields, ten, null));
        int extra = 1;
        for (byte b : inserted.getBytes(java.nio.charset.StandardCharsets.UTF_8)) extra += b & 0xFF;
        return String.format("%03d", (base + extra) % 256);
    }

    /** Edit that sets a group's count field to {@code count}. */
    public static Edit setCount(RawField countField, int count) {
        return new Edit(countField.valueStart(), countField.end(), String.valueOf(count));
    }

    /** Edits that replace every delimiter of the message with {@code to}. Empty when it already uses it. */
    public static List<Edit> convertDelimiter(FixMessage m, String to) {
        if (m.delimiter().equals(to)) return Collections.emptyList();
        List<Edit> edits = new ArrayList<>();
        for (RawField f : m.fields()) {
            if (f.hasDelimiter()) edits.add(new Edit(f.end(), f.delimiterEnd(), to));
        }
        return edits;
    }

    /** The message alone, with {@code |} as delimiter, for copying. */
    public static String readable(FixMessage m) {
        StringBuilder sb = new StringBuilder();
        for (RawField f : m.fields()) {
            sb.append(f.tag()).append('=').append(f.value());
            if (f.hasDelimiter()) sb.append('|');
        }
        return sb.toString();
    }

    /** The message alone, with SOH as delimiter. */
    public static String withSoh(FixMessage m) {
        StringBuilder sb = new StringBuilder();
        for (RawField f : m.fields()) {
            sb.append(f.tag()).append('=').append(f.value());
            if (f.hasDelimiter()) sb.append(FixScanner.SOH);
        }
        return sb.toString();
    }
}
