package com.dush.intellij.fixdecoder.actions;

import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.UpdateInBackground;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** An editor action that is shown only when the caret is in a FIX message. */
abstract class MessageAtCaretAction extends AnAction implements DumbAware, UpdateInBackground {

    // UpdateInBackground runs update() on a background thread in a read action, which newer IDEs require for
    // reading PSI. It's deprecated, but it exists in every supported IDE. getActionUpdateThread(), its replacement,
    // can't be used yet because ActionUpdateThread only exists since 2022.3.
    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(messageAtCaret(e) != null);
    }

    @Nullable
    static FoundMessage messageAtCaret(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || file == null) return null;
        if (PsiDocumentManager.getInstance(project).isUncommited(editor.getDocument())) return null;
        return FixMessages.atCaret(file, editor.getCaretModel().getOffset());
    }
}
