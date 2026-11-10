package com.dush.intellij.fixdecoder.inspection;

import com.dush.intellij.fixdecoder.core.parse.FixRewriter;
import com.dush.intellij.fixdecoder.core.parse.Problem;
import com.dush.intellij.fixdecoder.edit.FixEdits;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.dush.intellij.fixdecoder.settings.FixDecoderSettings;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** BodyLength (9) and CheckSum (10) that don't match the message. */
public final class FixLengthChecksumInspection extends FixInspectionBase {

    @Override
    protected Problem.Category category() {
        return Problem.Category.LENGTH_CHECKSUM;
    }

    @Override
    protected boolean enabled(@NotNull Project project) {
        return FixDecoderSettings.of(project).lengthChecksumFix;
    }

    @Override
    protected LocalQuickFix[] fixes(@NotNull FoundMessage message, @NotNull Problem problem) {
        if (!FixEdits.canApply(message, FixRewriter.fixLengthAndChecksum(message.message()))) return LocalQuickFix.EMPTY_ARRAY;
        return new LocalQuickFix[]{new RecomputeLengthChecksumFix()};
    }
}
