package com.dush.intellij.fixdecoder.intention;

import com.dush.intellij.fixdecoder.core.parse.FixRewriter;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Replaces every delimiter of the message at the caret with {@code |}. */
public final class ConvertToPipeIntention extends FixIntentionBase {

    @Override
    public @NotNull String getText() {
        return "Convert FIX delimiter to |";
    }

    @Override
    protected List<FixRewriter.Edit> edits(@NotNull Project project, @NotNull FoundMessage message) {
        return FixRewriter.convertDelimiter(message.message(), "|");
    }
}
