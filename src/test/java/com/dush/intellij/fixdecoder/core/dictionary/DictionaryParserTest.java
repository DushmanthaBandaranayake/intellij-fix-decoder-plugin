package com.dush.intellij.fixdecoder.core.dictionary;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class DictionaryParserTest {

    @Test
    public void bundledFix44HasFieldsEnumsAndGroups() {
        Dictionary d = DictionarySet.bundled().get(DictionarySet.FIX44);
        assertEquals("FIX.4.4", d.beginString());
        FieldDef side = d.field(54);
        assertEquals("Side", side.name());
        assertEquals("CHAR", side.type());
        assertEquals("Buy", side.meaning("1"));
        assertEquals("Sell short exempt", side.meaning("6"));
        assertNull(side.meaning("Z"));

        MessageDef nos = d.message("D");
        assertEquals("NewOrderSingle", nos.name());
        assertTrue(nos.member(11).required());
        // Parties is a component; its group is flattened into the message.
        GroupDef parties = nos.member(453).group();
        assertNotNull(parties);
        assertEquals(448, parties.delimiterTag());
        assertTrue(parties.contains(452));
        assertTrue(parties.indexOf(447) < parties.indexOf(452));
        // nested group inside Parties
        assertNotNull(parties.member(802).group());
        assertEquals(523, parties.member(802).group().delimiterTag());

        assertEquals(8, d.header().members().get(0).tag());
        assertEquals(10, d.trailer().members().get(d.trailer().members().size() - 1).tag());
    }

    @Test
    public void fixtCombinesSessionAndApplication() {
        Dictionary d = DictionarySet.bundled().get(DictionarySet.FIX50SP2);
        assertEquals("FIXT.1.1", d.beginString());
        assertTrue(d.header().contains(1128));
        assertNotNull(d.message("A"));   // Logon from FIXT
        assertNotNull(d.message("D"));   // NewOrderSingle from FIX 5.0 SP2
    }

    @Test
    public void versionChoice() {
        assertEquals(DictionarySet.FIX42, DictionarySet.versionFor("FIX.4.2", null, null));
        assertEquals(DictionarySet.FIX44, DictionarySet.versionFor("FIX.4.3", null, null));
        assertEquals(DictionarySet.FIX50SP2, DictionarySet.versionFor("FIXT.1.1", null, null));
        assertEquals(DictionarySet.FIX44, DictionarySet.versionFor("FIXT.1.1", "6", null));
        assertEquals(DictionarySet.FIX42, DictionarySet.versionFor(null, null, DictionarySet.FIX42));
        assertEquals(DictionarySet.FIX44, DictionarySet.versionFor("XYZ", null, "nonsense"));
    }

    @Test
    public void customDictionaryAddsFieldsAndUsesBaseReferences() throws Exception {
        String xml = "<fix major=\"4\" minor=\"4\">\n"
                + "  <messages>\n"
                + "    <message name=\"VenueOrder\" msgtype=\"U1\" msgcat=\"app\">\n"
                + "      <field name=\"ClOrdID\" required=\"Y\"/>\n"
                + "      <field name=\"VenueFlag\" required=\"N\"/>\n"
                + "      <component name=\"Parties\" required=\"N\"/>\n"
                + "    </message>\n"
                + "  </messages>\n"
                + "  <fields>\n"
                + "    <field number=\"5001\" name=\"VenueFlag\" type=\"CHAR\">\n"
                + "      <value enum=\"Y\" description=\"YES\"/>\n"
                + "    </field>\n"
                + "  </fields>\n"
                + "</fix>\n";
        DictionarySet set = DictionarySet.bundled().withCustom("venue.xml", xml.getBytes(StandardCharsets.UTF_8));
        Dictionary d44 = set.get(DictionarySet.FIX44);
        assertEquals("VenueFlag", d44.field(5001).name());
        assertEquals("Yes", d44.field(5001).meaning("Y"));
        MessageDef m = d44.message("U1");
        assertNotNull(m.member(453).group());
        assertEquals("FIX 4.4 + venue.xml", d44.label());
        assertTrue(set.isKnownTag(5001));
        // other versions are untouched
        assertNull(set.get(DictionarySet.FIX42).field(5001));
        assertNull(DictionarySet.bundled().get(DictionarySet.FIX44).field(5001));
    }

    @Test(expected = DictionaryParser.DictionaryException.class)
    public void rejectsNonDictionary() throws Exception {
        DictionarySet.bundled().withCustom("bad.xml", "<foo/>".getBytes(StandardCharsets.UTF_8));
    }
}
