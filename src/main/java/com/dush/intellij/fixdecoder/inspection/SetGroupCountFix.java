package com.dush.intellij.fixdecoder.inspection;

import com.dush.intellij.fixdecoder.core.parse.FixRewriter;
import com.dush.intellij.fixdecoder.core.parse.RawField;
import com.dush.intellij.fixdecoder.edit.FixEdits;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

/** Sets a group's NumInGroup field to the number of instances actually present. */
final class SetGroupCountFix implements LocalQuickFix {
    private final int tag;
    private final int count;

    SetGroupCountFix(int tag, int count) {
        this.tag = tag;
        this.count = count;
    }

    @Override
    public @NotNull String getName() {
        return "Set count to " + count;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Set repeating group count";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement();
        if (element == null) return;
        PsiFile file = element.getContainingFile();
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) return;
        int offset = element.getTextRange().getStartOffset() + descriptor.getTextRangeInElement().getStartOffset();
        FoundMessage m = FixEdits.messageAt(file, offset);
        RawField count = m == null ? null : m.fieldAt(offset);
        if (count == null || count.tag() != tag) return;
        FixEdits.apply(project, document, m, Collections.singletonList(FixRewriter.setCount(count, this.count)));
    }
}
