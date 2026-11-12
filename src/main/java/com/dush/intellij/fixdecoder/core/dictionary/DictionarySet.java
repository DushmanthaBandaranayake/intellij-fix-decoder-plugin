package com.dush.intellij.fixdecoder.core.dictionary;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The dictionaries for every supported version, each with the custom dictionaries laid over it,
 * and the choice of dictionary for a message.
 */
public final class DictionarySet {
    public static final String FIX42 = "FIX.4.2";
    public static final String FIX44 = "FIX.4.4";
    public static final String FIX50SP2 = "FIX.5.0SP2";
    /** Version ids, in the order shown in settings. FIX.5.0SP2 means FIXT 1.1 with the FIX 5.0 SP2 application layer. */
    public static final List<String> VERSIONS = Collections.unmodifiableList(Arrays.asList(FIX42, FIX44, FIX50SP2));

    private static volatile DictionarySet bundled;

    /** How one version is built: its XML files (standard first, then custom), with the FIXT session layer apart. */
    private static final class Recipe {
        final List<DictionaryParser.Source> app;
        final String appLabel;
        final List<DictionaryParser.Source> transport;   // null unless FIXT
        final String transportLabel;

        Recipe(List<DictionaryParser.Source> app, String appLabel, List<DictionaryParser.Source> transport, String transportLabel) {
            this.app = app;
            this.appLabel = appLabel;
            this.transport = transport;
            this.transportLabel = transportLabel;
        }

        Recipe with(DictionaryParser.Source custom) {
            List<DictionaryParser.Source> a = new ArrayList<>(app);
            a.add(custom);
            List<DictionaryParser.Source> t = null;
            if (transport != null) {
                t = new ArrayList<>(transport);
                t.add(custom);   // a custom header or trailer belongs to the session layer
            }
            return new Recipe(a, appLabel + " + " + custom.name, t, transportLabel);
        }

        Dictionary build() throws DictionaryParser.DictionaryException {
            Dictionary appDict = DictionaryParser.parse(app, appLabel);
            return transport == null ? appDict : Dictionary.combine(DictionaryParser.parse(transport, transportLabel), appDict);
        }
    }

    private final Map<String, Recipe> recipes;
    private final Map<String, Dictionary> byVersion;
    private final Set<Integer> knownTags = new HashSet<>();

    private DictionarySet(Map<String, Recipe> recipes, Map<String, Dictionary> byVersion) {
        this.recipes = recipes;
        this.byVersion = byVersion;
        for (Dictionary d : byVersion.values()) {
            for (FieldDef f : d.fields()) knownTags.add(f.number());
        }
    }

    /** The dictionaries shipped with the plugin, loaded once. */
    public static DictionarySet bundled() {
        DictionarySet b = bundled;
        if (b == null) {
            synchronized (DictionarySet.class) {
                b = bundled;
                if (b == null) bundled = b = loadBundled();
            }
        }
        return b;
    }

