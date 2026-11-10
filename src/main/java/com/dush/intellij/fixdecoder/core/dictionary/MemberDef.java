package com.dush.intellij.fixdecoder.core.dictionary;

/** A field inside a header, trailer, message or group. When it is the count field of a repeating group, {@link #group()} is set. */
public final class MemberDef {
    private final FieldDef field;
    private final boolean required;
    private final GroupDef group;

    public MemberDef(FieldDef field, boolean required, GroupDef group) {
        this.field = field;
        this.required = required;
        this.group = group;
    }

    public FieldDef field() {
        return field;
    }

    public int tag() {
        return field.number();
    }

    public boolean required() {
        return required;
    }

    /** The repeating group this field counts, or null for an ordinary field. */
    public GroupDef group() {
        return group;
    }
}
