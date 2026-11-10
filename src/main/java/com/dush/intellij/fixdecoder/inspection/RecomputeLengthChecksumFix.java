package com.dush.intellij.fixdecoder.inspection;

import com.dush.intellij.fixdecoder.core.parse.FixRewriter;
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

/** Rewrites BodyLength (9) and CheckSum (10) so that they match the message. */
final class RecomputeLengthChecksumFix implements LocalQuickFix {

    @Override
    public @NotNull String getFamilyName() {
        return "Recompute BodyLength and CheckSum";
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
        if (m == null) return;
        FixEdits.apply(project, document, m, FixRewriter.fixLengthAndChecksum(m.message()));
    }
}
