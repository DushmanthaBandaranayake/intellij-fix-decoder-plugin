package com.dush.intellij.fixdecoder.core.parse;

import com.dush.intellij.fixdecoder.core.dictionary.DictionarySet;
import org.junit.Test;

import static org.junit.Assert.*;

public class FixTextTest {
    private static final String S = FixScanner.SOH;
    /** One backslash, built so that no unicode escape appears in this source file. */
    private static final String BS = String.valueOf((char) 92);

    @Test
    public void unicodeEscapesAsCopiedFromJava() {
        String pasted = "8=FIX.4.4" + BS + "u00019=10" + BS + "u000135=D" + BS + "u000149=CLIENT" + BS + "u000156=BROKER"
                + BS + "u000134=1" + BS + "u000152=20260101-10:00:00";
        String text = FixText.fromSource(pasted);
        assertEquals("8=FIX.4.4" + S + "9=10" + S + "35=D" + S + "49=CLIENT" + S + "56=BROKER" + S + "34=1" + S + "52=20260101-10:00:00", text);
        FixMessage m = FixParser.parse(text, DictionarySet.bundled(), DictionarySet.FIX44);
        assertNotNull(m);
        assertEquals("NewOrderSingle", m.typeName());
        assertEquals(7, m.fields().size());
    }

    @Test
    public void octalHexAndDoubledBackslashes() {
        assertEquals("8=FIX.4.4" + S + "35=0" + S, FixText.fromSource("8=FIX.4.4" + BS + "00135=0" + BS + "x01"));
        assertEquals("8=FIX.4.4" + S + "35=0", FixText.fromSource("8=FIX.4.4" + BS + BS + "u000135=0"));
    }

    @Test
    public void concatenatedLiterals() {
        String pasted = "\"8=FIX.4.4" + BS + "u0001\" + SOH_IS_NOT_JOINED + \"35=D|\"\n  + \"55=IBM|\"";
        assertEquals("8=FIX.4.4" + S + "\" + SOH_IS_NOT_JOINED + \"35=D|55=IBM|", FixText.fromSource(pasted));
        assertEquals("35=D|55=IBM|", FixText.fromSource("\"35=D|\" + \"55=IBM|\""));
    }

    @Test
    public void plainTextIsUnchanged() {
        assertEquals("8=FIX.4.4|35=0|10=000|", FixText.fromSource("  8=FIX.4.4|35=0|10=000|  "));
    }
}
