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

    @Test
    public void customComponentReachesEveryMessageThatUsesIt() throws Exception {
        String xml = "<fix major=\"4\" minor=\"4\"><components><component name=\"Parties\">"
                + "<group name=\"NoPartyIDs\" required=\"N\"><field name=\"PartyID\" required=\"N\"/>"
                + "<field name=\"PartyIDSource\" required=\"N\"/><field name=\"PartyRole\" required=\"N\"/>"
                + "<field name=\"VenuePartyFlag\" required=\"N\"/></group></component></components>"
                + "<fields><field number=\"5001\" name=\"VenuePartyFlag\" type=\"CHAR\"/></fields></fix>";
        Dictionary d = DictionarySet.bundled().withCustom("venue.xml", xml.getBytes(StandardCharsets.UTF_8)).get(DictionarySet.FIX44);
        // NewOrderSingle and ExecutionReport are not in the custom file, but use Parties
        assertTrue(d.message("D").member(453).group().contains(5001));
        assertTrue(d.message("8").member(453).group().contains(5001));
    }

    @Test
    public void customHeaderAddsToTheStandardOne() throws Exception {
        String xml = "<fix major=\"4\" minor=\"4\"><header><field name=\"VenueHeaderTag\" required=\"N\"/></header>"
                + "<trailer><field name=\"VenueTrailerTag\" required=\"N\"/></trailer>"
                + "<fields><field number=\"5002\" name=\"VenueHeaderTag\" type=\"STRING\"/>"
                + "<field number=\"5003\" name=\"VenueTrailerTag\" type=\"STRING\"/></fields></fix>";
        Dictionary d = DictionarySet.bundled().withCustom("header.xml", xml.getBytes(StandardCharsets.UTF_8)).get(DictionarySet.FIX44);
        assertEquals(8, d.header().members().get(0).tag());
        assertTrue(d.header().contains(5002));
        assertNotNull(d.header().member(627).group());   // NoHops is still there
        java.util.List<MemberDef> trailer = d.trailer().members();
        assertEquals(10, trailer.get(trailer.size() - 1).tag());   // CheckSum stays last
        assertTrue(d.trailer().contains(5003));
    }

    @Test
    public void customEnumValuesAreAdded() throws Exception {
        String xml = "<fix major=\"4\" minor=\"4\"><fields><field number=\"54\" name=\"Side\" type=\"CHAR\">"
                + "<value enum=\"Z\" description=\"VENUE_SIDE\"/></field></fields></fix>";
        FieldDef side = DictionarySet.bundled().withCustom("side.xml", xml.getBytes(StandardCharsets.UTF_8)).get(DictionarySet.FIX44).field(54);
        assertEquals("Venue side", side.meaning("Z"));
        assertEquals("Buy", side.meaning("1"));
    }

    @Test
    public void customFileAppliesToFixtToo() throws Exception {
        String xml = "<fix major=\"5\" minor=\"0\"><fields><field number=\"5001\" name=\"VenueFlag\" type=\"CHAR\"/></fields></fix>";
        DictionarySet set = DictionarySet.bundled().withCustom("v5.xml", xml.getBytes(StandardCharsets.UTF_8));
        assertEquals("VenueFlag", set.get(DictionarySet.FIX50SP2).field(5001).name());
        assertEquals("FIXT 1.1 + FIX 5.0 SP2 + v5.xml", set.get(DictionarySet.FIX50SP2).label());
        assertNull(set.get(DictionarySet.FIX44).field(5001));
    }

    @Test(expected = DictionaryParser.DictionaryException.class)
    public void rejectsNonDictionary() throws Exception {
        DictionarySet.bundled().withCustom("bad.xml", "<foo/>".getBytes(StandardCharsets.UTF_8));
    }
}
