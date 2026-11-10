package com.dush.intellij.fixdecoder.core.dictionary;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a data dictionary in the QuickFIX/J XML format. Components are flattened into the lists that use them.
 * <p>
 * A custom dictionary may contain only some sections and may refer to fields and components of a {@code base}
 * dictionary, which is how venue extensions are usually written.
 */
public final class DictionaryParser {

    /** Thrown for a file that is not a usable dictionary. */
    public static final class DictionaryException extends Exception {
        public DictionaryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Map<Integer, FieldDef> fields = new LinkedHashMap<>();
    private final Map<String, FieldDef> fieldsByName = new HashMap<>();
    private final Map<String, Element> componentElements = new HashMap<>();
    private final Map<String, List<MemberDef>> components = new LinkedHashMap<>();
    private final Set<String> resolving = new HashSet<>();
    private final Dictionary base;

    private DictionaryParser(Dictionary base) {
        this.base = base;
    }

    public static Dictionary parse(InputStream in, String label) throws DictionaryException {
        return parse(in, label, null);
    }

    public static Dictionary parse(InputStream in, String label, Dictionary base) throws DictionaryException {
        Document doc;
        try {
            doc = newBuilder().parse(in);
        } catch (Exception e) {
            throw new DictionaryException("Not a readable XML file: " + e.getMessage(), e);
        }
        Element root = doc.getDocumentElement();
        if (root == null || !"fix".equals(root.getTagName())) {
            throw new DictionaryException("Not a QuickFIX/J dictionary: the root element must be <fix>", null);
        }
        return new DictionaryParser(base).read(root, label);
    }

    private static DocumentBuilder newBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setExpandEntityReferences(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return f.newDocumentBuilder();
    }

    /** {@code FIX.4.4}, {@code FIXT.1.1}, or empty when the root has no version. */
    static String beginString(Element root) {
        String major = root.getAttribute("major");
        String minor = root.getAttribute("minor");
        if (major.isEmpty() || minor.isEmpty()) return "";
        String type = root.getAttribute("type");
        return (type.isEmpty() ? "FIX" : type) + "." + major + "." + minor;
    }

    private Dictionary read(Element root, String label) throws DictionaryException {
        Element fieldsEl = child(root, "fields");
        if (fieldsEl != null) {
            for (Element f : children(fieldsEl, "field")) {
                String number = f.getAttribute("number");
                String name = f.getAttribute("name");
                int n;
                try {
                    n = Integer.parseInt(number.trim());
                } catch (NumberFormatException e) {
                    throw new DictionaryException("Field '" + name + "' has an invalid number '" + number + "'", e);
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (Element v : children(f, "value")) {
                    values.put(v.getAttribute("enum"), v.getAttribute("description"));
                }
                FieldDef def = new FieldDef(n, name, f.getAttribute("type"), values);
                fields.put(n, def);
                fieldsByName.put(name, def);
            }
        }
        Element componentsEl = child(root, "components");
        if (componentsEl != null) {
            for (Element c : children(componentsEl, "component")) {
                componentElements.put(c.getAttribute("name"), c);
            }
            for (String name : componentElements.keySet()) {
                component(name);
            }
        }
        FieldList header = new FieldList(members(child(root, "header"), true));
        FieldList trailer = new FieldList(members(child(root, "trailer"), true));
        Map<String, MessageDef> messages = new LinkedHashMap<>();
        Element messagesEl = child(root, "messages");
        if (messagesEl != null) {
            for (Element m : children(messagesEl, "message")) {
                String type = m.getAttribute("msgtype");
                messages.put(type, new MessageDef(type, m.getAttribute("name"), m.getAttribute("msgcat"), members(m, true)));
            }
        }
        return new Dictionary(beginString(root), label, fields, messages, components, header, trailer);
    }

    private FieldDef fieldByName(String name) {
        FieldDef f = fieldsByName.get(name);
        if (f == null && base != null) f = base.field(name);
        return f;
    }

    private List<MemberDef> component(String name) {
        List<MemberDef> done = components.get(name);
        if (done != null) return done;
        Element el = componentElements.get(name);
        if (el == null) {
            return base == null ? null : base.components().get(name);
        }
        if (!resolving.add(name)) return new ArrayList<>();   // a component that contains itself: stop
        List<MemberDef> list = members(el, true);
        resolving.remove(name);
        components.put(name, list);
        return list;
    }

    /** The members of a header, trailer, message, group or component element, with components inlined. */
    private List<MemberDef> members(Element parent, boolean parentRequired) {
        List<MemberDef> out = new ArrayList<>();
        if (parent == null) return out;
        Set<Integer> seen = new HashSet<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            boolean required = parentRequired && "Y".equalsIgnoreCase(e.getAttribute("required"));
            String name = e.getAttribute("name");
            switch (e.getTagName()) {
                case "field": {
                    FieldDef f = fieldByName(name);
                    if (f != null && seen.add(f.number())) out.add(new MemberDef(f, required, null));
                    break;
                }
                case "group": {
                    FieldDef count = fieldByName(name);
                    if (count == null) break;
                    List<MemberDef> inner = members(e, true);
                    if (inner.isEmpty()) break;
                    if (seen.add(count.number())) out.add(new MemberDef(count, required, new GroupDef(count, inner)));
                    break;
                }
                case "component": {
                    List<MemberDef> inner = component(name);
                    if (inner == null) break;
                    for (MemberDef m : inner) {
                        if (seen.add(m.tag())) out.add(required ? m : new MemberDef(m.field(), false, m.group()));
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return out;
    }

    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && name.equals(((Element) n).getTagName())) return (Element) n;
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element && name.equals(((Element) n).getTagName())) out.add((Element) n);
        }
        return out;
    }
}
