package com.dush.intellij.fixdecoder.inspection;

import com.dush.intellij.fixdecoder.core.parse.FixRewriter;
import com.dush.intellij.fixdecoder.core.parse.Problem;
import com.dush.intellij.fixdecoder.edit.FixEdits;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.dush.intellij.fixdecoder.settings.FixDecoderSettings;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

/** Repeating groups: count against instances found, the delimiter field, field order, unknown tags inside a group. */
public final class FixRepeatingGroupInspection extends FixInspectionBase {

    @Override
    protected Problem.Category category() {
        return Problem.Category.GROUP;
    }

    @Override
    protected boolean enabled(@NotNull Project project) {
        return FixDecoderSettings.of(project).groupValidation;
    }

    @Override
    protected LocalQuickFix[] fixes(@NotNull FoundMessage message, @NotNull Problem problem) {
        if (problem.kind() != Problem.Kind.GROUP_COUNT || problem.fixCount() < 0) return LocalQuickFix.EMPTY_ARRAY;
        FixRewriter.Edit edit = FixRewriter.setCount(problem.field(), problem.fixCount());
        if (!FixEdits.canApply(message, Collections.singletonList(edit))) return LocalQuickFix.EMPTY_ARRAY;
        return new LocalQuickFix[]{new SetGroupCountFix(problem.field().tag(), problem.fixCount())};
    }
}
