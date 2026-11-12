package com.dush.intellij.fixdecoder.core.dictionary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A FIX data dictionary: fields, header, trailer and messages, with components already flattened.
 * Instances are not changed after they are built; {@link #combine} returns a new one.
 */
public final class Dictionary {
    private final String beginString;
    private final String label;
    private final Map<Integer, FieldDef> fields;
    private final Map<String, FieldDef> fieldsByName;
    private final Map<String, MessageDef> messages;
    private final Map<String, List<MemberDef>> components;
    private final FieldList header;
    private final FieldList trailer;
    /** Count tag to every distinct group definition with that count tag, for messages whose type is unknown. */
    private final Map<Integer, List<GroupDef>> groupsByCount = new HashMap<>();

    Dictionary(String beginString, String label, Map<Integer, FieldDef> fields, Map<String, MessageDef> messages,
               Map<String, List<MemberDef>> components, FieldList header, FieldList trailer) {
        this.beginString = beginString;
        this.label = label;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        Map<String, FieldDef> byName = new HashMap<>();
        for (FieldDef f : fields.values()) byName.put(f.name(), f);
        this.fieldsByName = Collections.unmodifiableMap(byName);
        this.messages = Collections.unmodifiableMap(new LinkedHashMap<>(messages));
        this.components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
        this.header = header;
        this.trailer = trailer;
        collectGroups(header);
        collectGroups(trailer);
        for (MessageDef m : messages.values()) collectGroups(m);
    }

    private void collectGroups(FieldList list) {
        for (MemberDef m : list.members()) {
            if (m.group() != null) {
                List<GroupDef> defs = groupsByCount.computeIfAbsent(m.tag(), k -> new ArrayList<>());
                if (defs.stream().noneMatch(d -> sameMembers(d, m.group()))) defs.add(m.group());
                collectGroups(m.group());
            }
        }
    }

    private static boolean sameMembers(GroupDef a, GroupDef b) {
        if (a.members().size() != b.members().size()) return false;
        for (int i = 0; i < a.members().size(); i++) {
            if (a.members().get(i).tag() != b.members().get(i).tag()) return false;
        }
        return true;
    }

    /** BeginString value this dictionary is for, e.g. {@code FIX.4.4} or {@code FIXT.1.1}. Empty for a partial custom file. */
    public String beginString() {
        return beginString;
    }

    /** Short readable name, e.g. "FIX 4.4" or "FIXT 1.1 + FIX 5.0 SP2 + venue.xml". */
    public String label() {
        return label;
    }

    public FieldDef field(int tag) {
        return fields.get(tag);
    }

    public FieldDef field(String name) {
        return fieldsByName.get(name);
    }

    public Collection<FieldDef> fields() {
        return fields.values();
    }

    public MessageDef message(String msgType) {
        return messages.get(msgType);
    }

    public Collection<MessageDef> messages() {
        return messages.values();
    }

    public FieldList header() {
        return header;
    }

    public FieldList trailer() {
        return trailer;
    }

    /**
     * For a message whose type is unknown: the group definition with this count tag that fits the field that follows
     * the count (its first field, or else any of its fields); the first one found when none fits; null when no message
     * has such a group. Messages define the same count tag differently (NoMDEntries starts with 269 in a snapshot and
     * with 279 in an incremental refresh), so the next field decides.
     */
    public GroupDef groupFor(int countTag, int nextTag) {
        List<GroupDef> defs = groupsByCount.get(countTag);
        if (defs == null || defs.isEmpty()) return null;
        for (GroupDef d : defs) if (d.delimiterTag() == nextTag) return d;
        for (GroupDef d : defs) if (d.contains(nextTag)) return d;
        return defs.get(0);
    }


    /** FIXT: the session layer (header, trailer, admin messages) from {@code transport}, everything else from {@code app}. */
    public static Dictionary combine(Dictionary transport, Dictionary app) {
        Map<Integer, FieldDef> f = new LinkedHashMap<>(transport.fields);
        f.putAll(app.fields);
        Map<String, MessageDef> m = new LinkedHashMap<>(app.messages);
        m.putAll(transport.messages);
        Map<String, List<MemberDef>> c = new LinkedHashMap<>(transport.components);
        c.putAll(app.components);
        return new Dictionary(transport.beginString, transport.label + " + " + app.label, f, m, c, transport.header, transport.trailer);
    }

    /** Number of fields, for tests and diagnostics. */
    public int fieldCount() {
        return fields.size();
    }
}
