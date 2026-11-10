package com.dush.intellij.fixdecoder.inspection;

import com.dush.intellij.fixdecoder.core.parse.Problem;
import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports the problems of one {@link Problem.Category} for every message in a file, highlighting exactly the part
 * of the message the problem is about. Registered without a language, so it runs on Java, plain text and any other file.
 */
public abstract class FixInspectionBase extends LocalInspectionTool {

    protected abstract Problem.Category category();

    /** False when the feature is switched off in Settings > Tools > FIX Decoder. */
    protected abstract boolean enabled(@NotNull Project project);

    protected LocalQuickFix[] fixes(@NotNull FoundMessage message, @NotNull Problem problem) {
        return LocalQuickFix.EMPTY_ARRAY;
    }

    @Override
    public ProblemDescriptor @NotNull [] checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        if (!enabled(file.getProject())) return ProblemDescriptor.EMPTY_ARRAY;
        List<ProblemDescriptor> out = new ArrayList<>();
        for (FoundMessage m : FixMessages.in(file)) {
            for (Problem p : m.message().problems()) {
                if (p.kind().category() != category()) continue;
                TextRange r = m.displayRange(p.start(), p.end());
                if (r == null || r.isEmpty()) r = m.fieldRange(p.field());
                if (r == null || r.isEmpty()) continue;
                PsiElement element = elementCovering(file, r);
                TextRange inElement = r.shiftLeft(element.getTextRange().getStartOffset());
                out.add(manager.createProblemDescriptor(element, inElement, p.message(),
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly, fixes(m, p)));
            }
        }
        return out.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    private static PsiElement elementCovering(PsiFile file, TextRange r) {
        PsiElement a = file.findElementAt(r.getStartOffset());
        PsiElement b = file.findElementAt(r.getEndOffset() - 1);
        PsiElement common = a == null || b == null ? null : PsiTreeUtil.findCommonParent(a, b);
        return common == null ? file : common;
    }
}
