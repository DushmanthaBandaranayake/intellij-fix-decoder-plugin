package com.dush.intellij.fixdecoder.core.parse;

/** One {@code tag=value} field as written, with its position in the message text. */
public final class RawField {
    private final int index;
    private final int tag;
    private final String value;
    private final int start;
    private final int valueStart;
    private final int end;
    private final int delimiterEnd;

    public RawField(int index, int tag, String value, int start, int valueStart, int end, int delimiterEnd) {
        this.index = index;
        this.tag = tag;
        this.value = value;
        this.start = start;
        this.valueStart = valueStart;
        this.end = end;
        this.delimiterEnd = delimiterEnd;
    }

    /** Position of this field in the message, from 0. */
    public int index() {
        return index;
    }

    public int tag() {
        return tag;
    }

    public String value() {
        return value;
    }

    /** Offset of the first digit of the tag. */
    public int start() {
        return start;
    }

    /** Offset just after the {@code =}. */
    public int valueStart() {
        return valueStart;
    }

    /** Offset just after the value. */
    public int end() {
        return end;
    }

    /** Offset just after the delimiter that follows the value; equal to {@link #end()} when there is none. */
    public int delimiterEnd() {
        return delimiterEnd;
    }

    public boolean hasDelimiter() {
        return delimiterEnd > end;
    }

    @Override
    public String toString() {
        return tag + "=" + value;
    }
}
