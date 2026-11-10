package com.dush.intellij.fixdecoder.toolwindow;

import com.dush.intellij.fixdecoder.core.parse.FixMessage;
import com.dush.intellij.fixdecoder.core.parse.FixNode;
import com.dush.intellij.fixdecoder.core.parse.FixParser;
import com.dush.intellij.fixdecoder.core.parse.FixRewriter;
import com.dush.intellij.fixdecoder.core.parse.FixText;
import com.dush.intellij.fixdecoder.core.parse.Problem;
import com.dush.intellij.fixdecoder.core.parse.RawField;
import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.dush.intellij.fixdecoder.settings.FixDecoderConfigurable;
import com.dush.intellij.fixdecoder.settings.FixDictionaries;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns;
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo;
import com.intellij.ui.dualView.TreeTableView;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.table.TableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The decoded message as a tree table: Tag, Name, Value, Meaning, with repeating groups as nodes holding numbered
 * instances. Clicking a value selects it in the editor (the tag cell selects the {@code tag=} part) and keeps the
 * focus here; a double click or Enter moves the focus to the editor. Moving the caret in the editor selects the
 * matching row. The view follows edits of the message and says so when it no longer parses.
 */
public final class FixDecoderPanel extends JPanel implements Disposable {
    static final int COL_TAG = 0;
    static final int COL_NAME = 1;
    static final int COL_VALUE = 2;
    static final int COL_MEANING = 3;
    private static final int REFRESH_DELAY_MS = 400;

    private final Project project;
    private final JBLabel header = new JBLabel();
    private final TreeTableView table;
    private final List<Row> rows = new ArrayList<>();

    private FixMessage message;
    private Document document;
    private RangeMarker messageMarker;
    private Disposable documentDisposable;
    private Timer refreshTimer;
    private boolean navigating;
    private boolean stale;

    /** One row of the table: a field, or a group instance. */
    static final class Row {
        final String tag;
        final String name;
        final String value;
        final String meaning;
        final boolean problem;
        final boolean instance;
        final RawField field;
        /** The {@code tag=} part. */
        final RangeMarker tagMarker;
        final RangeMarker valueMarker;

        Row(String tag, String name, String value, String meaning, boolean problem, boolean instance, RawField field,
            RangeMarker tagMarker, RangeMarker valueMarker) {
            this.tag = tag;
            this.name = name;
            this.value = value;
            this.meaning = meaning;
            this.problem = problem;
            this.instance = instance;
            this.field = field;
            this.tagMarker = tagMarker;
            this.valueMarker = valueMarker;
        }

        @Override
        public String toString() {
            return tag;
        }
    }

