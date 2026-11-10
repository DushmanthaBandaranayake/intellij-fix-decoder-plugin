package com.dush.intellij.fixdecoder.core.dictionary;

import java.util.List;

/** A repeating group: its count field (NumInGroup) and the ordered fields of one instance. The first field is the delimiter. */
public final class GroupDef extends FieldList {
    private final FieldDef countField;

    public GroupDef(FieldDef countField, List<MemberDef> members) {
        super(members);
        this.countField = countField;
    }

    public FieldDef countField() {
        return countField;
    }

    public String name() {
        return countField.name();
    }

    /** The field every instance must start with. */
    public FieldDef delimiter() {
        return members().get(0).field();
    }

    public int delimiterTag() {
        return members().get(0).tag();
    }
}
