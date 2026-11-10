package com.dush.intellij.fixdecoder.edit;

import com.dush.intellij.fixdecoder.core.parse.FixRewriter;
import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Writes message edits into the document. Must run in a write action (quick fixes and intentions already do). */
public final class FixEdits {

    private FixEdits() {
    }

    /** Applies the edits; false when any of them touches text that is not written in the document as it is. */
    public static boolean apply(@NotNull Project project, @NotNull Document document, @NotNull FoundMessage message,
                                @NotNull List<FixRewriter.Edit> edits) {
        List<FoundMessage.DocEdit> docEdits = message.toDocument(edits);
        if (docEdits == null) return false;
        PsiDocumentManager pdm = PsiDocumentManager.getInstance(project);
        pdm.doPostponedOperationsAndUnblockDocument(document);
        List<FoundMessage.DocEdit> sorted = new ArrayList<>(docEdits);
        sorted.sort((a, b) -> Integer.compare(b.range.getStartOffset(), a.range.getStartOffset()));
        for (FoundMessage.DocEdit e : sorted) {
            document.replaceString(e.range.getStartOffset(), e.range.getEndOffset(), e.text);
        }
        pdm.commitDocument(document);
        return true;
    }

    /** True when the edits can be written back. */
    public static boolean canApply(@NotNull FoundMessage message, @NotNull List<FixRewriter.Edit> edits) {
        return !edits.isEmpty() && message.toDocument(edits) != null;
    }

    /** The message at a document offset of the file, read again from the current text. */
    @Nullable
    public static FoundMessage messageAt(@NotNull PsiFile file, int offset) {
        return FixMessages.atCaret(file, offset);
    }
}
