package com.dush.intellij.fixdecoder.java;

import com.dush.intellij.fixdecoder.core.parse.RawField;
import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.dush.intellij.fixdecoder.hover.FieldInfo;
import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.FakePsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gives IntelliJ's own documentation (hover and Ctrl+Q) the details of a FIX field in a Java string.
 * Without it, IntelliJ's hover shows "No documentation found" on top of the plugin's popup; with it, there is one
 * popup with the FIX details. {@link com.dush.intellij.fixdecoder.hover.FixHoverListener} then stays quiet in Java
 * files while IntelliJ's hover documentation is switched on.
 */
public final class FixDocumentationProvider extends AbstractDocumentationProvider {

    @Override
    public @Nullable PsiElement getCustomDocumentationElement(@NotNull Editor editor, @NotNull PsiFile file,
                                                            @Nullable PsiElement contextElement, int targetOffset) {
        if (contextElement == null) return null;
        FoundMessage m = FixMessages.at(file, targetOffset);
        RawField f = m == null ? null : m.fieldAt(targetOffset);
        if (f == null) return null;
        // IntelliJ's popup can't follow the plugin's "Decode message" link; Alt+Enter > Decode FIX message does that.
        return new FixFieldElement(contextElement, FieldInfo.html(m.message(), f, false, 0), f.tag() + "=" + f.value());
    }

    @Override
    public @Nullable String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
        return element instanceof FixFieldElement ? ((FixFieldElement) element).html : null;
    }

    @Override
    public @Nullable String generateHoverDoc(@NotNull PsiElement element, @Nullable PsiElement originalElement) {
        return generateDoc(element, originalElement);
    }

    /** Stands for one FIX field in a string literal, so that the documentation is about that field. */
    static final class FixFieldElement extends FakePsiElement {
        private final PsiElement parent;
        final String html;
        private final String name;

        FixFieldElement(PsiElement parent, String html, String name) {
            this.parent = parent;
            this.html = html;
            this.name = name;
        }

        @Override
        public PsiElement getParent() {
            return parent;
        }

        @Override
        public @Nullable String getName() {
            return name;
        }

        @Override
        public @Nullable String getPresentableText() {
            return name;
        }
    }
}
