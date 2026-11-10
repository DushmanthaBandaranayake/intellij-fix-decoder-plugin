package com.dush.intellij.fixdecoder.hover;

import com.dush.intellij.fixdecoder.core.parse.RawField;
import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.dush.intellij.fixdecoder.toolwindow.FixDecoderToolWindow;
import com.intellij.codeInsight.hint.HintUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseEventArea;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.event.HyperlinkEvent;
import java.awt.Point;

/**
 * Shows {@link FieldInfo} in a popup when the mouse rests on a field of a FIX message, in any editor.
 * <p>
 * Mouse events arrive on the UI thread, which must not read PSI for long (finding messages resolves Java constants).
 * So the listener only works out the offset under the mouse; after a short pause it looks up the field in a background
 * read action, and shows the popup if the mouse is still at that offset.
 * <p>
 * Registered as both an editor mouse motion listener and mouse listener; the platform creates one instance for each,
 * so the state is static (there is only one mouse).
 */
public final class FixHoverListener implements EditorMouseMotionListener, EditorMouseListener {
    private static final int DELAY_MS = 450;

    private static Timer timer;
    private static Editor pendingEditor;
    private static int pendingOffset = -1;
    private static JBPopup popup;
    private static Editor shownEditor;
    private static TextRange shownRange;

    @Override
    public void mouseMoved(@NotNull EditorMouseEvent e) {
        Editor editor = e.getEditor();
        Project project = editor.getProject();
        if (project == null || project.isDisposed() || e.getArea() != EditorMouseEventArea.EDITING_AREA
                || e.getMouseEvent().getModifiersEx() != 0) {
            leave();
            return;
        }
        int offset = offsetUnderMouse(editor, e.getMouseEvent().getPoint());
        if (offset < 0) {
            leave();
            return;
        }
        if (isShowing() && shownEditor == editor && shownRange != null && shownRange.containsOffset(offset)) return;
        if (editor == pendingEditor && offset == pendingOffset) return;
        hide();
        schedule(project, editor, offset);
    }

    @Override
    public void mouseExited(@NotNull EditorMouseEvent e) {
        // Keep a visible popup: the mouse may be on its way into it to click "Decode message".
        cancelPending();
    }

    /** What the popup shows for the field at an offset. Built in the background. */
    private static final class Hit {
        final String html;
        final TextRange range;
        final long stamp;

        Hit(String html, TextRange range, long stamp) {
            this.html = html;
            this.range = range;
            this.stamp = stamp;
        }
    }

    private static void schedule(Project project, Editor editor, int offset) {
        cancelPending();
        pendingEditor = editor;
        pendingOffset = offset;
        timer = new Timer(DELAY_MS, ev -> lookUp(project, editor, offset));
        timer.setRepeats(false);
        timer.start();
    }

    private static void lookUp(Project project, Editor editor, int offset) {
        stopTimer();
        if (editor.isDisposed() || project.isDisposed()) return;
        Document document = editor.getDocument();
        FixMessages.inBackground(project, document, file -> hit(file, document, offset), hit -> {
            if (hit == null || editor.isDisposed() || editor != pendingEditor || offset != pendingOffset
                    || hit.stamp != document.getModificationStamp()) return;
            show(project, editor, offset, hit);
        });
    }

    private static Hit hit(PsiFile file, Document document, int offset) {
        FoundMessage m = FixMessages.at(file, offset);
        RawField f = m == null ? null : m.fieldAt(offset);
        if (f == null) return null;
        TextRange r = m.fieldRange(f);
        return new Hit(FieldInfo.html(m.message(), f), r, document.getModificationStamp());
    }

    private static void show(Project project, Editor editor, int offset, Hit hit) {
        pendingEditor = null;
        pendingOffset = -1;
        hide();
        JComponent label = HintUtil.createInformationLabel(hit.html, e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && FieldInfo.DECODE_LINK.equals(e.getDescription())) {
                hide();
                // Leave the hyperlink event first: opening the tool window is not allowed from inside it.
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!editor.isDisposed() && !project.isDisposed()) FixDecoderToolWindow.decode(project, editor, offset);
                });
            }
        }, null, null);
        JBPopup p = JBPopupFactory.getInstance().createComponentPopupBuilder(label, null)
                .setRequestFocus(false)
                .setFocusable(false)
                .setResizable(false)
                .setMovable(false)
                .setCancelOnClickOutside(true)
                .setCancelKeyEnabled(true)
                .setCancelOnWindowDeactivation(true)
                .createPopup();
        Point at = editor.offsetToXY(hit.range == null ? offset : hit.range.getStartOffset());
        at.y += editor.getLineHeight();
        popup = p;
        shownEditor = editor;
        shownRange = hit.range;
        p.show(new RelativePoint(editor.getContentComponent(), at));
    }

    /** The offset of the character under the point, or -1 when the point is past the end of its line. */
    private static int offsetUnderMouse(Editor editor, Point point) {
        Document document = editor.getDocument();
        LogicalPosition lp = editor.xyToLogicalPosition(point);
        if (lp.line < 0 || lp.line >= document.getLineCount()) return -1;
        int lineEnd = document.getLineEndOffset(lp.line);
        int offset = editor.logicalPositionToOffset(lp);
        if (offset >= lineEnd) return -1;
        Point end = editor.offsetToXY(lineEnd);
        if (end.y == editor.offsetToXY(offset).y && point.x >= end.x) return -1;
        // xyToLogicalPosition rounds to the nearest caret position; use the character the point is actually over
        Point at = editor.offsetToXY(offset);
        if (point.x < at.x && offset > document.getLineStartOffset(lp.line)) offset--;
        return offset;
    }

    /** The mouse is no longer on a field: forget the pending lookup and close a shown popup, unless the mouse is on it. */
    private static void leave() {
        cancelPending();
        if (isShowing() && popup.getContent().getMousePosition() != null) return;
        hide();
    }

    private static boolean isShowing() {
        return popup != null && popup.isVisible();
    }

    private static void hide() {
        if (popup != null) {
            popup.cancel();
            popup = null;
        }
        shownEditor = null;
        shownRange = null;
    }

    private static void cancelPending() {
        stopTimer();
        pendingEditor = null;
        pendingOffset = -1;
    }

    private static void stopTimer() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }
}