    FixDecoderPanel(Project project) {
        super(new BorderLayout());
        this.project = project;

        table = new TreeTableView(model(new DefaultMutableTreeNode()));
        table.setRootVisible(false);
        table.getTree().setShowsRootHandles(true);
        table.setTreeCellRenderer(new ColoredTreeCellRenderer() {
            @Override
            public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded,
                                              boolean leaf, int row, boolean hasFocus) {
                Row r = row(value);
                if (r != null) append(r.tag, r.instance ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        });
        table.getEmptyText().setText("Put the caret in a FIX message and choose Decode FIX Message,")
                .appendSecondaryText("or paste a message", SimpleTextAttributes.LINK_ATTRIBUTES, e -> askForMessage());
        installListeners();

        JPanel top = new JPanel(new BorderLayout());
        top.add(toolbar().getComponent(), BorderLayout.WEST);
        header.setBorder(JBUI.Borders.emptyLeft(8));
        top.add(header, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER);
        setColumnWidths();

        EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent e) {
                followCaret(e.getEditor());
            }
        }, this);
    }

    private ListTreeTableModelOnColumns model(DefaultMutableTreeNode root) {
        ColumnInfo<?, ?>[] columns = {
                new TreeColumnInfo("Tag"),
                new TextColumn("Name", r -> r.name, null),
                new TextColumn("Value", r -> r.value, this::valueRenderer),
                new TextColumn("Meaning", r -> r.meaning, this::meaningRenderer),
        };
        return new ListTreeTableModelOnColumns(root, columns);
    }

    private void setColumnWidths() {
        if (table.getColumnModel().getColumnCount() < 4) return;
        table.getColumnModel().getColumn(COL_TAG).setPreferredWidth(JBUI.scale(110));
        table.getColumnModel().getColumn(COL_NAME).setPreferredWidth(JBUI.scale(180));
        table.getColumnModel().getColumn(COL_VALUE).setPreferredWidth(JBUI.scale(180));
        table.getColumnModel().getColumn(COL_MEANING).setPreferredWidth(JBUI.scale(300));
    }

    private ActionToolbar toolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new PanelAction("Refresh", "Decode the message again", AllIcons.Actions.Refresh, this::refresh));
        group.add(new PanelAction("Copy as Readable", "Copy the message with | as delimiter", AllIcons.Actions.Copy, () -> {
            if (message != null) CopyPasteManager.getInstance().setContents(new StringSelection(FixRewriter.readable(message)));
        }));
        group.add(new PanelAction("Paste Message", "Decode a message from text you paste", AllIcons.Actions.MenuPaste, this::askForMessage));
        group.addSeparator();
        group.add(new PanelAction("Settings", "Open Settings | Tools | FIX Decoder", AllIcons.General.Settings,
                () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, FixDecoderConfigurable.class)));
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("FixDecoderToolbar", group, true);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    private static final class PanelAction extends AnAction implements DumbAware {
        private final Runnable run;

        PanelAction(String text, String description, javax.swing.Icon icon, Runnable run) {
            super(text, description, icon);
            this.run = run;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            run.run();
        }
    }

    private void installListeners() {
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) return;
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row < 0) return;
                navigate(rowAt(row), col, e.getClickCount() >= 2);
            }
        });
        table.registerKeyboardAction(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) navigate(rowAt(row), COL_VALUE, true);
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED);
    }

    // --- showing a message ---

    /** Shows a message found in a document. */
    void show(@NotNull Document document, @NotNull FoundMessage found) {
        detachDocument();
        this.document = document;
        TextRange whole = found.displayRange();
        messageMarker = whole == null ? null : document.createRangeMarker(whole);
        if (messageMarker != null) {
            messageMarker.setGreedyToLeft(true);
            messageMarker.setGreedyToRight(true);
        }
        documentDisposable = Disposer.newDisposable();
        Disposer.register(this, documentDisposable);
        document.addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                scheduleRefresh();
            }
        }, documentDisposable);
        build(found.message(), found);
    }

    /** Shows a message from pasted text, with no document to navigate to. */
    void showText(@NotNull FixMessage m) {
        detachDocument();
        build(m, null);
    }

    void askForMessage() {
        String text = Messages.showMultilineInputDialog(project,
                "Paste a FIX message. Delimiters: SOH, |, ^A or ^. Text copied from Java source works too.",
                "FIX Decoder", "", null, null);
        if (text == null || text.trim().isEmpty()) return;
        FixMessage m = FixParser.parse(FixText.fromSource(text), FixDictionaries.get(project).set(),
                FixDictionaries.get(project).defaultVersion());
        if (m == null) {
            Messages.showWarningDialog(project, "No FIX message found in the text.", "FIX Decoder");
            return;
        }
        showText(m);
    }

    private void detachDocument() {
        if (documentDisposable != null) {
            Disposer.dispose(documentDisposable);
            documentDisposable = null;
        }
        for (Row r : rows) {
            if (r.tagMarker != null) r.tagMarker.dispose();
            if (r.valueMarker != null) r.valueMarker.dispose();
        }
        if (messageMarker != null) messageMarker.dispose();
        messageMarker = null;
        document = null;
        stopRefreshTimer();
    }

    private void build(FixMessage m, @Nullable FoundMessage found) {
        for (Row r : rows) {
            if (r.tagMarker != null) r.tagMarker.dispose();
            if (r.valueMarker != null) r.valueMarker.dispose();
        }
        rows.clear();
        message = m;
        stale = false;
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        addNodes(root, m.nodes(), found);
        table.setModel(model(root));
        setColumnWidths();
        TreeUtil.expandAll(table.getTree());
        updateHeader(found);
    }

    private void addNodes(DefaultMutableTreeNode parent, List<FixNode> nodes, @Nullable FoundMessage found) {
        for (FixNode n : nodes) {
            if (n instanceof FixNode.GroupNode) {
                FixNode.GroupNode g = (FixNode.GroupNode) n;
                DefaultMutableTreeNode gNode = fieldNode(g.count(), found,
                        g.instances().size() + (g.instances().size() == 1 ? " instance" : " instances"));
                parent.add(gNode);
                for (FixNode.Instance inst : g.instances()) {
                    Row r = new Row("[" + inst.number() + "]", g.def().delimiter().name(), "", "", false, true, null, null, null);
                    rows.add(r);
                    DefaultMutableTreeNode iNode = new DefaultMutableTreeNode(r);
                    gNode.add(iNode);
                    addNodes(iNode, inst.children(), found);
                }
            } else {
                parent.add(fieldNode((FixNode.FieldNode) n, found, null));
            }
        }
    }

    private DefaultMutableTreeNode fieldNode(FixNode.FieldNode n, @Nullable FoundMessage found, @Nullable String groupMeaning) {
        RawField f = n.field();
        List<Problem> problems = message.problemsAt(f);
        String meaning = problems.isEmpty() ? (groupMeaning != null ? groupMeaning : message.meaning(f)) : problems.get(0).message();
        Row r = new Row(String.valueOf(f.tag()), n.def() == null ? "(unknown)" : n.name(), f.value(), meaning == null ? "" : meaning,
                !problems.isEmpty(), false, f, marker(found == null ? null : found.displayRange(f.start(), f.valueStart())),
                marker(found == null ? null : found.valueRange(f)));
        rows.add(r);
        return new DefaultMutableTreeNode(r);
    }

    private RangeMarker marker(@Nullable TextRange r) {
        return r == null || document == null ? null : document.createRangeMarker(r);
    }

    private void updateHeader(@Nullable FoundMessage found) {
        StringBuilder sb = new StringBuilder();
        String type = message.typeName();
        String msgType = message.value(35);
        if (type != null) sb.append(type).append(" (35=").append(msgType).append(")");
        else if (msgType != null) sb.append("MsgType ").append(msgType);
        else sb.append("Fragment");
        sb.append(" · ").append(message.dictionary().label());
        if (document != null && found != null) {
            VirtualFile vf = FileDocumentManager.getInstance().getFile(document);
            TextRange r = found.displayRange();
            if (vf != null && r != null) sb.append(" · ").append(vf.getName()).append(':').append(document.getLineNumber(r.getStartOffset()) + 1);
        } else {
            sb.append(" · pasted");
        }
        int problems = message.problems().size();
        if (problems > 0) sb.append(" · ").append(problems).append(problems == 1 ? " problem" : " problems");
        if (stale) sb.append(" · changed, no longer a FIX message");
        header.setText(sb.toString());
        header.setForeground(stale ? JBColor.RED : null);
    }

    // --- following edits ---

    private void scheduleRefresh() {
        stopRefreshTimer();
        refreshTimer = new Timer(REFRESH_DELAY_MS, e -> refresh());
        refreshTimer.setRepeats(false);
        refreshTimer.start();
    }

    private void stopRefreshTimer() {
        if (refreshTimer != null) {
            refreshTimer.stop();
            refreshTimer = null;
        }
    }

    /** Finds the message again where it was, in the background, then redraws it or marks the view as stale. */
    void refresh() {
        stopRefreshTimer();
        if (!canRefresh()) return;
        Document d = document;
        int start = messageMarker.getStartOffset();
        int end = messageMarker.getEndOffset();
        FixMessages.inBackground(project, d, file -> overlapping(file, start, end), found -> {
            if (document == d) refreshed(found);
        });
    }

    /** Like {@link #refresh()}, but on the calling thread; for tests. */
    void refreshNow() {
        stopRefreshTimer();
        if (!canRefresh()) return;
        PsiDocumentManager.getInstance(project).commitDocument(document);
        int start = messageMarker.getStartOffset();
        int end = messageMarker.getEndOffset();
        refreshed(ReadAction.compute(() -> {
            PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
            return file == null ? null : overlapping(file, start, end);
        }));
    }

    private boolean canRefresh() {
        if (document == null || messageMarker == null || project.isDisposed()) return false;
        if (!messageMarker.isValid()) {
            stale = true;
            updateHeader(null);
            return false;
        }
        return true;
    }

    private static FoundMessage overlapping(PsiFile file, int start, int end) {
        for (FoundMessage m : FixMessages.in(file)) {
            TextRange r = m.displayRange();
            if (r != null && r.getStartOffset() <= end && r.getEndOffset() >= start) return m;
        }
        return null;
    }

    private void refreshed(@Nullable FoundMessage found) {
        if (found == null) {
            stale = true;
            updateHeader(null);
            return;
        }
        show(document, found);
    }

    // --- navigation ---

    private Row rowAt(int tableRow) {
        TreePath path = table.getTree().getPathForRow(tableRow);
        return path == null ? null : row(path.getLastPathComponent());
    }

    private static Row row(Object node) {
        if (node instanceof DefaultMutableTreeNode && ((DefaultMutableTreeNode) node).getUserObject() instanceof Row) {
            return (Row) ((DefaultMutableTreeNode) node).getUserObject();
        }
        return null;
    }

    private void navigate(@Nullable Row r, int column, boolean focusEditor) {
        if (r == null || document == null) return;
        RangeMarker m = column == COL_VALUE ? r.valueMarker : r.tagMarker;
        if (m == null) m = r.valueMarker != null ? r.valueMarker : r.tagMarker;
        if (m == null || !m.isValid()) return;
        VirtualFile vf = FileDocumentManager.getInstance().getFile(document);
        if (vf == null) return;
        int start = m.getStartOffset();
        int end = m.getEndOffset();
        navigating = true;
        try {
            Editor editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf, start), focusEditor);
            if (editor == null) return;
            editor.getCaretModel().moveToOffset(end);
            editor.getSelectionModel().setSelection(start, end);
            editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
        } finally {
            navigating = false;
        }
        if (!focusEditor) table.requestFocusInWindow();
    }

    private void followCaret(Editor editor) {
        if (navigating || document == null || editor.getDocument() != document || rows.isEmpty()) return;
        int offset = editor.getCaretModel().getOffset();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) table.getTree().getModel().getRoot();
        DefaultMutableTreeNode best = null;
        for (java.util.Enumeration<?> e = root.depthFirstEnumeration(); e.hasMoreElements(); ) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
            Row r = row(n);
            if (r == null || r.tagMarker == null || !r.tagMarker.isValid()) continue;
            int s = r.tagMarker.getStartOffset();
            int end = r.valueMarker != null && r.valueMarker.isValid() ? r.valueMarker.getEndOffset() : r.tagMarker.getEndOffset();
            if (offset >= s && offset <= end) {
                best = n;
                break;
            }
        }
        if (best == null) return;
        TreePath path = new TreePath(best.getPath());
        table.getTree().setSelectionPath(path);
        int row = table.getTree().getRowForPath(path);
        if (row >= 0) table.scrollRectToVisible(table.getCellRect(row, 0, true));
    }

    // --- rendering ---

    private TableCellRenderer valueRenderer(Row r) {
        return new ColoredTableCellRenderer() {
            @Override
            protected void customizeCellRenderer(@NotNull JTable t, Object value, boolean selected, boolean hasFocus, int row, int column) {
                if (value == null) return;
                boolean link = r.valueMarker != null && !r.instance;
                append(String.valueOf(value), link && !selected ? SimpleTextAttributes.LINK_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        };
    }

    private TableCellRenderer meaningRenderer(Row r) {
        return new ColoredTableCellRenderer() {
            @Override
            protected void customizeCellRenderer(@NotNull JTable t, Object value, boolean selected, boolean hasFocus, int row, int column) {
                if (value == null) return;
                if (r.problem) {
                    setIcon(AllIcons.General.Warning);
                    append(String.valueOf(value), SimpleTextAttributes.ERROR_ATTRIBUTES);
                } else {
                    append(String.valueOf(value), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            }
        };
    }

    /** A text column of the tree table; the rows are tree nodes holding a {@link Row}. */
    private static final class TextColumn extends ColumnInfo<Object, String> {
        private final java.util.function.Function<Row, String> getter;
        private final java.util.function.Function<Row, TableCellRenderer> renderer;

        TextColumn(String name, java.util.function.Function<Row, String> getter, java.util.function.Function<Row, TableCellRenderer> renderer) {
            super(name);
            this.getter = getter;
            this.renderer = renderer;
        }

        @Override
        public @Nullable String valueOf(Object node) {
            Row r = row(node);
            return r == null ? null : getter.apply(r);
        }

        @Override
        public @Nullable TableCellRenderer getRenderer(Object node) {
            Row r = row(node);
            return r == null || renderer == null ? null : renderer.apply(r);
        }
    }

    // --- for tests ---

    FixMessage message() {
        return message;
    }

    List<Row> rows() {
        return rows;
    }

    boolean isStale() {
        return stale;
    }

    String headerText() {
        return header.getText();
    }

    void click(Row r, int column, boolean focusEditor) {
        navigate(r, column, focusEditor);
    }

    @Override
    public void dispose() {
        detachDocument();
    }
}
