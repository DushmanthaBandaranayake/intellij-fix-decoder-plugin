package com.dush.intellij.fixdecoder.toolwindow;

import com.dush.intellij.fixdecoder.FixTestCase;
import com.dush.intellij.fixdecoder.core.dictionary.DictionarySet;
import com.dush.intellij.fixdecoder.core.parse.FixParser;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.dush.intellij.fixdecoder.settings.FixActiveDictionary;
import com.dush.intellij.fixdecoder.settings.FixDictionaries;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;

import java.util.List;
import java.util.stream.Collectors;

/** One tab of the FIX Decoder tool window per message. */
public class TabsTest extends FixTestCase {
    private static final String TWO = "String a = \"8=FIX.4.4|35=D|55=IBM|54=1|\";\n"
            + "String b = \"8=FIX.4.4|35=8|55=IBM|54=2|\";";

    private ContentManager contents() {
        ContentManager contents = ApplicationManager.getApplication().getService(ContentFactory.class)
                .createContentManager(true, getProject());
        Disposer.register(getTestRootDisposable(), contents);
        FixDecoderToolWindow.install(getProject(), contents);
        return contents;
    }

    private static String titles(ContentManager contents) {
        return java.util.Arrays.stream(contents.getContents()).map(Content::getDisplayName).collect(Collectors.joining(", "));
    }

    public void testTabPerMessage() {
        java(TWO);
        Document document = myFixture.getEditor().getDocument();
        List<FoundMessage> found = messages();
        ContentManager contents = contents();

        // an empty tab to start with, which cannot be closed
        assertEquals(1, contents.getContentCount());
        assertFalse(contents.getContent(0).isCloseable());

        // the first message takes the empty tab over
        FixDecoderToolWindow.showMessage(getProject(), contents, document, found.get(0));
        assertEquals("NewOrderSingle D", titles(contents));
        assertTrue(contents.getContent(0).isCloseable());
        assertEquals("Orders.java:2 · FIX 4.4", contents.getContent(0).getDescription());

        // another message gets a tab of its own, which is selected
        FixDecoderToolWindow.showMessage(getProject(), contents, document, found.get(1));
        assertEquals("NewOrderSingle D, ExecutionReport 8", titles(contents));
        assertSame(contents.getContent(1), contents.getSelectedContent());

        // decoding a message again selects its tab
        FixDecoderToolWindow.showMessage(getProject(), contents, document, messages().get(0));
        assertEquals(2, contents.getContentCount());
        assertSame(contents.getContent(0), contents.getSelectedContent());

        // pasted text always gets a new tab
        FixDecoderToolWindow.showPasted(getProject(), contents, FixParser.parse("35=A|98=0|108=30|",
                FixDictionaries.get(getProject()).set(), FixDictionaries.get(getProject()).defaultVersion()));
        assertEquals("NewOrderSingle D, ExecutionReport 8, Pasted: Logon A", titles(contents));
        assertEquals("pasted · FIX 4.4", contents.getContent(2).getDescription());

        // closing every tab leaves the empty one
        contents.removeAllContents(true);
        assertEquals(1, contents.getContentCount());
        assertTrue(((FixDecoderPanel) contents.getContent(0).getComponent()).isEmpty());
        assertFalse(contents.getContent(0).isCloseable());
    }

    public void testTitleFollowsEdits() {
        java(TWO);
        Document document = myFixture.getEditor().getDocument();
        ContentManager contents = contents();
        FixDecoderPanel panel = FixDecoderToolWindow.showMessage(getProject(), contents, document, messages().get(0));
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            int at = document.getText().indexOf("35=D") + 3;
            document.replaceString(at, at + 1, "F");
        });
        panel.refreshNow();
        assertEquals("OrderCancelRequest F", titles(contents));
    }

    public void testEveryTabFollowsTheDictionary() {
        java(TWO);
        Document document = myFixture.getEditor().getDocument();
        ContentManager contents = contents();
        FixDecoderToolWindow.showMessage(getProject(), contents, document, messages().get(0));
        FixDecoderToolWindow.showMessage(getProject(), contents, document, messages().get(1));
        FixActiveDictionary.choose(getProject(), DictionarySet.FIX42);
        for (FixDecoderPanel p : FixDecoderToolWindow.panels(contents)) p.decodeAgain();
        for (FixDecoderPanel p : FixDecoderToolWindow.panels(contents)) p.refreshNow();
        for (FixDecoderPanel p : FixDecoderToolWindow.panels(contents)) {
            assertEquals(DictionarySet.FIX42, p.chosenDictionary());
            assertEquals("FIX 4.2", p.message().dictionary().label());
        }
        assertTrue(contents.getContent(1).getDescription(), contents.getContent(1).getDescription().endsWith("FIX 4.2"));
    }
}
