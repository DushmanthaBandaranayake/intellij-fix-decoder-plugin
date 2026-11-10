package com.dush.intellij.fixdecoder.intention;

import com.dush.intellij.fixdecoder.core.parse.FixRewriter;
import com.dush.intellij.fixdecoder.core.parse.FixScanner;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/** Replaces every delimiter of the message at the caret with SOH, written as an escape. Java code only. */
public final class ConvertToSohIntention extends FixIntentionBase {

    @Override
    public @NotNull String getText() {
        return "Convert FIX delimiter to SOH";
    }

    @Override
    protected List<FixRewriter.Edit> edits(@NotNull Project project, @NotNull FoundMessage message) {
        if (!message.inJavaCode()) return Collections.emptyList();
        return FixRewriter.convertDelimiter(message.message(), FixScanner.SOH);
    }
}
