package com.dush.intellij.fixdecoder;

import com.dush.intellij.fixdecoder.core.parse.FixMessage;
import com.dush.intellij.fixdecoder.core.parse.FixScanner;
import com.dush.intellij.fixdecoder.core.parse.RawField;
import com.dush.intellij.fixdecoder.extract.FoundMessage;

import java.util.List;

public class FinderTest extends FixTestCase {

    public void testSingleLiteralWithUnicodeEscapes() {
        java("String m = \"8=FIX.4.4\\u00019=12\\u000135=D\\u000154=1\\u000110=000\\u0001\";");
        FoundMessage found = only();
        FixMessage m = found.message();
        assertEquals(FixScanner.SOH, m.delimiter());
        assertEquals("NewOrderSingle", m.typeName());
        RawField side = m.first(54);
        assertEquals("54", text(found.tagRange(side)));
        assertEquals("1", text(found.valueRange(side)));
        assertEquals("54=1", text(found.fieldRange(side)));
        // the delimiter is written as six characters
        RawField nine = m.first(9);
        assertEquals("\\u0001", text(found.editableRange(nine.end(), nine.delimiterEnd())));
        assertTrue(found.inJavaCode());
    }

    public void testConcatenationWithConstant() {
        java("static final String SOH = \"\\u0001\";\n"
                + "String m = \"8=FIX.4.4\" + SOH + \"35=D\" + SOH\n"
                + "    + \"11=\" + id() + SOH + \"55=IBM\" + SOH;\n"
                + "String id() { return \"x\"; }");
        FoundMessage found = only();
        FixMessage m = found.message();
        assertEquals(4, m.fields().size());
        assertEquals("IBM", text(found.valueRange(m.first(55))));
        assertEquals(String.valueOf(FixScanner.UNKNOWN), m.value(11));
        assertNull(found.editableRange(m.first(11).valueStart(), m.first(11).end()));
        // a delimiter that comes from the constant cannot be edited
        RawField eight = m.first(8);
        assertNull(found.editableRange(eight.end(), eight.delimiterEnd()));
        // an offset in the second line finds its field
        int offset = text().indexOf("55=IBM") + 4;
        assertEquals(55, found.fieldAt(offset).tag());
    }

    public void testPipeDelimitedAndOctal() {
        java("String a = \"35=D|55=IBM|54=2\";\nString b = \"8=FIX.4.2\\00135=0\\001\";");
        List<FoundMessage> all = messages();
        assertEquals(2, all.size());
        assertEquals("|", all.get(0).message().delimiter());
        assertEquals("Sell", all.get(0).message().node(2).meaning());
        assertEquals("\\001", all.get(1).escaper().escape(FixScanner.SOH));
        assertEquals("\\u0001", all.get(0).escaper().escape(FixScanner.SOH));
    }

    public void testTextBlock() {
        java("String m = \"\"\"\n        35=D|55=IBM|54=1|\n        \"\"\";");
        FoundMessage found = only();
        assertEquals("IBM", text(found.valueRange(found.message().first(55))));
    }

    public void testIgnoresOtherStrings() {
        java("String a = \"a=1|b=2|c=3\";\nString b = \"hello\";\nint x = 1;");
        assertTrue(messages().isEmpty());
    }

    public void testPlainText() {
        myFixture.configureByText("orders.log", "10:00 IN 8=FIX.4.4|9=5|35=0|10=163|\n10:01 OUT 35=D|55=IBM|54=1\n");
        List<FoundMessage> all = messages();
        assertEquals(2, all.size());
        assertFalse(all.get(0).inJavaCode());
        assertEquals("163", text(all.get(0).valueRange(all.get(0).message().first(10))));
        assertEquals("54=1", text(all.get(1).fieldRange(all.get(1).message().first(54))));
    }
}
