package com.dush.intellij.fixdecoder.actions;

import com.dush.intellij.fixdecoder.toolwindow.FixDecoderToolWindow;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Shows the message at the caret in the FIX Decoder tool window. */
public final class DecodeFixMessageAction extends MessageAtCaretAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) return;
        FixDecoderToolWindow.decode(project, editor, editor.getCaretModel().getOffset());
    }
}
