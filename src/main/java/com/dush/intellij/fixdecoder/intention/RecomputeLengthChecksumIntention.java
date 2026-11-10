package com.dush.intellij.fixdecoder.intention;

import com.dush.intellij.fixdecoder.core.parse.FixRewriter;
import com.dush.intellij.fixdecoder.core.parse.Problem;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.dush.intellij.fixdecoder.settings.FixDecoderSettings;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * Adds or recomputes BodyLength (9) and CheckSum (10). When one of them is reported as wrong, the inspection's
 * quick fix does the same, so this intention is offered only when neither is reported (for example when they are missing).
 */
public final class RecomputeLengthChecksumIntention extends FixIntentionBase {

    @Override
    public @NotNull String getText() {
        return "Add FIX BodyLength and CheckSum";
    }

    @Override
    protected List<FixRewriter.Edit> edits(@NotNull Project project, @NotNull FoundMessage message) {
        if (!FixDecoderSettings.of(project).lengthChecksumFix) return Collections.emptyList();
        for (Problem p : message.message().problems()) {
            if (p.kind().category() == Problem.Category.LENGTH_CHECKSUM) return Collections.emptyList();
        }
        return FixRewriter.fixLengthAndChecksum(message.message());
    }
}
