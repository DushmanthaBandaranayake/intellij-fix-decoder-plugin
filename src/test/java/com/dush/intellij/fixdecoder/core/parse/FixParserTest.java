package com.dush.intellij.fixdecoder.core.parse;

import com.dush.intellij.fixdecoder.core.dictionary.DictionarySet;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class FixParserTest {
    private static final DictionarySet SET = DictionarySet.bundled();
    private static final String S = "\u0001";

    private static FixMessage parse(String text) {
        FixMessage m = FixParser.parse(text, SET, DictionarySet.FIX44);
        assertNotNull("no message found in: " + text, m);
        return m;
    }

    private static List<Problem.Kind> kinds(FixMessage m) {
        return m.problems().stream().map(Problem::kind).collect(Collectors.toList());
    }

    /** Pipe-delimited text to SOH, then BodyLength and CheckSum made correct. */
    private static String valid(String pipeText) {
        String soh = pipeText.replace("|", S);
        FixMessage m = parse(soh);
        return FixRewriter.apply(soh, FixRewriter.fixLengthAndChecksum(m));
    }

    private static int independentChecksum(String sohText) {
        int i = sohText.lastIndexOf(S + "10=");
        int sum = 0;
        for (byte b : sohText.substring(0, i + 1).getBytes(StandardCharsets.UTF_8)) sum += b & 0xFF;
        return sum % 256;
    }

    // --- scanner ---

    @Test
    public void scannerFindsMessagesWithDifferentDelimiters() {
        String text = "log: 8=FIX.4.4|9=5|35=0|10=163| and then 8=FIX.4.2^A35=D^A10=000^A\n"
                + "fragment 35=D|55=IBM|54=1 end";
        List<FixScanner.Span> spans = FixScanner.scan(text, SET::isKnownTag);
        assertEquals(3, spans.size());
        assertEquals("|", spans.get(0).delimiter());
        assertEquals("8=FIX.4.4|9=5|35=0|10=163|", text.substring(spans.get(0).start(), spans.get(0).end()));
        assertEquals("^A", spans.get(1).delimiter());
        assertEquals(3, spans.get(1).fields().size());
        assertEquals("35=D|55=IBM|54=1", text.substring(spans.get(2).start(), spans.get(2).end()));
    }

    @Test
    public void scannerIgnoresThingsThatAreNotFix() {
        assertTrue(FixScanner.scan("a=1|b=2|c=3", SET::isKnownTag).isEmpty());
        assertTrue(FixScanner.scan("x 55=IBM|54=1 only two", SET::isKnownTag).isEmpty());
        assertTrue(FixScanner.scan("version 1.2=3|4=5|6=7", SET::isKnownTag).isEmpty());
        assertTrue(FixScanner.scan("99001=1|99002=2|99003=3", SET::isKnownTag).isEmpty());
    }

    @Test
    public void checksumStopsAfterThreeDigits() {
        List<FixScanner.Span> spans = FixScanner.scan("8=FIX.4.4|35=0|10=123 trailing text", SET::isKnownTag);
        assertEquals(1, spans.size());
        assertEquals("123", spans.get(0).fields().get(2).value());
    }

    // --- groups ---

    @Test
    public void wellFormedGroupsHaveNoProblems() {
        FixMessage m = parse(valid("8=FIX.4.4|9=0|35=D|11=ORD1|453=2|448=TRADER1|447=D|452=11|448=DESK7|447=D|452=24|"
                + "55=IBM|54=1|60=20260101-10:00:00|40=2|38=100|44=1.5|10=000|"));
        assertEquals("NewOrderSingle", m.typeName());
        assertEquals("[]", kinds(m).toString());
        FixNode.GroupNode g = (FixNode.GroupNode) m.nodes().stream().filter(n -> n instanceof FixNode.GroupNode).findFirst().get();
        assertEquals(2, g.instances().size());
        assertEquals(3, g.instances().get(1).children().size());
        FixNode.FieldNode desk = m.node(8);
        assertEquals("DESK7", desk.field().value());
        assertEquals(2, desk.instance());
        assertSame(g, desk.parentGroup());
        assertEquals("Buy", m.node(12).meaning());
    }

    @Test
    public void countMismatch() {
        FixMessage m = parse("35=D|453=3|448=A|452=11|448=B|452=24|55=IBM|");
        assertEquals("[GROUP_COUNT]", kinds(m).toString());
        Problem p = m.problems().get(0);
        assertEquals(2, p.fixCount());
        assertEquals("453=3", m.text().substring(p.start(), p.end()));
        assertEquals("NoPartyIDs (453) says 3, but 2 instances found", p.message());
    }

    @Test
    public void duplicateAndOutOfOrderFields() {
        FixMessage m = parse("35=D|453=2|448=A|452=11|452=24|448=B|447=D|452=1|55=IBM|");
        assertEquals("[GROUP_FIELD_ORDER]", kinds(m).toString());
        assertEquals("PartyRole (452) appears twice in instance 1 of NoPartyIDs", m.problems().get(0).message());

        m = parse("35=D|453=1|448=A|452=11|447=D|55=IBM|");
        assertEquals("[GROUP_FIELD_ORDER]", kinds(m).toString());
        assertEquals("PartyIDSource (447) is out of order in instance 1 of NoPartyIDs: it must come before PartyRole (452)",
                m.problems().get(0).message());
    }

    @Test
    public void instanceWithoutDelimiter() {
        FixMessage m = parse("35=D|453=1|452=11|448=A|55=IBM|");
        assertEquals("[GROUP_MISSING_DELIMITER]", kinds(m).toString());

        // the first instance takes the next PartyID in, rather than becoming a third instance
        m = parse("35=D|453=2|452=11|448=A|448=B|452=24|55=IBM|");
        assertEquals("[GROUP_MISSING_DELIMITER]", kinds(m).toString());
        FixNode.GroupNode g = group(m, 453);
        assertEquals(2, g.instances().size());
        assertEquals(2, g.instances().get(0).children().size());
    }

    @Test
    public void nestedGroups() {
        FixMessage m = parse("35=D|453=1|448=A|802=2|523=X|803=1|523=Y|803=2|55=IBM|");
        assertEquals("[]", kinds(m).toString());
        FixNode.FieldNode y = m.node(6);
        assertEquals("Y", y.field().value());
        assertEquals("NoPartySubIDs", y.parentGroup().def().name());
        assertEquals(2, y.instance());
    }

    @Test
    public void unknownTagStaysInItsInstance() {
        FixMessage m = parse("35=D|453=2|448=A|452=11|5999=x|448=B|452=24|5999=y|55=IBM|");
        // the group is not cut off: both instances, each with the stray tag, and no count problem
        assertEquals("[GROUP_UNKNOWN_TAG, GROUP_UNKNOWN_TAG]", kinds(m).toString());
        assertEquals("Unknown tag 5999 in instance 1 of NoPartyIDs; if it belongs to the group, add it to a custom dictionary",
                m.problems().get(0).message());
        FixNode.GroupNode g = group(m, 453);
        assertEquals(2, g.instances().size());
        assertEquals(3, g.instances().get(1).children().size());
        assertSame(g, m.node(7).parentGroup());   // 5999=y, the last field of instance 2
        assertNull(m.node(8).parentGroup());      // 55=IBM is back at the top level
    }

    @Test
    public void messageFieldEndsTheGroup() {
        // Text (58) is a field of NewOrderSingle itself, so it ends the group rather than joining the instance
        FixMessage m = parse("35=D|453=1|448=A|58=hello|55=IBM|");
        assertEquals("[]", kinds(m).toString());
        assertNull(m.node(3).parentGroup());
    }

    @Test
    public void unknownMessageTypeUsesTheGroupThatFits() {
        // NoMDEntries starts with MDEntryType (269) in a snapshot and with MDUpdateAction (279) in an incremental refresh
        FixMessage m = parse("35=U9|268=2|279=0|269=0|270=1.5|279=1|269=1|270=1.6|");
        assertEquals("[]", kinds(m).toString());
        assertEquals(2, group(m, 268).instances().size());
        assertEquals(279, group(m, 268).def().delimiterTag());
    }

    @Test
    public void userDefinedMessageTypeIsNotAnInvalidValue() {
        assertEquals("[]", kinds(parse("35=U1|55=IBM|54=1|")).toString());
        assertEquals("[ENUM_VALUE]", kinds(parse("35=ZZ|55=IBM|54=1|")).toString());
    }

    private static FixNode.GroupNode group(FixMessage m, int countTag) {
        return (FixNode.GroupNode) m.nodes().stream()
                .filter(n -> n instanceof FixNode.GroupNode && ((FixNode.GroupNode) n).count().field().tag() == countTag)
                .findFirst().orElseThrow();
    }

    @Test
    public void countNotANumber() {
        assertTrue(kinds(parse("35=D|453=x|448=A|55=IBM|")).contains(Problem.Kind.GROUP_COUNT_NOT_A_NUMBER));
    }

    // --- length, checksum, enums ---

    @Test
    public void lengthAndChecksum() {
        String good = valid("8=FIX.4.4|9=1|35=0|49=A|56=B|34=1|52=20260101-10:00:00|10=999|");
        FixMessage m = parse(good);
        assertEquals("[]", kinds(m).toString());
        assertEquals(String.format("%03d", independentChecksum(good)), m.value(10));
        String body = good.substring(good.indexOf(S + "35=") + 1, good.lastIndexOf(S + "10=") + 1);
        assertEquals(String.valueOf(body.length()), m.value(9));

        FixMessage bad = parse(good.replace("9=" + body.length(), "9=1").replace("49=A", "49=C"));
        assertEquals("[BODY_LENGTH, CHECKSUM]", kinds(bad).toString());
    }

    @Test
    public void insertsMissingLengthAndChecksum() {
        String text = "8=FIX.4.4|35=0|49=A|56=B|";
        FixMessage m = parse(text);
        String fixed = FixRewriter.apply(text, FixRewriter.fixLengthAndChecksum(m));
        assertTrue(fixed, fixed.startsWith("8=FIX.4.4|9=15|35=0|49=A|56=B|10="));
        FixMessage again = parse(fixed);
        assertEquals("[]", kinds(again).toString());
    }

    @Test
    public void unknownPartsSkipLengthAndChecksum() {
        FixMessage m = parse("8=FIX.4.4|9=1|35=D|11=" + FixScanner.UNKNOWN + "|10=000|");
        assertEquals("[]", kinds(m).toString());
        assertFalse(FixRewriter.canFixLengthAndChecksum(m));
    }

    @Test
    public void invalidEnumValue() {
        FixMessage m = parse("35=D|54=Z|55=IBM|");
        assertEquals("[ENUM_VALUE]", kinds(m).toString());
        assertEquals("54=Z", m.text().substring(m.problems().get(0).start(), m.problems().get(0).end()));
    }

    // --- rewrites ---

    @Test
    public void convertDelimiterAndReadable() {
        String text = "8=FIX.4.4" + S + "35=0" + S + "10=000" + S;
        FixMessage m = parse(text);
        assertEquals("8=FIX.4.4|35=0|10=000|", FixRewriter.apply(text, FixRewriter.convertDelimiter(m, "|")));
        assertEquals("8=FIX.4.4|35=0|10=000|", FixRewriter.readable(m));
        assertTrue(FixRewriter.convertDelimiter(m, S).isEmpty());
    }

    @Test
    public void setCount() {
        String text = "35=D|453=3|448=A|55=IBM|";
        FixMessage m = parse(text);
        assertEquals("35=D|453=1|448=A|55=IBM|", FixRewriter.apply(text, List.of(FixRewriter.setCount(m.first(453), 1))));
    }

    @Test
    public void fixtUsesApplicationDictionary() {
        FixMessage m = parse("8=FIXT.1.1|35=D|1128=9|453=1|448=A|55=IBM|");
        assertEquals("NewOrderSingle", m.typeName());
        assertTrue(m.dictionary().label().contains("5.0"));
    }
}
