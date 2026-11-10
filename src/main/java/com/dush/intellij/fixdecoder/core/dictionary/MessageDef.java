package com.dush.intellij.fixdecoder.core.dictionary;

import java.util.List;

/** One message type: its MsgType (35) value, name and body fields. */
public final class MessageDef extends FieldList {
    private final String msgType;
    private final String name;
    private final String category;

    public MessageDef(String msgType, String name, String category, List<MemberDef> members) {
        super(members);
        this.msgType = msgType;
        this.name = name;
        this.category = category == null ? "" : category;
    }

    public String msgType() {
        return msgType;
    }

    public String name() {
        return name;
    }

    /** {@code admin} or {@code app}. */
    public String category() {
        return category;
    }
}
