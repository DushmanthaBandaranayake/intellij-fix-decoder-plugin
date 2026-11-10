package com.dush.intellij.fixdecoder.extract;

import com.dush.intellij.fixdecoder.core.parse.FixMessage;
import com.dush.intellij.fixdecoder.core.parse.FixRewriter;
import com.dush.intellij.fixdecoder.core.parse.RawField;
import com.intellij.openapi.util.TextRange;

import java.util.ArrayList;
import java.util.List;

/**
 * A message found in a document: the parsed message, and for each character of its text the range it came from in
 * the document. A character has no range (-1) when it does not appear in the document as written, such as the value
 * of a constant in a Java concatenation.
 */
public final class FoundMessage {

    /** Turns message text into text that can be written into the document at this message (e.g. Java escapes). */
    public interface Escaper {
        String escape(String text);

        Escaper NONE = text -> text;
    }

    private final FixMessage message;
    private final int[] starts;
    private final int[] ends;
    private final Escaper escaper;
    private final boolean inJavaCode;

    public FoundMessage(FixMessage message, int[] starts, int[] ends, Escaper escaper, boolean inJavaCode) {
        this.message = message;
        this.starts = starts;
        this.ends = ends;
        this.escaper = escaper;
        this.inJavaCode = inJavaCode;
    }

    public FixMessage message() {
        return message;
    }

    public Escaper escaper() {
        return escaper;
    }

    /** True when the message is in Java code (a string literal or concatenation) rather than plain text. */
    public boolean inJavaCode() {
        return inJavaCode;
    }

    /** From the first to the last character that has a range; null when none has. Use this to show or highlight. */
    public TextRange displayRange(int from, int to) {
        int s = -1;
        int e = -1;
        for (int i = from; i < to && i < starts.length; i++) {
            if (starts[i] < 0) continue;
            if (s < 0) s = starts[i];
            e = ends[i];
        }
        return s < 0 ? null : new TextRange(s, e);
    }

    public TextRange displayRange() {
        return displayRange(0, starts.length);
    }

    public TextRange tagRange(RawField f) {
        return displayRange(f.start(), f.valueStart() - 1);
    }

    /** The value; for an empty value, the position just after the {@code =}. */
    public TextRange valueRange(RawField f) {
        if (f.end() > f.valueStart()) return displayRange(f.valueStart(), f.end());
        TextRange eq = displayRange(f.valueStart() - 1, f.valueStart());
        return eq == null ? null : new TextRange(eq.getEndOffset(), eq.getEndOffset());
    }

    public TextRange fieldRange(RawField f) {
        return displayRange(f.start(), f.end());
    }

    /**
     * The document range of {@code [from, to)} when every character in it is written in the document one after the other,
     * so the range can be replaced; null otherwise. For an empty range, the position to insert at.
     */
    public TextRange editableRange(int from, int to) {
        if (from == to) {
            if (from < starts.length && starts[from] >= 0) return new TextRange(starts[from], starts[from]);
            if (from > 0 && ends[from - 1] >= 0) return new TextRange(ends[from - 1], ends[from - 1]);
            return null;
        }
        for (int i = from; i < to; i++) {
            if (starts[i] < 0) return null;
            if (i > from && ends[i - 1] != starts[i]) return null;
        }
        return new TextRange(starts[from], ends[to - 1]);
    }

    /** A replacement in the document. */
    public static final class DocEdit {
        public final TextRange range;
        public final String text;

        DocEdit(TextRange range, String text) {
            this.range = range;
            this.text = text;
        }
    }

    /** The edits mapped to the document and escaped, or null when any of them touches text that is not written there. */
    public List<DocEdit> toDocument(List<FixRewriter.Edit> edits) {
        List<DocEdit> out = new ArrayList<>();
        for (FixRewriter.Edit e : edits) {
            TextRange r = editableRange(e.start(), e.end());
            if (r == null) return null;
            out.add(new DocEdit(r, escaper.escape(e.text())));
        }
        return out;
    }

    /** Index into the message text of the character at a document offset, or -1. */
    public int indexAt(int docOffset) {
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] >= 0 && docOffset >= starts[i] && docOffset < ends[i]) return i;
        }
        return -1;
    }

    /** The field at a document offset (the tag, the {@code =}, the value or the delimiter after it), or null. */
    public RawField fieldAt(int docOffset) {
        int i = indexAt(docOffset);
        return i < 0 ? null : message.fieldAt(i);
    }

    public boolean contains(int docOffset) {
        TextRange r = displayRange();
        return r != null && r.containsOffset(docOffset);
    }
}
