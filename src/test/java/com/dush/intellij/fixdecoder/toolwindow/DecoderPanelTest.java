package com.dush.intellij.fixdecoder.toolwindow;

import com.dush.intellij.fixdecoder.FixTestCase;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.dush.intellij.fixdecoder.hover.FieldInfo;
import com.dush.intellij.fixdecoder.core.dictionary.DictionarySet;
import com.dush.intellij.fixdecoder.core.parse.FixMessage;
import com.dush.intellij.fixdecoder.settings.FixActiveDictionary;
import com.dush.intellij.fixdecoder.settings.FixDecoderSettings;
import com.dush.intellij.fixdecoder.settings.FixDictionaries;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Disposer;

import java.awt.datatransfer.DataFlavor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

public class DecoderPanelTest extends FixTestCase {
    private static final String ORDER = "String m = \"8=FIX.4.4|35=D|11=ORD1|453=2|448=TRADER1|452=11|\"\n"
            + "    + \"448=DESK7|452=24|55=IBM|54=1|\";";

    private FixDecoderPanel panel() {
        FixDecoderPanel panel = new FixDecoderPanel(getProject());
        Disposer.register(getTestRootDisposable(), panel);
        return panel;
    }

    public void testHoverText() {
        java(ORDER);
        FoundMessage m = only();
        String side = FieldInfo.html(m.message(), m.message().first(54));
        assertTrue(side, side.contains("<b>Side</b>"));
        assertTrue(side, side.contains("<b>Buy</b>"));
        assertTrue(side, side.contains("Required in NewOrderSingle (35=D)"));
        assertTrue(side, side.contains("Other values: 2 Sell"));
        String desk = FieldInfo.html(m.message(), m.message().fields().get(6));
        assertTrue(desk, desk.contains("In NoPartyIDs (453), instance 2 of 2"));
        String type = FieldInfo.html(m.message(), m.message().first(35));
        assertTrue(type, type.contains("= <b>NewOrderSingle</b>"));
    }

    public void testRowsAndGroups() {
        java(ORDER);
        FixDecoderPanel panel = panel();
        panel.show(myFixture.getEditor().getDocument(), only());
        String tags = panel.rows().stream().map(r -> r.tag).collect(Collectors.joining(","));
        assertEquals("8,35,11,453,[1],448,452,[2],448,452,55,54", tags);
        assertFalse("the holder row must stay hidden", panel.isRootVisible());
        assertEquals("Buy", panel.rows().get(11).meaning);
        assertEquals("2 instances", panel.rows().get(3).meaning);
        assertTrue(panel.headerText(), panel.headerText().startsWith("NewOrderSingle (35=D) · FIX 4.4 · Orders.java:2"));
    }

    public void testSearch() {
        java(ORDER);
        FixDecoderPanel panel = panel();
        panel.show(myFixture.getEditor().getDocument(), only());
        try {
            // nothing is hidden: the match is highlighted and selected
            panel.setSearchText("desk");
            assertEquals(12, panel.shownTags().size());
            assertEquals("[448]", panel.matchedTags().toString());
            assertEquals("DESK7", panel.selectedRow().value);
            assertTrue(panel.headerText(), panel.headerText().contains("· match 1 of 1"));

            // Enter moves to the next match and wraps; Shift+Enter goes back
            panel.setSearchText("452");
            assertEquals("[452, 452]", panel.matchedTags().toString());
            assertEquals("11", panel.selectedRow().value);
            panel.nextMatch(1);
            assertEquals("24", panel.selectedRow().value);
            assertTrue(panel.headerText(), panel.headerText().contains("· match 2 of 2"));
            panel.nextMatch(1);
            assertEquals("11", panel.selectedRow().value);
            panel.nextMatch(-1);
            assertEquals("24", panel.selectedRow().value);

            // names and meanings count too; instance rows don't
            panel.setSearchText("NoPartyIDs");
            assertEquals("[453]", panel.matchedTags().toString());
            panel.setSearchText("BUY");
            assertEquals("[54]", panel.matchedTags().toString());

            panel.setSearchText("nothing like this");
            assertTrue(panel.matchedTags().isEmpty());
            assertEquals(12, panel.shownTags().size());
            assertTrue(panel.headerText(), panel.headerText().contains("· no matches"));

            // rebuilding (flat view) keeps the search
            panel.setSearchText("452");
            panel.setFlat(true);
            assertEquals(10, panel.shownTags().size());
            assertEquals("[452, 452]", panel.matchedTags().toString());

            panel.setSearchText("");
            assertTrue(panel.matchedTags().isEmpty());
            assertFalse(panel.headerText().contains("match"));
        } finally {
            panel.setFlat(false);
            panel.setSearchText("");
        }
    }

