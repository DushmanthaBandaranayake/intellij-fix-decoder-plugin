package com.dush.intellij.fixdecoder.intention;

import com.dush.intellij.fixdecoder.core.parse.FixRewriter;
import com.dush.intellij.fixdecoder.edit.FixEdits;
import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** An intention that rewrites the FIX message at the caret. Available only when the edits can be written back. */
abstract class FixIntentionBase implements IntentionAction {

    /** The edits for the message, or an empty list when the intention does not apply to it. */
    protected abstract List<FixRewriter.Edit> edits(@NotNull Project project, @NotNull FoundMessage message);

    @Override
    public @NotNull String getFamilyName() {
        return getText();
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        FoundMessage m = message(editor, file);
        return m != null && FixEdits.canApply(m, edits(project, m));
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
        FoundMessage m = message(editor, file);
        if (m != null) FixEdits.apply(project, editor.getDocument(), m, edits(project, m));
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }

    @Nullable
    private static FoundMessage message(Editor editor, PsiFile file) {
        if (editor == null || file == null) return null;
        return FixMessages.atCaret(file, editor.getCaretModel().getOffset());
    }
}