    private static DictionarySet loadBundled() {
        Map<String, Recipe> recipes = new LinkedHashMap<>();
        recipes.put(FIX42, new Recipe(List.of(resource("FIX42.xml")), "FIX 4.2", null, null));
        recipes.put(FIX44, new Recipe(List.of(resource("FIX44.xml")), "FIX 4.4", null, null));
        recipes.put(FIX50SP2, new Recipe(List.of(resource("FIX50SP2.xml")), "FIX 5.0 SP2", List.of(resource("FIXT11.xml")), "FIXT 1.1"));
        Map<String, Dictionary> map = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Recipe> e : recipes.entrySet()) map.put(e.getKey(), e.getValue().build());
        } catch (DictionaryParser.DictionaryException e) {
            throw new IllegalStateException("Bundled dictionaries could not be read", e);
        }
        return new DictionarySet(recipes, map);
    }

    private static DictionaryParser.Source resource(String file) {
        try (InputStream in = DictionarySet.class.getResourceAsStream("/dictionaries/" + file)) {
            if (in == null) throw new IllegalStateException("Bundled dictionary missing: " + file);
            return new DictionaryParser.Source(file, in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("Bundled dictionary " + file + " could not be read", e);
        }
    }

    /**
     * Returns a copy with one custom dictionary merged into the versions it is for: the version in its root element
     * ({@code <fix major="4" minor="4">}), or every version when the root gives none. The version is built again from
     * the standard XML and all custom files, so a redefined component reaches every message that uses it.
     */
    public DictionarySet withCustom(String name, byte[] xml) throws DictionaryParser.DictionaryException {
        DictionaryParser.check(xml, name);
        String target = targetVersion(xml);
        DictionaryParser.Source custom = new DictionaryParser.Source(name, xml);
        Map<String, Recipe> r = new LinkedHashMap<>(recipes);
        Map<String, Dictionary> map = new LinkedHashMap<>(byVersion);
        for (Map.Entry<String, Recipe> e : recipes.entrySet()) {
            if (target != null && !target.equals(e.getKey())) continue;
            Recipe next = e.getValue().with(custom);
            r.put(e.getKey(), next);
            map.put(e.getKey(), next.build());
        }
        return new DictionarySet(r, map);
    }

    /** The version a custom file is for, or null for all. */
    private static String targetVersion(byte[] xml) {
        String head = new String(xml, 0, Math.min(xml.length, 4096), java.nio.charset.StandardCharsets.ISO_8859_1);
        int i = head.indexOf("<fix");
        if (i < 0) return null;
        int end = head.indexOf('>', i);
        if (end < 0) return null;
        String tag = head.substring(i, end);
        String major = attribute(tag, "major");
        String minor = attribute(tag, "minor");
        String type = attribute(tag, "type");
        if (major == null || minor == null) return null;
        return versionFor((type == null ? "FIX" : type) + "." + major + "." + minor, null, null);
    }

    private static String attribute(String tag, String name) {
        int i = tag.indexOf(" " + name + "=");
        if (i < 0) i = tag.indexOf("\t" + name + "=");
        if (i < 0) i = tag.indexOf("\n" + name + "=");
        if (i < 0) return null;
        int q = i + name.length() + 2;
        if (q >= tag.length()) return null;
        char quote = tag.charAt(q);
        int close = tag.indexOf(quote, q + 1);
        return close < 0 ? null : tag.substring(q + 1, close);
    }

    /**
     * The version id for a message: from its BeginString (8) and, for FIXT, its ApplVerID (1128).
     * Unknown or missing values give {@code defaultVersion}.
     */
    public static String versionFor(String beginString, String applVerId, String defaultVersion) {
        String fallback = defaultVersion != null && VERSIONS.contains(defaultVersion) ? defaultVersion : FIX44;
        if (beginString == null) return fallback;
        switch (beginString) {
            case "FIX.4.0":
            case "FIX.4.1":
            case "FIX.4.2":
                return FIX42;
            case "FIX.4.3":
            case "FIX.4.4":
                return FIX44;
            default:
                break;
        }
        if (beginString.startsWith("FIXT.") || beginString.startsWith("FIX.5.")) {
            if (applVerId != null) {
                switch (applVerId) {
                    case "2":
                    case "3":
                    case "4":
                        return FIX42;
                    case "5":
                    case "6":
                        return FIX44;
                    default:
                        break;
                }
            }
            return FIX50SP2;
        }
        return fallback;
    }

    public Dictionary get(String version) {
        Dictionary d = byVersion.get(version);
        return d != null ? d : byVersion.get(FIX44);
    }

    /** True when any dictionary in the set defines the tag. */
    public boolean isKnownTag(int tag) {
        return knownTags.contains(tag);
    }

    public List<String> versions() {
        return new ArrayList<>(byVersion.keySet());
    }
}