    public void testCopyValue() throws Exception {
        java(ORDER);
        FixDecoderPanel panel = panel();
        panel.show(myFixture.getEditor().getDocument(), only());
        assertNull("nothing selected, nothing copied", panel.copySelectedValues());
        try {
            panel.setSearchText("desk");   // selects the DESK7 row
            assertEquals("DESK7", panel.copySelectedValues());
            assertEquals("DESK7", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor));
        } finally {
            panel.setSearchText("");
        }
    }

    public void testDictionaryChoice() {
        java(ORDER);
        FixDecoderPanel panel = panel();
        panel.show(myFixture.getEditor().getDocument(), only());
        assertEquals(FixActiveDictionary.AUTO, panel.chosenDictionary());
        assertTrue(panel.headerText(), panel.headerText().contains("· FIX 4.4 ·"));

        // FIX 4.2 has no parties: decoded again without the group, whatever BeginString says
        panel.setDictionary(DictionarySet.FIX42);
        panel.refreshNow();
        assertTrue(panel.headerText(), panel.headerText().contains("· FIX 4.2 ·"));
        assertEquals("8,35,11,453,448,452,448,452,55,54", panel.rows().stream().map(r -> r.tag).collect(Collectors.joining(",")));
        assertEquals("(unknown)", panel.rows().get(3).name);
        assertEquals("Buy", panel.rows().get(9).meaning);
        // the editor follows: hover and the inspections read the same message
        assertEquals("FIX 4.2", only().message().dictionary().label());
        // still linked to the editor
        panel.click(panel.rows().get(6), FixDecoderPanel.COL_VALUE, false);
        assertEquals("DESK7", myFixture.getEditor().getSelectionModel().getSelectedText());

        // a new panel shows the choice in effect
        assertEquals(DictionarySet.FIX42, panel().chosenDictionary());

        panel.setDictionary(FixActiveDictionary.AUTO);
        panel.refreshNow();
        assertTrue(panel.headerText(), panel.headerText().contains("· FIX 4.4 ·"));
        assertEquals("8,35,11,453,[1],448,452,[2],448,452,55,54",
                panel.rows().stream().map(r -> r.tag).collect(Collectors.joining(",")));
        assertEquals("FIX 4.4", only().message().dictionary().label());
    }

    public void testCustomDictionaryIsListed() throws Exception {
        Path file = Files.createTempFile("venue", ".xml");
        Files.write(file, ("<fix major=\"4\" minor=\"4\"><fields>"
                + "<field number=\"5001\" name=\"VenueFlag\" type=\"CHAR\"><value enum=\"Y\" description=\"YES\"/></field>"
                + "</fields></fix>").getBytes(StandardCharsets.UTF_8));
        file.toFile().deleteOnExit();
        String name = file.getFileName().toString();
        String key = FixActiveDictionary.FILE + file;

        java("String m = \"8=FIX.4.2|35=D|11=ORD1|5001=Y|55=IBM|54=1|\";");
        FixDecoderPanel panel = panel();
        panel.show(myFixture.getEditor().getDocument(), only());
        assertEquals("[Auto, FIX 4.2, FIX 4.4, FIXT 1.1 / FIX 5.0 SP2]", panel.dictionaryChoices().toString());
        assertEquals("(unknown)", panel.rows().get(3).name);

        // added in the settings: listed in the table, as the settings page does on Apply
        FixDecoderSettings.of(getProject()).customDictionaries.add(file.toString());
        FixDictionaries.get(getProject()).reload();
        panel.decodeAgain();
        panel.refreshNow();
        assertEquals("[Auto, FIX 4.2, FIX 4.4, FIXT 1.1 / FIX 5.0 SP2, " + name + "]", panel.dictionaryChoices().toString());
        // the file is for FIX 4.4 and the message says 4.2, so auto doesn't use it
        assertEquals("(unknown)", panel.rows().get(3).name);

        // chosen: FIX 4.4 with that file, in the table and in the editor
        panel.setDictionary(key);
        panel.refreshNow();
        assertEquals("VenueFlag", panel.rows().get(3).name);
        assertEquals("Yes", panel.rows().get(3).meaning);
        assertTrue(panel.headerText(), panel.headerText().contains("· FIX 4.4 + " + name + " ·"));
        FixMessage inEditor = only().message();
        assertEquals("Yes", inEditor.meaning(inEditor.first(5001)));

        // a version alone is the standard dictionary, without the file
        panel.setDictionary(DictionarySet.FIX44);
        panel.refreshNow();
        assertEquals("(unknown)", panel.rows().get(3).name);
        assertTrue(panel.headerText(), panel.headerText().contains("· FIX 4.4 ·"));

        // removed from the settings: no longer listed, and the choice goes back to auto
        panel.setDictionary(key);
        FixDecoderSettings.of(getProject()).customDictionaries.clear();
        FixDictionaries.get(getProject()).reload();
        panel.decodeAgain();
        panel.refreshNow();
        assertEquals("[Auto, FIX 4.2, FIX 4.4, FIXT 1.1 / FIX 5.0 SP2]", panel.dictionaryChoices().toString());
        assertEquals(FixActiveDictionary.AUTO, panel.chosenDictionary());
        assertEquals("(unknown)", panel.rows().get(3).name);
        assertEquals("FIX 4.2", only().message().dictionary().label());
    }

    public void testFlatList() {
        java(ORDER);
        FixDecoderPanel panel = panel();
        panel.show(myFixture.getEditor().getDocument(), only());
        try {
            panel.setFlat(true);
            String tags = panel.rows().stream().map(r -> r.tag).collect(Collectors.joining(","));
            assertEquals("8,35,11,453,448,452,448,452,55,54", tags);
            assertEquals("2 instances", panel.rows().get(3).meaning);
            // still linked to the editor
            panel.click(panel.rows().get(6), FixDecoderPanel.COL_VALUE, false);
            assertEquals("DESK7", myFixture.getEditor().getSelectionModel().getSelectedText());

            panel.setFlat(false);
            assertEquals("8,35,11,453,[1],448,452,[2],448,452,55,54",
                    panel.rows().stream().map(r -> r.tag).collect(Collectors.joining(",")));
        } finally {
            panel.setFlat(false);
        }
    }

    public void testClickSelectsValueInConcatenation() {
        java(ORDER);
        FixDecoderPanel panel = panel();
        panel.show(myFixture.getEditor().getDocument(), only());
        FixDecoderPanel.Row desk = panel.rows().get(8);
        assertEquals("DESK7", desk.value);
        panel.click(desk, FixDecoderPanel.COL_VALUE, false);
        assertEquals("DESK7", myFixture.getEditor().getSelectionModel().getSelectedText());
        panel.click(desk, FixDecoderPanel.COL_TAG, false);
        assertEquals("448=", myFixture.getEditor().getSelectionModel().getSelectedText());
    }

    public void testJumpToSourceOnClick() {
        java(ORDER);
        FixDecoderPanel panel = panel();
        panel.show(myFixture.getEditor().getDocument(), only());
        FixDecoderPanel.Row desk = panel.rows().get(8);
        assertTrue("on by default", FixDecoderPanel.isJumpOnClick());
        try {
            FixDecoderPanel.setJumpOnClick(false);
            myFixture.getEditor().getSelectionModel().removeSelection();
            int caret = myFixture.getEditor().getCaretModel().getOffset();
            // off: a single click leaves the editor alone
            panel.click(desk, FixDecoderPanel.COL_VALUE, false);
            assertNull(myFixture.getEditor().getSelectionModel().getSelectedText());
            assertEquals(caret, myFixture.getEditor().getCaretModel().getOffset());
            // a double click still jumps
            panel.click(desk, FixDecoderPanel.COL_VALUE, true);
            assertEquals("DESK7", myFixture.getEditor().getSelectionModel().getSelectedText());

            FixDecoderPanel.setJumpOnClick(true);
            panel.click(panel.rows().get(2), FixDecoderPanel.COL_VALUE, false);
            assertEquals("ORD1", myFixture.getEditor().getSelectionModel().getSelectedText());
        } finally {
            FixDecoderPanel.setJumpOnClick(true);
        }
    }

    public void testFollowsEdits() {
        java(ORDER);
        FixDecoderPanel panel = panel();
        Document doc = myFixture.getEditor().getDocument();
        panel.show(doc, only());
        int at = doc.getText().indexOf("ORD1");
        WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.replaceString(at, at + 4, "ORD2"));
        panel.refreshNow();
        assertEquals("ORD2", panel.rows().get(2).value);
        assertFalse(panel.isStale());

        WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.setText("class Orders {}"));
        panel.refreshNow();
        assertTrue(panel.isStale());
    }
}
