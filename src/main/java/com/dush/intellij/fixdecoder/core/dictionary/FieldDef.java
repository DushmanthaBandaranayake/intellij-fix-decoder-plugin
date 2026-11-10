package com.dush.intellij.fixdecoder.core.dictionary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One field of a data dictionary: tag number, name, type and (for enumerated fields) the allowed values. */
public final class FieldDef {
    private final int number;
    private final String name;
    private final String type;
    private final Map<String, String> values;

    public FieldDef(int number, String name, String type, Map<String, String> values) {
        this.number = number;
        this.name = name;
        this.type = type == null ? "" : type;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public int number() {
        return number;
    }

    public String name() {
        return name;
    }

    /** The QuickFIX/J type name, such as {@code STRING}, {@code CHAR} or {@code NUMINGROUP}. */
    public String type() {
        return type;
    }

    /** Enum value to its description (QuickFIX/J style, e.g. {@code SELL_SHORT}), in dictionary order. Empty when not enumerated. */
    public Map<String, String> values() {
        return values;
    }

    public boolean isEnumerated() {
        return !values.isEmpty();
    }

    /** True for types whose value is a space-separated list of enum values. */
    public boolean isMultipleValue() {
        return type.startsWith("MULTIPLE");
    }

    /** Readable meaning of a value, e.g. {@code SELL_SHORT} becomes "Sell short"; null when the value is not one of the enum values. */
    public String meaning(String value) {
        if (values.isEmpty() || value == null) return null;
        if (isMultipleValue() && value.indexOf(' ') >= 0) {
            StringBuilder sb = new StringBuilder();
            for (String part : value.trim().split(" +")) {
                String d = values.get(part);
                if (d == null) return null;
                if (sb.length() > 0) sb.append(", ");
                sb.append(readable(d));
            }
            return sb.toString();
        }
        String d = values.get(value);
        return d == null ? null : readable(d);
    }

    /** Turns {@code SELL_SHORT_EXEMPT} into "Sell short exempt". Descriptions that are not in that style are kept as they are. */
    public static String readable(String description) {
        if (description.isEmpty()) return description;
        boolean constantStyle = true;
        for (int i = 0; i < description.length(); i++) {
            char c = description.charAt(i);
            if (Character.isLowerCase(c) || c == ' ') {
                constantStyle = false;
                break;
            }
        }
        if (!constantStyle) return description;
        String s = description.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public String toString() {
        return number + "(" + name + ")";
    }
}
