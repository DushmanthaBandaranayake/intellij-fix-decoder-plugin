package com.dush.intellij.fixdecoder;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.IntentionActionDelegate;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.util.Iconable;

import java.util.List;
import java.util.stream.Collectors;

/** Every menu entry of the plugin shows the FIX Decoder icon. */
public class MenuIconTest extends FixTestCase {

    public void testEditorMenuActions() {
        for (String id : List.of("FixDecoder.EditorPopup", "FixDecoder.Decode", "FixDecoder.CopyReadable")) {
            AnAction action = ActionManager.getInstance().getAction(id);
            assertNotNull(id, action);
            assertNotNull(id + " has an icon", action.getTemplatePresentation().getIcon());
        }
    }

    public void testAltEnterEntries() {
        // a message with a broken group and wrong 9 and 10: every intention and quick fix of the plugin applies
        java("String m = \"8=FIX.4.4|9=5|35=D|453=2|448=A|452=11|55=IBM|10=000|<caret>\";");
        myFixture.enableInspections(new com.dush.intellij.fixdecoder.inspection.FixRepeatingGroupInspection(),
                new com.dush.intellij.fixdecoder.inspection.FixLengthChecksumInspection());
        myFixture.doHighlighting();
        List<String> seen = new java.util.ArrayList<>();
        for (int offset : new int[]{text().indexOf("9=5"), text().indexOf("453=2"), text().indexOf("55=IBM")}) {
            myFixture.getEditor().getCaretModel().moveToOffset(offset);
            for (IntentionAction a : myFixture.getAvailableIntentions()) {
                IntentionAction inner = IntentionActionDelegate.unwrap(a);
                Object fix = inner instanceof com.intellij.codeInspection.ex.QuickFixWrapper
                        ? ((com.intellij.codeInspection.ex.QuickFixWrapper) inner).getFix() : inner;
                if (!fix.getClass().getName().startsWith("com.dush.intellij.fixdecoder")) continue;
                seen.add(a.getText());
                assertTrue(a.getText() + " shows the plugin icon",
                        fix instanceof Iconable && ((Iconable) fix).getIcon(0) == FixDecoderIcons.DECODER);
            }
        }
        String all = seen.stream().distinct().sorted().collect(Collectors.joining(", "));
        assertTrue(all, all.contains("Decode FIX message"));
        assertTrue(all, all.contains("Recompute BodyLength and CheckSum"));
        assertTrue(all, all.contains("Set count to 1"));
        assertTrue(all, all.contains("Convert FIX delimiter to SOH"));
    }
}
