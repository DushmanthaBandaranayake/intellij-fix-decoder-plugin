package com.dush.intellij.fixdecoder.core.dictionary;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A FIX data dictionary: fields, header, trailer and messages, with components already flattened.
 * Instances are not changed after they are built; {@link #overlay} and {@link #combine} return new ones.
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
    /** Count tag to a group definition, for messages whose type is unknown. The first definition found wins. */
    private final Map<Integer, GroupDef> anyGroup = new HashMap<>();

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
                anyGroup.putIfAbsent(m.tag(), m.group());
                collectGroups(m.group());
            }
        }
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

    /** A group definition with this count tag from any message; null when no message has such a group. */
    public GroupDef anyGroup(int countTag) {
        return anyGroup.get(countTag);
    }

    Map<String, List<MemberDef>> components() {
        return components;
    }

    /**
     * Returns a copy with {@code custom} laid over this one: its fields and messages are added, and replace definitions
     * with the same tag number or MsgType. A non-empty custom header or trailer replaces this one's.
     */
    public Dictionary overlay(Dictionary custom, String customName) {
        Map<Integer, FieldDef> f = new LinkedHashMap<>(fields);
        f.putAll(custom.fields);
        Map<String, MessageDef> m = new LinkedHashMap<>(messages);
        m.putAll(custom.messages);
        Map<String, List<MemberDef>> c = new LinkedHashMap<>(components);
        c.putAll(custom.components);
        FieldList h = custom.header.members().isEmpty() ? header : custom.header;
        FieldList t = custom.trailer.members().isEmpty() ? trailer : custom.trailer;
        return new Dictionary(beginString, label + " + " + customName, f, m, c, h, t);
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
