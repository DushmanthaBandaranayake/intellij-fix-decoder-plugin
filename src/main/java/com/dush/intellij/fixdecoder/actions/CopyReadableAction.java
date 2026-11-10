package com.dush.intellij.fixdecoder.actions;

import com.dush.intellij.fixdecoder.core.parse.FixRewriter;
import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;

/** Copies the message at the caret with {@code |} as delimiter, as it would appear in a log. */
public final class CopyReadableAction extends MessageAtCaretAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) return;
        int offset = editor.getCaretModel().getOffset();
        // The lookup reads PSI, which must not happen on the UI thread; copy once it is done.
        FixMessages.inBackground(project, editor.getDocument(), file -> FixMessages.atCaret(file, offset), m -> {
            if (m != null) CopyPasteManager.getInstance().setContents(new StringSelection(FixRewriter.readable(m.message())));
        });
    }
}
