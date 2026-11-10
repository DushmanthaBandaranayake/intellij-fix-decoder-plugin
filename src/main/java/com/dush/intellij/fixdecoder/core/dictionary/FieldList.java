package com.dush.intellij.fixdecoder.core.dictionary;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An ordered list of members (components already flattened in), with lookup by tag. */
public class FieldList {
    private final List<MemberDef> members;
    private final Map<Integer, Integer> indexByTag = new HashMap<>();

    public FieldList(List<MemberDef> members) {
        this.members = Collections.unmodifiableList(members);
        for (int i = 0; i < members.size(); i++) {
            indexByTag.putIfAbsent(members.get(i).tag(), i);
        }
    }

    public List<MemberDef> members() {
        return members;
    }

    public MemberDef member(int tag) {
        Integer i = indexByTag.get(tag);
        return i == null ? null : members.get(i);
    }

    /** Position of the tag in this list, or -1. */
    public int indexOf(int tag) {
        Integer i = indexByTag.get(tag);
        return i == null ? -1 : i;
    }

    public boolean contains(int tag) {
        return indexByTag.containsKey(tag);
    }
}
