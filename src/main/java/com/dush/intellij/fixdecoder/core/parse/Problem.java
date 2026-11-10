package com.dush.intellij.fixdecoder.core.parse;

/** Something wrong with a message, with the part of the message text to highlight. */
public final class Problem {

    /** What kind of problem; {@link #category()} decides which inspection reports it. */
    public enum Kind {
        GROUP_COUNT(Category.GROUP),
        GROUP_COUNT_NOT_A_NUMBER(Category.GROUP),
        GROUP_MISSING_DELIMITER(Category.GROUP),
        GROUP_FIELD_ORDER(Category.GROUP),
        GROUP_UNKNOWN_TAG(Category.GROUP),
        BODY_LENGTH(Category.LENGTH_CHECKSUM),
        CHECKSUM(Category.LENGTH_CHECKSUM),
        ENUM_VALUE(Category.ENUM);

        private final Category category;

        Kind(Category category) {
            this.category = category;
        }

        public Category category() {
            return category;
        }
    }

    public enum Category { GROUP, LENGTH_CHECKSUM, ENUM }

    private final Kind kind;
    private final RawField field;
    private final int start;
    private final int end;
    private final String message;
    private final int fixCount;

    Problem(Kind kind, RawField field, int start, int end, String message, int fixCount) {
        this.kind = kind;
        this.field = field;
        this.start = start;
        this.end = end;
        this.message = message;
        this.fixCount = fixCount;
    }

    public Kind kind() {
        return kind;
    }

    public RawField field() {
        return field;
    }

    /** Start of the range to highlight, in the message text. */
    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    public String message() {
        return message;
    }

    /** For {@link Kind#GROUP_COUNT}: the number of instances actually present; otherwise -1. */
    public int fixCount() {
        return fixCount;
    }

    @Override
    public String toString() {
        return kind + " at " + field + ": " + message;
    }
}
