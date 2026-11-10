package com.dush.intellij.fixdecoder.java;

import com.dush.intellij.fixdecoder.core.parse.FixMessage;
import com.dush.intellij.fixdecoder.core.parse.FixParser;
import com.dush.intellij.fixdecoder.core.parse.FixRewriter;
import com.dush.intellij.fixdecoder.core.parse.FixScanner;
import com.dush.intellij.fixdecoder.settings.FixDecoderSettings;
import com.dush.intellij.fixdecoder.settings.FixDictionaries;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.datatransfer.DataFlavor;
import java.util.Arrays;
import java.util.List;

/**
 * Pasting a FIX message into a Java file asks how to paste it: as a Java string (SOH written as {@code \u0001},
 * quotes added outside a string literal), or as it is. Esc pastes it as it is. Anything else pastes as usual.
 */
public final class FixPasteHandler extends EditorActionHandler {
    static final String AS_JAVA = "Paste as Java string";
    static final String AS_IS = "Paste as is";

    private final EditorActionHandler original;

    public FixPasteHandler(EditorActionHandler original) {
        this.original = original;
    }

    @Override
    protected boolean isEnabledForCaret(@NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
        return original.isEnabled(editor, caret, dataContext);
    }

    @Override
    protected void doExecute(@NotNull Editor editor, @Nullable Caret caret, DataContext dataContext) {
        Project project = editor.getProject();
        String clipboard = project == null ? null : clipboardText();
        FixMessage message = clipboard == null ? null : wholeMessage(project, clipboard);
        if (message == null || !FixDecoderSettings.of(project).pastePopup || editor.isViewer()
                || editor.getCaretModel().getCaretCount() > 1 || !isJavaFile(project, editor)) {
            original.execute(editor, caret, dataContext);
            return;
        }
        boolean convert = !FixScanner.SOH.equals(message.delimiter());
        List<String> options = Arrays.asList(convert ? AS_JAVA + ", " + message.delimiter() + " as \\u0001" : AS_JAVA, AS_IS);
        ListPopup popup = JBPopupFactory.getInstance().createListPopup(new BaseListPopupStep<String>("Paste FIX Message", options) {
            private boolean chosen;

            @Override
            public PopupStep<?> onChosen(String value, boolean finalChoice) {
                chosen = true;
                boolean asJava = value.startsWith(AS_JAVA);
                return doFinalStep(() -> {
                    if (asJava) pasteAsJava(project, editor, message);
                    else pasteAsIs(project, editor);
                });
            }

            @Override
            public void canceled() {
                if (!chosen) pasteAsIs(project, editor);
            }
        });
        popup.showInBestPositionFor(editor);
    }

    private void pasteAsIs(Project project, Editor editor) {
        if (editor.isDisposed()) return;
        DataContext context = DataManager.getInstance().getDataContext(editor.getContentComponent());
        CommandProcessor.getInstance().executeCommand(project, () -> original.execute(editor, null, context), "Paste", null);
    }

    private static void pasteAsJava(Project project, Editor editor, FixMessage message) {
        if (editor.isDisposed()) return;
        Document document = editor.getDocument();
        PsiDocumentManager.getInstance(project).commitDocument(document);
        SelectionModel selection = editor.getSelectionModel();
        int start = selection.hasSelection() ? selection.getSelectionStart() : editor.getCaretModel().getOffset();
        int end = selection.hasSelection() ? selection.getSelectionEnd() : start;
        String text = javaText(message, insideStringLiteral(project, document, start));
        WriteCommandAction.runWriteCommandAction(project, "Paste FIX Message", null, () -> {
            document.replaceString(start, end, text);
            selection.removeSelection();
            editor.getCaretModel().moveToOffset(start + text.length());
        });
    }

    /** The message with SOH delimiters, escaped for Java, with quotes when it is not pasted inside a literal. */
    static String javaText(FixMessage message, boolean insideLiteral) {
        String escaped = JavaEscaper.escape(FixRewriter.withSoh(message), "\\u0001");
        return insideLiteral ? escaped : "\"" + escaped + "\"";
    }

    /** The clipboard parsed as one message, or null when it is not (mostly) a single FIX message. */
    static FixMessage wholeMessage(Project project, String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.indexOf('\n') >= 0) return null;
        FixDictionaries dictionaries = FixDictionaries.get(project);
        FixScanner.Span span = FixScanner.first(trimmed, dictionaries.set()::isKnownTag);
        if (span == null || (span.end() - span.start()) * 10 < trimmed.length() * 9) return null;
        return FixParser.parse(trimmed.substring(span.start(), span.end()), dictionaries.set(), dictionaries.defaultVersion());
    }

    private static boolean insideStringLiteral(Project project, Document document, int offset) {
        PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
        if (file == null) return false;
        PsiElement e = file.findElementAt(offset);
        if (!(e instanceof PsiJavaToken)) return false;
        IElementType type = ((PsiJavaToken) e).getTokenType();
        if (type != JavaTokenType.STRING_LITERAL && !"TEXT_BLOCK_LITERAL".equals(type.toString())) return false;
        int s = e.getTextRange().getStartOffset();
        int len = e.getTextLength();
        int open = type == JavaTokenType.STRING_LITERAL ? 1 : 3;
        return offset >= s + open && offset <= s + len - open;
    }

    private static boolean isJavaFile(Project project, Editor editor) {
        return PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()) instanceof PsiJavaFile;
    }

    private static String clipboardText() {
        try {
            return CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
