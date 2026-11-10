package com.dush.intellij.fixdecoder.inspection;

import com.dush.intellij.fixdecoder.FixTestCase;
import com.dush.intellij.fixdecoder.core.parse.Problem;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.dush.intellij.fixdecoder.settings.FixDecoderSettings;
import com.intellij.codeInsight.intention.IntentionAction;

import java.util.List;
import java.util.stream.Collectors;

public class InspectionTest extends FixTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new FixRepeatingGroupInspection(), new FixLengthChecksumInspection(), new FixEnumValueInspection());
    }

    public void testGroupProblemsAreHighlightedInsideTheLiteral() {
        java("String m = \"35=D|<error descr=\"NoPartyIDs (453) says 3, but 2 instances found\">453=3</error>|448=A|452=11|"
                + "<error descr=\"PartyRole (452) appears twice in instance 1 of NoPartyIDs\">452=12</error>|448=B|452=24|55=IBM|"
                + "<error descr=\"'Z' is not a valid value for Side (54)\">54=Z</error>|\";");
        myFixture.checkHighlighting(true, false, true);
    }

    public void testWrongLengthAndChecksumAreErrorsOnTheWholeField() {
        java("String m = \"8=FIX.4.4|<error descr=\"BodyLength (9) should be 5\">9=1</error>|35=0|"
                + "<error descr=\"CheckSum (10) should be 159\">10=000</error>|\";");
        myFixture.checkHighlighting(true, false, true);
    }

    public void testFileWithProblemsIsMarked() {
        java("String m = \"35=D|453=<caret>3|448=A|452=11|448=B|452=24|55=IBM|\";");
        myFixture.doHighlighting();
        assertTrue(FixProblemFiles.isReported(myFixture.getFile().getVirtualFile()));
        assertTrue(new FixProblemFiles.Filter().value(myFixture.getFile().getVirtualFile()));

        myFixture.launchAction(myFixture.findSingleIntention("Set count to 2"));
        myFixture.doHighlighting();
        assertFalse(FixProblemFiles.isReported(myFixture.getFile().getVirtualFile()));
    }

    public void testSwitchedOffChecksDoNotMarkTheFile() {
        FixDecoderSettings.of(getProject()).groupValidation = false;
        java("String m = \"35=D|453=3|448=A|55=IBM|\";");
        myFixture.doHighlighting();
        assertFalse(FixProblemFiles.isReported(myFixture.getFile().getVirtualFile()));
    }

    public void testSetCountQuickFix() {
        java("String m = \"35=D|453=<caret>3|448=A|452=11|448=B|452=24|55=IBM|\";");
        myFixture.launchAction(myFixture.findSingleIntention("Set count to 2"));
        assertTrue(text(), text().contains("\"35=D|453=2|448=A|"));
    }

    public void testRecomputeKeepsUnicodeEscapes() {
        java("String m = \"8=FIX.4.4\\u00019=1\\u000135=0\\u000149=A\\u000156=B\\u000110=<caret>000\\u0001\";");
        myFixture.launchAction(myFixture.findSingleIntention("Recompute BodyLength and CheckSum"));
        String text = text();
        assertTrue(text, text.contains("\"8=FIX.4.4\\u00019=15\\u000135=0\\u000149=A\\u000156=B\\u000110="));
        assertEquals("[]", lengthAndChecksumProblems(only()).toString());
    }

    public void testRecomputeKeepsOctalEscapes() {
        java("String m = \"8=FIX.4.4\\0019=1\\00135=0\\00110=<caret>000\\001\";");
        myFixture.launchAction(myFixture.findSingleIntention("Recompute BodyLength and CheckSum"));
        assertTrue(text(), text().contains("\"8=FIX.4.4\\0019=5\\00135=0\\00110="));
        assertEquals("[]", lengthAndChecksumProblems(only()).toString());
    }

    public void testNoFixWhenTheValueComesFromAConstant() {
        java("static final String COUNT = \"3\";\n"
                + "String m = \"35=D|45<caret>3=\" + COUNT + \"|448=A|55=IBM|\";");
        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        assertTrue(fixes.stream().noneMatch(f -> f.getText().startsWith("Set count")));
        assertFalse(myFixture.doHighlighting().isEmpty());
    }

    public void testSettingsSwitchOffChecks() {
        FixDecoderSettings.of(getProject()).groupValidation = false;
        FixDecoderSettings.of(getProject()).lengthChecksumFix = false;
        java("String m = \"8=FIX.4.4|9=1|35=D|453=3|448=A|55=IBM|10=000|\";");
        myFixture.checkHighlighting(true, false, true);
        assertTrue(myFixture.getAllQuickFixes().isEmpty());
    }

    public void testPlainTextFile() {
        myFixture.configureByText("orders.txt",
                "IN 8=FIX.4.4|35=D|<error descr=\"NoPartyIDs (453) says 2, but 1 instance found\">453=2</error>|448=A|55=IBM|\n");
        myFixture.checkHighlighting(true, false, true);
        myFixture.launchAction(myFixture.findSingleIntention("Set count to 1"));
        assertTrue(text(), text().contains("453=1|448=A"));
    }

    private static List<Problem.Kind> lengthAndChecksumProblems(FoundMessage m) {
        return m.message().problems().stream().map(Problem::kind)
                .filter(k -> k.category() == Problem.Category.LENGTH_CHECKSUM).collect(Collectors.toList());
    }
}
