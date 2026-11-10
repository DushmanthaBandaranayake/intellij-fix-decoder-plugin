package com.dush.intellij.fixdecoder.core.parse;

import com.dush.intellij.fixdecoder.core.dictionary.Dictionary;
import com.dush.intellij.fixdecoder.core.dictionary.MessageDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A parsed message: its fields, the dictionary used, the group structure and the problems found. */
public final class FixMessage {
    private final String text;
    private final String delimiter;
    private final List<RawField> fields;
    private final Dictionary dictionary;
    private final MessageDef messageDef;
    private final List<FixNode> nodes;
    private final FixNode.FieldNode[] byIndex;
    private final List<Problem> problems = new ArrayList<>();

    FixMessage(String text, String delimiter, List<RawField> fields, Dictionary dictionary, MessageDef messageDef,
               List<FixNode> nodes, FixNode.FieldNode[] byIndex) {
        this.text = text;
        this.delimiter = delimiter;
        this.fields = Collections.unmodifiableList(fields);
        this.dictionary = dictionary;
        this.messageDef = messageDef;
        this.nodes = Collections.unmodifiableList(nodes);
        this.byIndex = byIndex;
    }

    /** The text the field offsets refer to. */
    public String text() {
        return text;
    }

    public String delimiter() {
        return delimiter;
    }

    public List<RawField> fields() {
        return fields;
    }

    public Dictionary dictionary() {
        return dictionary;
    }

    /** Null when MsgType (35) is missing or not in the dictionary. */
    public MessageDef messageDef() {
        return messageDef;
    }

    /** Top-level nodes in message order. */
    public List<FixNode> nodes() {
        return nodes;
    }

    /** The node for a field, by {@link RawField#index()}. */
    public FixNode.FieldNode node(int fieldIndex) {
        return byIndex[fieldIndex];
    }

    /** The field containing the text offset (including its trailing delimiter), or null. */
    public RawField fieldAt(int offset) {
        for (RawField f : fields) {
            if (offset >= f.start() && offset < Math.max(f.end(), f.delimiterEnd())) return f;
            if (offset == f.end() && !f.hasDelimiter()) return f;
        }
        return null;
    }

    public RawField first(int tag) {
        for (RawField f : fields) if (f.tag() == tag) return f;
        return null;
    }

    public String value(int tag) {
        RawField f = first(tag);
        return f == null ? null : f.value();
    }

    /** True when the message starts with BeginString (8) rather than being a fragment. */
    public boolean isComplete() {
        return !fields.isEmpty() && fields.get(0).tag() == 8;
    }

    /** True when part of the text is unknown (for example a method call in a Java concatenation). */
    public boolean hasUnknownParts() {
        return text.indexOf(FixScanner.UNKNOWN) >= 0;
    }

    public List<Problem> problems() {
        return Collections.unmodifiableList(problems);
    }

    void addProblem(Problem p) {
        problems.add(p);
    }

    /** Readable meaning of a field's value: the message name for MsgType (35), the enum meaning otherwise, or null. */
    public String meaning(RawField f) {
        if (f.tag() == 35) {
            com.dush.intellij.fixdecoder.core.dictionary.MessageDef d = dictionary.message(f.value());
            if (d != null) return d.name();
        }
        com.dush.intellij.fixdecoder.core.dictionary.FieldDef def = dictionary.field(f.tag());
        return def == null ? null : def.meaning(f.value());
    }

    /** Problems whose range touches the field. */
    public List<Problem> problemsAt(RawField f) {
        List<Problem> out = new ArrayList<>();
        for (Problem p : problems) {
            if (p.field() == f) out.add(p);
        }
        return out;
    }

    /** Readable name of the message type, e.g. "NewOrderSingle", or null. */
    public String typeName() {
        return messageDef == null ? null : messageDef.name();
    }
}
