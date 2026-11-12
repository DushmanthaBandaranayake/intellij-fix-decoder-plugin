package com.dush.intellij.fixdecoder.java;

import com.dush.intellij.fixdecoder.FixTestCase;
import com.intellij.psi.PsiElement;

public class DocumentationProviderTest extends FixTestCase {

    private String docAt(String marker, int into) {
        int offset = text().indexOf(marker) + into;
        PsiElement context = myFixture.getFile().findElementAt(offset);
        FixDocumentationProvider p = new FixDocumentationProvider();
        PsiElement element = p.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), context, offset);
        return element == null ? null : p.generateHoverDoc(element, context);
    }

    public void testFieldDetailsInsteadOfNoDocumentation() {
        java("String m = \"35=D|55=IBM|54=1|\" + \"453=1|448=A|\";\nString other = \"hello\";");
        String side = docAt("54=1", 1);
        assertNotNull(side);
        assertTrue(side, side.contains("<b>Side</b>"));
        assertTrue(side, side.contains("<b>Buy</b>"));
        assertFalse("IntelliJ's popup can't follow the link", side.contains("Decode message"));

        String party = docAt("448=A", 4);
        assertTrue(party, party.contains("In NoPartyIDs (453), instance 1 of 1"));

        assertNull(docAt("hello", 2));
    }
}
