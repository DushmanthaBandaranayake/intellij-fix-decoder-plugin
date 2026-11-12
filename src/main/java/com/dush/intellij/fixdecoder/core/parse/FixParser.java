package com.dush.intellij.fixdecoder.core.parse;

import com.dush.intellij.fixdecoder.core.dictionary.Dictionary;
import com.dush.intellij.fixdecoder.core.dictionary.DictionarySet;
import com.dush.intellij.fixdecoder.core.dictionary.FieldDef;
import com.dush.intellij.fixdecoder.core.dictionary.GroupDef;
import com.dush.intellij.fixdecoder.core.dictionary.MemberDef;
import com.dush.intellij.fixdecoder.core.dictionary.MessageDef;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the group structure of a message from its fields and the dictionary, and reports group problems.
 * <p>
 * An instance starts at the group's delimiter field (its first field) and ends at the next delimiter field or at the
 * first field that is not a member of the group. A member that appears before one already seen in the same instance
 * is out of order (it is kept in the instance and reported).
 */
public final class FixParser {
    private final List<RawField> fields;
    private final Dictionary dict;
    private final MessageDef msgDef;
    private final FixNode.FieldNode[] byIndex;
    private final List<Problem> problems = new ArrayList<>();

    private FixParser(List<RawField> fields, Dictionary dict, MessageDef msgDef) {
        this.fields = fields;
        this.dict = dict;
        this.msgDef = msgDef;
        this.byIndex = new FixNode.FieldNode[fields.size()];
    }

    /** Parses and validates the first message in {@code text}; null when there is none. */
    public static FixMessage parse(String text, DictionarySet set, String defaultVersion) {
        FixScanner.Span span = FixScanner.first(text, set::isKnownTag);
        return span == null ? null : parse(text, span, set, defaultVersion);
    }

    /** Parses and validates a message found by {@link FixScanner}; field offsets refer to {@code text}. */
    public static FixMessage parse(String text, FixScanner.Span span, DictionarySet set, String defaultVersion) {
        List<RawField> fields = span.fields();
        String begin = null;
        String applVerId = null;
        String msgType = null;
        for (RawField f : fields) {
            if (f.tag() == 8 && begin == null) begin = f.value();
            else if (f.tag() == 1128 && applVerId == null) applVerId = f.value();
            else if (f.tag() == 35 && msgType == null) msgType = f.value();
        }
        Dictionary dict = set.get(DictionarySet.versionFor(begin, applVerId, defaultVersion));
        MessageDef msgDef = msgType == null ? null : dict.message(msgType);
        FixParser p = new FixParser(fields, dict, msgDef);
        List<FixNode> nodes = p.parseTop();
        FixMessage m = new FixMessage(text, span.delimiter(), new ArrayList<>(fields), dict, msgDef, nodes, p.byIndex);
        for (Problem problem : p.problems) m.addProblem(problem);
        FixValidator.validate(m);
        return m;
    }

    private List<FixNode> parseTop() {
        List<FixNode> out = new ArrayList<>();
        int pos = 0;
        while (pos < fields.size()) {
            RawField f = fields.get(pos);
            MemberDef member = topMember(f.tag());
            FixNode.FieldNode fn = fieldNode(f, member, null, 0);
            int nextTag = pos + 1 < fields.size() ? fields.get(pos + 1).tag() : -1;
            GroupDef g = member != null ? member.group() : (msgDef == null ? dict.groupFor(f.tag(), nextTag) : null);
            if (g != null) {
                FixNode.GroupNode gn = parseGroup(g, fn, pos + 1, new ArrayList<>());
                out.add(gn);
                pos = gn.endPos;
            } else {
                out.add(fn);
                pos++;
            }
        }
        return out;
    }

    private MemberDef topMember(int tag) {
        MemberDef m = msgDef == null ? null : msgDef.member(tag);
        if (m == null) m = dict.header().member(tag);
        if (m == null) m = dict.trailer().member(tag);
        return m;
    }

    private FixNode.FieldNode fieldNode(RawField f, MemberDef member, FixNode.GroupNode parent, int instance) {
        FieldDef def = dict.field(f.tag());
        FixNode.FieldNode fn = new FixNode.FieldNode(f, def, member, parent, instance);
        byIndex[f.index()] = fn;
        return fn;
    }

