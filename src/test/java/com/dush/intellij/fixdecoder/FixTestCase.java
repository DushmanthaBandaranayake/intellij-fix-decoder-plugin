package com.dush.intellij.fixdecoder;

import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.dush.intellij.fixdecoder.settings.FixDecoderSettings;
import com.dush.intellij.fixdecoder.settings.FixDictionaries;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public abstract class FixTestCase extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        // Mock JDK only; the default descriptor downloads JetBrains annotations from Maven, which the tests don't need.
        return new DefaultLightProjectDescriptor();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // The light project is shared between tests: start each one from the default settings.
        FixDecoderSettings.get(getProject()).loadState(new FixDecoderSettings.State());
        FixDictionaries.get(getProject()).reload();
        // The descriptor has no JDK. Constant evaluation needs String to resolve, so declare the few classes used.
        myFixture.addClass("package java.lang; public class Object { }");
        myFixture.addClass("package java.lang; public final class String implements CharSequence { }");
        myFixture.addClass("package java.lang; public interface CharSequence { }");
    }

    /** A Java class whose body is {@code body}. */
    protected void java(String body) {
        myFixture.configureByText("Orders.java", "class Orders {\n" + body + "\n}\n");
    }

    protected List<FoundMessage> messages() {
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        return FixMessages.in(myFixture.getFile());
    }

    protected FoundMessage only() {
        List<FoundMessage> all = messages();
        assertEquals("messages found", 1, all.size());
        return all.get(0);
    }

    protected String text(TextRange r) {
        assertNotNull("no range", r);
        return myFixture.getEditor().getDocument().getText(r);
    }

    protected String text() {
        return myFixture.getEditor().getDocument().getText();
    }
}
