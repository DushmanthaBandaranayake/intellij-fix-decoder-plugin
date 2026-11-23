package com.dush.intellij.fixdecoder.toolwindow;

import com.dush.intellij.fixdecoder.core.parse.FixMessage;
import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "FIX Decoder" tool window: one tab, holding a {@link FixDecoderPanel}, per decoded message. Decoding a message
 * that a tab already shows selects that tab; a pasted message always gets a tab of its own. With no message open,
 * the tool window holds one empty tab that cannot be closed, which the next message takes over.
 */
public final class FixDecoderToolWindow implements ToolWindowFactory, DumbAware {
    public static final String ID = "FIX Decoder";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        install(project, toolWindow.getContentManager());
    }

    /** Adds the empty tab, and adds it again whenever the last tab is closed. */
    static void install(@NotNull Project project, @NotNull ContentManager contents) {
        boolean[] disposing = {false};
        // disposed before the content manager removes its tabs, so that closing the project adds no tab back
        Disposable watch = () -> disposing[0] = true;
        Disposer.register(contents, watch);
        contents.addContentManagerListener(new ContentManagerListener() {
            @Override
            public void contentRemoved(@NotNull ContentManagerEvent event) {
                if (!disposing[0] && !project.isDisposed() && contents.getContentCount() == 0) addTab(project, contents, false);
            }
        });
        addTab(project, contents, false);
    }

    /**
     * Finds the message at the offset in the background, then opens the tool window and shows it.
     * Does nothing when there is no message there.
     */
    public static void decode(@NotNull Project project, @NotNull Editor editor, int offset) {
        Document document = editor.getDocument();
        FixMessages.inBackground(project, document, file -> FixMessages.atCaret(file, offset), m -> {
            if (m == null || project.isDisposed()) return;
            ContentManager contents = open(project);
            if (contents != null) showMessage(project, contents, document, m);
        });
    }

    /** Opens the tool window and asks for a message to paste, which gets a tab of its own. */
    public static void pasteMessage(@NotNull Project project) {
        FixMessage m = FixDecoderPanel.askForMessage(project);
        if (m == null) return;
        ContentManager contents = open(project);
        if (contents != null) showPasted(project, contents, m);
    }

    /**
     * Decodes the message of every tab again, after the dictionaries or the choice between them changed.
     * {@code done} is a panel that has already done so, or null.
     */
    public static void dictionariesChanged(@NotNull Project project, @Nullable FixDecoderPanel done) {
        forEachPanel(project, p -> {
            if (p != done) p.decodeAgain();
        });
    }

    public static void dictionariesChanged(@NotNull Project project) {
        dictionariesChanged(project, null);
    }

    /** Runs {@code action} on the panel of every tab; nothing when the tool window was never opened. */
    static void forEachPanel(@NotNull Project project, @NotNull Consumer<FixDecoderPanel> action) {
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(ID);
        // not getContentManager(): that would create the content of a tool window that was never opened
        ContentManager contents = tw == null ? null : tw.getContentManagerIfCreated();
        if (contents != null) for (FixDecoderPanel p : panels(contents)) action.accept(p);
    }

    /** Shows a message from a document: in the tab that shows it already, the empty tab, or a new tab. */
    static FixDecoderPanel showMessage(@NotNull Project project, @NotNull ContentManager contents,
                                       @NotNull Document document, @NotNull FoundMessage found) {
        TextRange range = found.displayRange();
        Content tab = null;
        for (Content c : contents.getContents()) {
            if (c.getComponent() instanceof FixDecoderPanel && ((FixDecoderPanel) c.getComponent()).shows(document, range)) {
                tab = c;
                break;
            }
        }
        if (tab == null) tab = freeTab(project, contents);
        FixDecoderPanel panel = (FixDecoderPanel) tab.getComponent();
        panel.show(document, found);
        contents.setSelectedContent(tab);
        return panel;
    }

    /** Shows pasted text, in the empty tab or a new one. */
    static FixDecoderPanel showPasted(@NotNull Project project, @NotNull ContentManager contents, @NotNull FixMessage m) {
        Content tab = freeTab(project, contents);
        FixDecoderPanel panel = (FixDecoderPanel) tab.getComponent();
        panel.showText(m);
        contents.setSelectedContent(tab);
        return panel;
    }

    static List<FixDecoderPanel> panels(@NotNull ContentManager contents) {
        List<FixDecoderPanel> out = new ArrayList<>();
        for (Content c : contents.getContents()) {
            if (c.getComponent() instanceof FixDecoderPanel) out.add((FixDecoderPanel) c.getComponent());
        }
        return out;
    }

    private static Content freeTab(Project project, ContentManager contents) {
        for (Content c : contents.getContents()) {
            if (c.getComponent() instanceof FixDecoderPanel && ((FixDecoderPanel) c.getComponent()).isEmpty()) return c;
        }
        return addTab(project, contents, true);
    }

    private static Content addTab(Project project, ContentManager contents, boolean closeable) {
        FixDecoderPanel panel = new FixDecoderPanel(project);
        // Service lookup rather than ContentFactory.getInstance(), which is missing from older platforms
        Content content = ApplicationManager.getApplication().getService(ContentFactory.class).createContent(panel, "", false);
        content.setDisposer(panel);
        content.setCloseable(closeable);
        panel.setTab(content);
        contents.addContent(content);
        return content;
    }

    private static ContentManager open(Project project) {
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(ID);
        if (tw == null) return null;
        tw.show(null);
        // creates the content, with its empty tab, when the tool window is opened for the first time
        return tw.getContentManager();
    }
}
