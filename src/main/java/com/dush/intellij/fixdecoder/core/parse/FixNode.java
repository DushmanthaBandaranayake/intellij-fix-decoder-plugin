package com.dush.intellij.fixdecoder.core.parse;

import com.dush.intellij.fixdecoder.core.dictionary.FieldDef;
import com.dush.intellij.fixdecoder.core.dictionary.GroupDef;
import com.dush.intellij.fixdecoder.core.dictionary.MemberDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The parsed structure of a message: fields, and repeating groups made of instances. */
public abstract class FixNode {

    private FixNode() {
    }

    /** A single field. When it counts a repeating group, it is the {@link GroupNode#count()} of that group. */
    public static final class FieldNode extends FixNode {
        private final RawField field;
        private final FieldDef def;
        private final MemberDef member;
        private final GroupNode parentGroup;
        private final int instance;

        FieldNode(RawField field, FieldDef def, MemberDef member, GroupNode parentGroup, int instance) {
            this.field = field;
            this.def = def;
            this.member = member;
            this.parentGroup = parentGroup;
            this.instance = instance;
        }

        public RawField field() {
            return field;
        }

        /** Null when the tag is not in the dictionary. */
        public FieldDef def() {
            return def;
        }

        /** Where the field is declared in the message, header, trailer or group; null when it is not declared there. */
        public MemberDef member() {
            return member;
        }

        /** The group whose instance contains this field, or null at the top level. */
        public GroupNode parentGroup() {
            return parentGroup;
        }

        /** Instance number in {@link #parentGroup()}, from 1; 0 at the top level. */
        public int instance() {
            return instance;
        }

        public String name() {
            return def == null ? "" : def.name();
        }

        /** Readable meaning of the value for enumerated fields, else null. */
        public String meaning() {
            return def == null ? null : def.meaning(field.value());
        }
    }

    /** A repeating group: the count field followed by its instances. */
    public static final class GroupNode extends FixNode {
        private final FieldNode count;
        private final GroupDef def;
        private final List<Instance> instances = new ArrayList<>();
        int endPos;

        GroupNode(FieldNode count, GroupDef def) {
            this.count = count;
            this.def = def;
        }

        public FieldNode count() {
            return count;
        }

        public GroupDef def() {
            return def;
        }

        public List<Instance> instances() {
            return Collections.unmodifiableList(instances);
        }

        void add(Instance i) {
            instances.add(i);
        }

        /** The value of the count field, or -1 when it is not a number. */
        public int declaredCount() {
            try {
                return Integer.parseInt(count.field().value().trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /** One instance of a repeating group. */
    public static final class Instance {
        private final int number;
        private final List<FixNode> children = new ArrayList<>();

        Instance(int number) {
            this.number = number;
        }

        /** From 1. */
        public int number() {
            return number;
        }

        public List<FixNode> children() {
            return Collections.unmodifiableList(children);
        }

        void add(FixNode n) {
            children.add(n);
        }
    }
}
