package com.dush.intellij.fixdecoder.core.dictionary;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads data dictionaries in the QuickFIX/J XML format. Components are flattened into the lists that use them.
 * <p>
 * Several files can be read as one: a standard dictionary followed by custom ones. They are merged before anything is
 * flattened, so that a custom file that redefines a component (say, Parties with a venue tag) changes every message
 * that uses it, not only the messages the custom file lists. Merge rules, later files winning:
 * <ul>
 *   <li>fields by number; enum values are added to the earlier ones</li>
 *   <li>components by name, messages by MsgType: replaced as a whole</li>
 *   <li>header and trailer entries by name: replaced in place, or added (trailer entries go before CheckSum)</li>
 * </ul>
 * A custom file may contain only some sections and may refer to fields and components of the files before it.
 */
public final class DictionaryParser {

    /** Thrown for a file that is not a usable dictionary. */
    public static final class DictionaryException extends Exception {
        public DictionaryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** One XML file to read. */
    public static final class Source {
        final String name;
        final byte[] xml;

        public Source(String name, byte[] xml) {
            this.name = name;
            this.xml = xml;
        }
    }

    // merged elements
    private final Map<Integer, Element> fieldElements = new LinkedHashMap<>();
    private final Map<Integer, Map<String, String>> fieldValues = new HashMap<>();
    private final Map<String, Integer> numberByName = new HashMap<>();
    private final Map<String, Element> componentElements = new HashMap<>();
    private final Map<String, Element> messageElements = new LinkedHashMap<>();
    private final List<Element> headerEntries = new ArrayList<>();
    private final List<Element> trailerEntries = new ArrayList<>();
    private String beginString = "";

    // built
    private final Map<Integer, FieldDef> fields = new LinkedHashMap<>();
    private final Map<String, List<MemberDef>> components = new LinkedHashMap<>();
    private final Set<String> resolving = new HashSet<>();

    private DictionaryParser() {
    }

    public static Dictionary parse(InputStream in, String label) throws DictionaryException {
        byte[] bytes;
        try {
            bytes = in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new DictionaryException("Not a readable file: " + e.getMessage(), e);
        }
        return parse(Collections.singletonList(new Source(label, bytes)), label);
    }

    /** Reads the files as one dictionary, later files laid over earlier ones. */
    public static Dictionary parse(List<Source> sources, String label) throws DictionaryException {
        DictionaryParser p = new DictionaryParser();
        for (Source s : sources) p.merge(root(s));
        return p.build(label);
    }

    /** Checks that a file is a dictionary, without building anything. */
    public static void check(byte[] xml, String name) throws DictionaryException {
        root(new Source(name, xml));
    }

    private static Element root(Source s) throws DictionaryException {
        Document doc;
        try {
            doc = newBuilder().parse(new ByteArrayInputStream(s.xml));
        } catch (Exception e) {
            throw new DictionaryException("Not a readable XML file: " + e.getMessage(), e);
        }
        Element root = doc.getDocumentElement();
        if (root == null || !"fix".equals(root.getTagName())) {
            throw new DictionaryException("Not a QuickFIX/J dictionary: the root element must be <fix>", null);
        }
        return root;
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

    // --- merging ---

    private void merge(Element root) throws DictionaryException {
        if (beginString.isEmpty()) beginString = beginString(root);
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
                fieldElements.put(n, f);
                numberByName.put(name, n);
                Map<String, String> values = fieldValues.computeIfAbsent(n, k -> new LinkedHashMap<>());
                for (Element v : children(f, "value")) values.put(v.getAttribute("enum"), v.getAttribute("description"));
            }
        }
        Element componentsEl = child(root, "components");
        if (componentsEl != null) {
            for (Element c : children(componentsEl, "component")) componentElements.put(c.getAttribute("name"), c);
        }
        Element messagesEl = child(root, "messages");
        if (messagesEl != null) {
            for (Element m : children(messagesEl, "message")) messageElements.put(m.getAttribute("msgtype"), m);
        }
        mergeEntries(headerEntries, child(root, "header"), false);
        mergeEntries(trailerEntries, child(root, "trailer"), true);
    }

    /** Header or trailer: an entry with the same name replaces the earlier one in place; a new one is added. */
    private static void mergeEntries(List<Element> into, Element section, boolean beforeCheckSum) {
        if (section == null) return;
        for (Node n = section.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            int existing = -1;
            for (int i = 0; i < into.size(); i++) {
                if (into.get(i).getTagName().equals(e.getTagName()) && into.get(i).getAttribute("name").equals(e.getAttribute("name"))) {
                    existing = i;
                    break;
                }
            }
            if (existing >= 0) {
                into.set(existing, e);
            } else {
                int at = into.size();
                if (beforeCheckSum) {
                    for (int i = 0; i < into.size(); i++) {
                        if ("CheckSum".equals(into.get(i).getAttribute("name"))) {
                            at = i;
                            break;
                        }
                    }
                }
                into.add(at, e);
            }
        }
    }

    // --- building ---

    private Dictionary build(String label) {
        for (Map.Entry<Integer, Element> e : fieldElements.entrySet()) {
            Element f = e.getValue();
            fields.put(e.getKey(), new FieldDef(e.getKey(), f.getAttribute("name"), f.getAttribute("type"), fieldValues.get(e.getKey())));
        }
        for (String name : componentElements.keySet()) component(name);
        FieldList header = new FieldList(members(headerEntries, true));
        FieldList trailer = new FieldList(members(trailerEntries, true));
        Map<String, MessageDef> messages = new LinkedHashMap<>();
        for (Map.Entry<String, Element> e : messageElements.entrySet()) {
            Element m = e.getValue();
            messages.put(e.getKey(), new MessageDef(e.getKey(), m.getAttribute("name"), m.getAttribute("msgcat"), members(elements(m), true)));
        }
        return new Dictionary(beginString, label, fields, messages, components, header, trailer);
    }

    private FieldDef fieldByName(String name) {
        Integer n = numberByName.get(name);
        return n == null ? null : fields.get(n);
    }

    private List<MemberDef> component(String name) {
        List<MemberDef> done = components.get(name);
        if (done != null) return done;
        Element el = componentElements.get(name);
        if (el == null) return null;
        if (!resolving.add(name)) return new ArrayList<>();   // a component that contains itself: stop
        List<MemberDef> list = members(elements(el), true);
        resolving.remove(name);
        components.put(name, list);
        return list;
    }

    /** The members described by header, trailer, message, group or component entries, with components inlined. */
    private List<MemberDef> members(List<Element> entries, boolean parentRequired) {
        List<MemberDef> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Element e : entries) {
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
                    List<MemberDef> inner = members(elements(e), true);
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

    private static List<Element> elements(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) out.add((Element) n);
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
