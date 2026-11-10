package com.dush.intellij.fixdecoder.hover;

import com.dush.intellij.fixdecoder.core.dictionary.FieldDef;
import com.dush.intellij.fixdecoder.core.dictionary.MessageDef;
import com.dush.intellij.fixdecoder.core.parse.FixMessage;
import com.dush.intellij.fixdecoder.core.parse.FixNode;
import com.dush.intellij.fixdecoder.core.parse.Problem;
import com.dush.intellij.fixdecoder.core.parse.RawField;
import com.intellij.openapi.util.text.StringUtil;

import java.util.Map;

/** The HTML shown when hovering over a field. */
public final class FieldInfo {
    static final String DECODE_LINK = "decode";
    private static final int MAX_OTHER_VALUES = 8;

    private FieldInfo() {
    }

    public static String html(FixMessage m, RawField f) {
        FieldDef def = m.dictionary().field(f.tag());
        FixNode.FieldNode node = m.node(f.index());
        StringBuilder sb = new StringBuilder("<html><body style='padding:2px 4px'>");
        sb.append("<b>").append(f.tag()).append("</b>&nbsp;&nbsp;");
        if (def == null) {
            sb.append("<i>Unknown tag</i><br>");
            sb.append(gray("Not in " + esc(m.dictionary().label()) + ". Add a custom dictionary in Settings &gt; Tools &gt; FIX Decoder."));
        } else {
            sb.append("<b>").append(esc(def.name())).append("</b>&nbsp;&nbsp;").append(gray(esc(def.type()))).append("<br>");
            String meaning = m.meaning(f);
            if (meaning != null) {
                sb.append("Value <code>").append(esc(f.value())).append("</code> = <b>").append(esc(meaning)).append("</b><br>");
            } else if (def.isEnumerated() && !f.value().isEmpty()) {
                sb.append("Value <code>").append(esc(f.value())).append("</code> is not one of the defined values<br>");
            }
            if (def.isEnumerated() && f.tag() != 35) sb.append(otherValues(def, f.value()));
        }
        String place = place(m, node);
        if (place != null) sb.append(gray(place)).append("<br>");
        for (Problem p : m.problemsAt(f)) {
            sb.append("<span style='color:#d9534f'>").append(esc(p.message())).append("</span><br>");
        }
        sb.append(gray(esc(m.dictionary().label()))).append("&nbsp;&nbsp;<a href='").append(DECODE_LINK).append("'>Decode message</a>");
        return sb.append("</body></html>").toString();
    }

    private static String otherValues(FieldDef def, String current) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, String> e : def.values().entrySet()) {
            if (e.getKey().equals(current)) continue;
            if (shown == MAX_OTHER_VALUES) {
                sb.append(" · …");
                break;
            }
            if (shown > 0) sb.append(" · ");
            sb.append(esc(e.getKey())).append(' ').append(esc(FieldDef.readable(e.getValue())));
            shown++;
        }
        return shown == 0 ? "" : gray("Other values: " + sb) + "<br>";
    }

    /** Where the field sits: its group and instance, or whether the message type requires it. */
    private static String place(FixMessage m, FixNode.FieldNode node) {
        if (node == null) return null;
        StringBuilder sb = new StringBuilder();
        if (node.parentGroup() != null) {
            FixNode.GroupNode g = node.parentGroup();
            sb.append("In ").append(esc(g.def().name())).append(" (").append(g.def().countField().number())
                    .append("), instance ").append(node.instance()).append(" of ").append(g.instances().size());
            if (node.member() != null && node.member().required()) sb.append(" · required");
            return sb.toString();
        }
        if (node.member() != null && node.member().group() != null) {
            sb.append("Repeating group, starts with ").append(esc(node.member().group().delimiter().name()))
                    .append(" (").append(node.member().group().delimiterTag()).append(") · ");
        }
        MessageDef msg = m.messageDef();
        boolean session = m.dictionary().header().contains(node.field().tag()) || m.dictionary().trailer().contains(node.field().tag());
        if (session) {
            sb.append(node.member() != null && node.member().required() ? "Required in every message" : "Header or trailer field");
        } else if (msg != null) {
            String where = esc(msg.name()) + " (35=" + esc(msg.msgType()) + ")";
            if (node.member() == null) sb.append("Not defined for ").append(where);
            else sb.append(node.member().required() ? "Required in " : "Optional in ").append(where);
        } else if (sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }

    private static String gray(String html) {
        return "<span style='color:gray'>" + html + "</span>";
    }

    private static String esc(String s) {
        return StringUtil.escapeXmlEntities(s);
    }
}