    /** @param enclosing the groups this one is nested in, outermost first */
    private FixNode.GroupNode parseGroup(GroupDef g, FixNode.FieldNode countNode, int pos, List<GroupDef> enclosing) {
        FixNode.GroupNode gn = new FixNode.GroupNode(countNode, g);
        int declared = gn.declaredCount();
        RawField countField = countNode.field();
        if (declared < 0) {
            problems.add(new Problem(Problem.Kind.GROUP_COUNT_NOT_A_NUMBER, countField, countField.start(), countField.end(),
                    describe(g.countField()) + " must be a number of instances", -1));
        }
        while (pos < fields.size()) {
            RawField f = fields.get(pos);
            if (f.tag() == g.delimiterTag()) {
                pos = parseInstance(g, gn, pos, enclosing);
            } else if (gn.instances().isEmpty() && g.contains(f.tag())) {
                problems.add(new Problem(Problem.Kind.GROUP_MISSING_DELIMITER, f, f.start(), f.end(),
                        "Instance 1 of " + g.name() + " must start with " + describe(g.delimiter()) + ", not " + describe(f), -1));
                pos = parseInstance(g, gn, pos, enclosing);
            } else {
                break;
            }
        }
        int actual = gn.instances().size();
        if (declared >= 0 && actual != declared) {
            problems.add(new Problem(Problem.Kind.GROUP_COUNT, countField, countField.start(), countField.end(),
                    describe(g.countField()) + " says " + declared + ", but " + actual + (actual == 1 ? " instance" : " instances")
                            + " found", actual));
        }
        gn.endPos = pos;
        return gn;
    }

    /**
     * Reads one instance. It ends at the next delimiter field or at a field that belongs elsewhere: to the message,
     * header or trailer, or to an enclosing group. A field that belongs nowhere (typically a venue tag that the dictionary
     * doesn't list in this group) stays in the instance and is reported, so the rest of the group is not cut off.
     */
    private int parseInstance(GroupDef g, FixNode.GroupNode gn, int pos, List<GroupDef> enclosing) {
        FixNode.Instance inst = new FixNode.Instance(gn.instances().size() + 1);
        gn.add(inst);
        String where = " instance " + inst.number() + " of " + g.name();
        int last = -1;
        boolean first = true;
        boolean hasDelimiter = false;
        while (pos < fields.size()) {
            RawField f = fields.get(pos);
            boolean isDelimiter = f.tag() == g.delimiterTag();
            if (!first && isDelimiter && hasDelimiter) break;
            int idx = g.indexOf(f.tag());
            if (idx < 0) {
                if (first || !belongsNowhere(f.tag(), enclosing)) break;
                FieldDef d = dict.field(f.tag());
                String msg = d == null
                        ? "Unknown tag " + f.tag() + " in" + where + "; if it belongs to the group, add it to a custom dictionary"
                        : describe(d) + " is not part of " + g.name() + " (in" + where + "); if it belongs to the group, add it in a custom dictionary";
                problems.add(new Problem(Problem.Kind.GROUP_UNKNOWN_TAG, f, f.start(), f.end(), msg, -1));
                inst.add(fieldNode(f, null, gn, inst.number()));
                pos++;
                continue;
            }
            // An instance that started without its delimiter takes the next delimiter in (already reported as missing).
            boolean lateDelimiter = isDelimiter && !first && !hasDelimiter;
            if (idx <= last && !lateDelimiter) {
                String msg = idx == last
                        ? describe(f) + " appears twice in" + where
                        : describe(f) + " is out of order in" + where + ": it must come before " + describe(g.members().get(last).field());
                problems.add(new Problem(Problem.Kind.GROUP_FIELD_ORDER, f, f.start(), f.end(), msg, -1));
            }
            hasDelimiter |= isDelimiter;
            last = Math.max(last, idx);
            MemberDef m = g.members().get(idx);
            FixNode.FieldNode fn = fieldNode(f, m, gn, inst.number());
            if (m.group() != null) {
                List<GroupDef> chain = new ArrayList<>(enclosing);
                chain.add(g);
                FixNode.GroupNode inner = parseGroup(m.group(), fn, pos + 1, chain);
                inst.add(inner);
                pos = inner.endPos;
            } else {
                inst.add(fn);
                pos++;
            }
            first = false;
        }
        return pos;
    }

    /**
     * True for a tag that is not a field of the message, header or trailer, nor of an enclosing group.
     * For a message type the dictionary doesn't know, only tags missing from the dictionary count, since any known
     * tag might be a field of that message.
     */
    private boolean belongsNowhere(int tag, List<GroupDef> enclosing) {
        if (topMember(tag) != null) return false;
        for (GroupDef outer : enclosing) if (outer.contains(tag)) return false;
        return msgDef != null || dict.field(tag) == null;
    }

    private String describe(RawField f) {
        FieldDef d = dict.field(f.tag());
        return d == null ? "tag " + f.tag() : describe(d);
    }

    static String describe(FieldDef d) {
        return d.name() + " (" + d.number() + ")";
    }
}
