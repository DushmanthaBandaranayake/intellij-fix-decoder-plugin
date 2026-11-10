package com.dush.intellij.fixdecoder.intention;

import com.dush.intellij.fixdecoder.FixDecoderIcons;
import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.dush.intellij.fixdecoder.toolwindow.FixDecoderToolWindow;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Iconable;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/** Alt+Enter on a FIX message: shows it in the FIX Decoder tool window, like the editor menu's Decode FIX Message. */
public final class DecodeMessageIntention implements IntentionAction, Iconable {

    @Override
    public @NotNull String getText() {
        return "Decode FIX message";
    }

    @Override
    public @NotNull String getFamilyName() {
        return getText();
    }

    // Called in the background by the intentions pass, so reading the messages here is fine.
    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return editor != null && file != null && FixMessages.atCaret(file, editor.getCaretModel().getOffset()) != null;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        // decode() finds the message in the background, then opens the tool window.
        if (editor != null) FixDecoderToolWindow.decode(project, editor, editor.getCaretModel().getOffset());
    }

    /** It changes nothing in the file, so no write action (and no preview of an edit). */
    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public Icon getIcon(int flags) {
        return FixDecoderIcons.DECODER;
    }
}
