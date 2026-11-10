package com.dush.intellij.fixdecoder.intention;

import com.dush.intellij.fixdecoder.FixTestCase;
import com.dush.intellij.fixdecoder.core.parse.FixMessage;
import com.dush.intellij.fixdecoder.java.PasteTestAccess;
import com.dush.intellij.fixdecoder.settings.FixDecoderSettings;
import com.intellij.openapi.editor.Inlay;

import java.util.List;
import java.util.stream.Collectors;

public class RewriteTest extends FixTestCase {

    public void testDecodeIsOfferedInsideAMessageOnly() {
        java("String m = \"35=D|55=<caret>IBM|54=1|\";\nString other = \"hello\";");
        assertFalse(myFixture.filterAvailableIntentions("Decode FIX message").isEmpty());
        myFixture.getEditor().getCaretModel().moveToOffset(text().indexOf("hello"));
        assertTrue(myFixture.filterAvailableIntentions("Decode FIX message").isEmpty());
    }

    public void testConvertToPipeAndBack() {
        java("String m = \"8=FIX.4.4\\u000135=0<caret>\\u000110=000\\u0001\";");
        myFixture.launchAction(myFixture.findSingleIntention("Convert FIX delimiter to |"));
        assertTrue(text(), text().contains("\"8=FIX.4.4|35=0|10=000|\""));
        myFixture.launchAction(myFixture.findSingleIntention("Convert FIX delimiter to SOH"));
        assertTrue(text(), text().contains("\"8=FIX.4.4\\u000135=0\\u000110=000\\u0001\""));
    }

    public void testNoConversionWhenADelimiterIsAConstant() {
        java("static final String SOH = \"\\u0001\";\nString m = \"8=FIX.4.4\" + SOH + \"35=0<caret>\" + SOH + \"10=000\" + SOH;");
        assertTrue(myFixture.filterAvailableIntentions("Convert FIX delimiter").isEmpty());
    }

    public void testNoSohConversionInPlainText() {
        myFixture.configureByText("orders.txt", "8=FIX.4.4|35=<caret>0|10=000|");
        assertTrue(myFixture.filterAvailableIntentions("Convert FIX delimiter to SOH").isEmpty());
    }

    public void testAddLengthAndChecksum() {
        java("String m = \"8=FIX.4.4|35=0|49=A|56=<caret>B|\";");
        myFixture.launchAction(myFixture.findSingleIntention("Add FIX BodyLength and CheckSum"));
        assertTrue(text(), text().contains("\"8=FIX.4.4|9=15|35=0|49=A|56=B|10=171|\""));
    }

    public void testAddLengthAndChecksumFollowsTheSetting() {
        FixDecoderSettings.of(getProject()).lengthChecksumFix = false;
        java("String m = \"8=FIX.4.4|35=0|49=A|56=<caret>B|\";");
        assertTrue(myFixture.filterAvailableIntentions("Add FIX BodyLength").isEmpty());
    }

    public void testInlineHintsAreOffByDefault() {
        java("String m = \"35=D|54=1|40=2|55=IBM|\";");
        myFixture.doHighlighting();
        assertTrue(myFixture.getEditor().getInlayModel().getInlineElementsInRange(0, text().length(),
                com.dush.intellij.fixdecoder.hints.FixHintRenderer.class).isEmpty());
    }

    public void testInlineHints() {
        FixDecoderSettings.of(getProject()).inlineHints = true;
        java("String m = \"35=D|54=1|40=2|55=IBM|\";");
        myFixture.doHighlighting();
        List<String> hints = myFixture.getEditor().getInlayModel().getInlineElementsInRange(0, text().length()).stream()
                .map(i -> i.getRenderer().toString().contains("FixHintRenderer") ? hintText(i) : "").collect(Collectors.toList());
        assertEquals("[NewOrderSingle, Buy, Limit]", hints.toString());

        FixDecoderSettings.of(getProject()).inlineHints = false;
        myFixture.type(' ');
        myFixture.doHighlighting();
        assertTrue(myFixture.getEditor().getInlayModel().getInlineElementsInRange(0, text().length(),
                com.dush.intellij.fixdecoder.hints.FixHintRenderer.class).isEmpty());
    }

    private static String hintText(Inlay<?> inlay) {
        return ((com.dush.intellij.fixdecoder.hints.FixHintRenderer) inlay.getRenderer()).text();
    }

    public void testPasteText() {
        java("");
        FixMessage pipe = PasteTestAccess.wholeMessage(getProject(), "  8=FIX.4.4|35=0|10=000|  ");
        assertNotNull(pipe);
        assertEquals("\"8=FIX.4.4\\u000135=0\\u000110=000\\u0001\"", PasteTestAccess.javaText(pipe, false));
        assertEquals("8=FIX.4.4\\u000135=0\\u000110=000\\u0001", PasteTestAccess.javaText(pipe, true));
        assertNull(PasteTestAccess.wholeMessage(getProject(), "String m = \"8=FIX.4.4|35=0|10=000|\"; // and much more code here"));
        assertNull(PasteTestAccess.wholeMessage(getProject(), "hello world"));
    }
}
