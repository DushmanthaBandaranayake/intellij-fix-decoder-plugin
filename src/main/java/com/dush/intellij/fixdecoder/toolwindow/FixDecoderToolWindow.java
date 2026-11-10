package com.dush.intellij.fixdecoder.toolwindow;

import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** The "FIX Decoder" tool window, holding one {@link FixDecoderPanel}. */
public final class FixDecoderToolWindow implements ToolWindowFactory, DumbAware {
    public static final String ID = "FIX Decoder";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        FixDecoderPanel panel = new FixDecoderPanel(project);
        // Service lookup rather than ContentFactory.getInstance(), which is missing from older platforms
        Content content = ApplicationManager.getApplication().getService(ContentFactory.class).createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }

    /**
     * Finds the message at the offset in the background, then opens the tool window and shows it.
     * Does nothing when there is no message there.
     */
    public static void decode(@NotNull Project project, @NotNull Editor editor, int offset) {
        Document document = editor.getDocument();
        FixMessages.inBackground(project, document, file -> FixMessages.atCaret(file, offset), m -> {
            if (m == null || project.isDisposed()) return;
            FixDecoderPanel panel = open(project);
            if (panel != null) panel.show(document, m);
        });
    }

    /** Opens the tool window and asks for a message to paste. */
    public static void pasteMessage(@NotNull Project project) {
        FixDecoderPanel panel = open(project);
        if (panel != null) panel.askForMessage();
    }

    private static FixDecoderPanel open(Project project) {
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(ID);
        if (tw == null) return null;
        tw.show(null);
        FixDecoderPanel panel = panel(tw);
        if (panel == null) {
            // the content is created lazily, on first show
            tw.getContentManager();
            panel = panel(tw);
        }
        return panel;
    }

    static FixDecoderPanel panel(ToolWindow tw) {
        for (Content c : tw.getContentManager().getContents()) {
            if (c.getComponent() instanceof FixDecoderPanel) return (FixDecoderPanel) c.getComponent();
        }
        return null;
    }
}
