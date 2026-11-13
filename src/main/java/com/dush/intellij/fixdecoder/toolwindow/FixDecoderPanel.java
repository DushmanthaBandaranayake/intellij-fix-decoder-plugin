package com.dush.intellij.fixdecoder.toolwindow;

import com.dush.intellij.fixdecoder.core.dictionary.DictionarySet;
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
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
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
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
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
import java.awt.event.InputEvent;
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
    /**
     * Highlights the fields whose tag, name, value or meaning contain the text; nothing is hidden. Ctrl+F (the Find
     * shortcut) focuses it, Enter and Shift+Enter move between the matches.
     */
    private final SearchTextField search = new SearchTextField(false);
    /** The rows that match the search, top to bottom. */
    private final List<TreePath> matches = new ArrayList<>();
    private int currentMatch = -1;
    private final TreeTableView table;
    private final List<Row> rows = new ArrayList<>();

    /**
     * The dictionary to decode with: {@link #AUTO} (from BeginString 8 and ApplVerID 1128, as everywhere else in the
     * IDE) or one version, whatever the message says. It only changes this table, not hover or the inspections.
     */
    private final ComboBox<String> dictionary = new ComboBox<>();
    static final String AUTO = "";
    /** The message as found, decoded with the dictionary its BeginString asks for. */
    private FixMessage source;
    /** The message shown: {@link #source}, or the same fields decoded with the chosen dictionary. */
    private FixMessage message;
    /** The message shown, with its document ranges; null for pasted text. Kept to redraw when the view changes. */
    private FoundMessage shownFound;
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

        /** {@code filter} is lower case. */
        boolean matches(String filter) {
            return !instance && (contains(tag, filter) || contains(name, filter) || contains(value, filter) || contains(meaning, filter));
        }

        private static boolean contains(String text, String filter) {
            return text != null && text.toLowerCase(java.util.Locale.ROOT).contains(filter);
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
                if (r != null) appendHighlighted(this, r, r.tag, r.instance ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        });
        table.getEmptyText().setText("Put the caret in a FIX message and choose Decode FIX Message,")
                .appendSecondaryText("or paste a message", SimpleTextAttributes.LINK_ATTRIBUTES, e -> askForMessage());
        installListeners();

        JPanel top = new JPanel(new BorderLayout());
        top.add(toolbar().getComponent(), BorderLayout.WEST);
        header.setBorder(JBUI.Borders.emptyLeft(8));
        top.add(header, BorderLayout.CENTER);
        search.getTextEditor().getEmptyText().setText("Search tag, name, value");
        search.setPreferredSize(new java.awt.Dimension(JBUI.scale(200), search.getPreferredSize().height));
        installDictionaryChoice();
        JPanel right = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        right.add(dictionary, BorderLayout.WEST);
        right.add(search, BorderLayout.CENTER);
        top.add(right, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        installSearch();
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER);
        setColumnWidths();

        EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent e) {
                followCaret(e.getEditor());
            }
        }, this);
    }

    private void installDictionaryChoice() {
        dictionary.addItem(AUTO);
        for (String v : DictionarySet.VERSIONS) dictionary.addItem(v);
        // A plain Swing renderer: SimpleListCellRenderer.create(String, Function) is scheduled for removal.
        dictionary.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focus) {
                String text = value == null || AUTO.equals(value) ? "Auto-detect" : FixDecoderConfigurable.versionLabel((String) value);
                return super.getListCellRendererComponent(list, text, index, selected, focus);
            }
        });
        dictionary.setToolTipText("<html>Dictionary used to decode the message in this table.<br>"
                + "Auto-detect takes it from BeginString (8) and ApplVerID (1128).</html>");
        dictionary.addActionListener(e -> {
            if (source != null) build(source, shownFound);
        });
    }

    /** The chosen version id, or null for auto-detect. */
    @Nullable
    private String chosenVersion() {
        Object v = dictionary.getSelectedItem();
        return v == null || AUTO.equals(v) ? null : (String) v;
    }

    /** {@code m} as it is, or decoded again with the chosen dictionary. */
    private FixMessage decoded(FixMessage m) {
        String version = chosenVersion();
        return version == null ? m : FixParser.reparse(m, FixDictionaries.get(project).set().get(version));
    }

    /**
     * Decodes the message shown again with the current dictionaries: a message in a document is found again, pasted
     * text is parsed again.
     */
    void decodeAgain() {
        if (document != null) {
            refresh();
        } else if (source != null) {
            FixMessage m = FixParser.parse(source.text(), FixDictionaries.get(project).set(), FixDictionaries.get(project).defaultVersion());
            if (m != null) build(m, null);
        }
    }

    /** Ctrl+C: the values of the selected rows, one per line. */
    @Nullable
    String copySelectedValues() {
        List<String> values = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            Row r = rowAt(row);
            if (r != null && !r.instance) values.add(r.value);
        }
        if (values.isEmpty()) return null;
        String text = String.join("\n", values);
        CopyPasteManager.getInstance().setContents(new StringSelection(text));
        return text;
    }

    private void installSearch() {
        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) {
                if (message != null) applySearch(true);
            }
        });
        // IDE shortcuts rather than a key listener: the action system and the search field's own key handling
        // would otherwise take Enter, Up/Down and Esc first.
        JComponent box = search.getTextEditor();
        // Enter jumps to the next match and wraps; Shift+Enter goes back. The focus stays in the box.
        shortcut(box, () -> moveMatch(1), KeyEvent.VK_ENTER, 0, KeyEvent.VK_DOWN, 0);
        shortcut(box, () -> moveMatch(-1), KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_UP, 0);
        // F3 / Shift+F3 anywhere in the decoder, also from the table
        shortcut(this, () -> moveMatch(1), KeyEvent.VK_F3, 0);
        shortcut(this, () -> moveMatch(-1), KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK);
        // first Esc clears the search, the next one goes back to the table
        shortcut(box, () -> {
            if (!search.getText().isEmpty()) search.setText("");
            else table.requestFocusInWindow();
        }, KeyEvent.VK_ESCAPE, 0);
        // Ctrl+F (whatever the Find shortcut is in the keymap) anywhere in the decoder goes to the search box
        new AnAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                focusSearch();
            }
        }.registerCustomShortcutSet(CommonShortcuts.getFind(), this, this);
    }

    /** Runs {@code run} for the given keys (pairs of key code and modifiers) while the focus is in {@code on}. */
    private void shortcut(JComponent on, Runnable run, int... keysAndModifiers) {
        com.intellij.openapi.actionSystem.Shortcut[] shortcuts = new com.intellij.openapi.actionSystem.Shortcut[keysAndModifiers.length / 2];
        for (int i = 0; i < shortcuts.length; i++) {
            shortcuts[i] = new com.intellij.openapi.actionSystem.KeyboardShortcut(
                    KeyStroke.getKeyStroke(keysAndModifiers[2 * i], keysAndModifiers[2 * i + 1]), null);
        }
        new com.intellij.openapi.project.DumbAwareAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                run.run();
            }
        }.registerCustomShortcutSet(new com.intellij.openapi.actionSystem.CustomShortcutSet(shortcuts), on, this);
    }

    void focusSearch() {
        search.requestFocusInWindow();
        search.selectText();
    }

    private String filter() {
        return search.getText().trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Finds the matching rows again and repaints; {@code select} moves the selection to the first match. */
    private void applySearch(boolean select) {
        matches.clear();
        currentMatch = -1;
        String filter = filter();
        if (!filter.isEmpty()) {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) table.getTree().getModel().getRoot();
            for (java.util.Enumeration<?> e = root.preorderEnumeration(); e.hasMoreElements(); ) {
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
                Row r = row(n);
                if (r != null && r.matches(filter)) matches.add(new TreePath(n.getPath()));
            }
        }
        if (select && !matches.isEmpty()) selectMatch(0);
        table.repaint();
        updateHeader(shownFound);
    }

    private void moveMatch(int step) {
        if (matches.isEmpty()) return;
        int from = currentMatch < 0 ? (step > 0 ? -1 : 0) : currentMatch;
        selectMatch(Math.floorMod(from + step, matches.size()));
    }

    private void selectMatch(int i) {
        currentMatch = i;
        int row = table.getTree().getRowForPath(matches.get(i));
        if (row >= 0) {
            table.getSelectionModel().setSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
        }
        updateHeader(shownFound);
    }

    /** Appends {@code text}, marking the parts that match the search the way the IDE marks speed search matches. */
    private void appendHighlighted(com.intellij.ui.SimpleColoredComponent c, Row r, String text, SimpleTextAttributes attrs) {
        String filter = filter();
        if (filter.isEmpty() || !r.matches(filter)) {
            c.append(text, attrs);
            return;
        }
        SimpleTextAttributes match = new SimpleTextAttributes(attrs.getBgColor(), attrs.getFgColor(), null,
                attrs.getStyle() | SimpleTextAttributes.STYLE_SEARCH_MATCH);
        int from = 0;
        for (int i = 0; i + filter.length() <= text.length(); ) {
            if (text.regionMatches(true, i, filter, 0, filter.length())) {
                if (i > from) c.append(text.substring(from, i), attrs);
                c.append(text.substring(i, i + filter.length()), match);
                i += filter.length();
                from = i;
            } else {
                i++;
            }
        }
        if (from < text.length()) c.append(text.substring(from), attrs);
    }

    private ListTreeTableModelOnColumns model(DefaultMutableTreeNode root) {
        ColumnInfo<?, ?>[] columns = {
                new TreeColumnInfo("Tag"),
                new TextColumn("Name", r -> r.name, this::nameRenderer),
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
        group.add(new FlatToggle());
        group.add(new PanelAction("Settings", "Open Settings | Tools | FIX Decoder", AllIcons.General.Settings,
                () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, FixDecoderConfigurable.class)));
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("FixDecoderToolbar", group, true);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    /**
     * Switches between the grouped tree and a flat list. UpdateInBackground (see MessageAtCaretAction) because a
     * toggle's update() reads its state, and newer IDEs want every action that updates to say on which thread.
     */
    @SuppressWarnings("removal")
    private final class FlatToggle extends ToggleAction implements DumbAware, com.intellij.openapi.actionSystem.UpdateInBackground {
        FlatToggle() {
            super("Show as Flat List", "Show every field in message order, without nesting repeating groups", AllIcons.ObjectBrowser.FlattenPackages);
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return isFlat();
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            setFlat(state);
        }
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
        // Ctrl+C (the Copy shortcut of the keymap) copies the Value of the selected rows; an IDE shortcut, so that the
        // table's own copy of whole rows doesn't get there first
        new com.intellij.openapi.project.DumbAwareAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                copySelectedValues();
            }
        }.registerCustomShortcutSet(CommonShortcuts.getCopy(), table, this);
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
        source = m;
        message = decoded(m);
        shownFound = found;
        stale = false;
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        if (isFlat()) addFlat(root, message, found);
        else addNodes(root, message.nodes(), found);
        table.setModel(model(root));
        // a new model shows its root again; the root is only a holder for the rows
        table.setRootVisible(false);
        table.getTree().setShowsRootHandles(true);
        setColumnWidths();
        TreeUtil.expandAll(table.getTree());
        applySearch(false);
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

    /** Flat list: every field in message order; a group's count field still says how many instances were found. */
    private void addFlat(DefaultMutableTreeNode root, FixMessage m, @Nullable FoundMessage found) {
        java.util.Map<Integer, Integer> instances = new java.util.HashMap<>();
        collectGroupSizes(m.nodes(), instances);
        for (RawField f : m.fields()) {
            FixNode.FieldNode n = m.node(f.index());
            if (n == null) continue;
            Integer count = instances.get(f.index());
            String groupMeaning = count == null ? null : count + (count == 1 ? " instance" : " instances");
            root.add(fieldNode(n, found, groupMeaning));
        }
    }

    private static void collectGroupSizes(List<FixNode> nodes, java.util.Map<Integer, Integer> out) {
        for (FixNode n : nodes) {
            if (!(n instanceof FixNode.GroupNode)) continue;
            FixNode.GroupNode g = (FixNode.GroupNode) n;
            out.put(g.count().field().index(), g.instances().size());
            for (FixNode.Instance inst : g.instances()) collectGroupSizes(inst.children(), out);
        }
    }

    // --- flat or grouped view, remembered for the whole IDE ---

    private static final String FLAT_KEY = "com.dush.fixdecoder.flatView";

    static boolean isFlat() {
        return PropertiesComponent.getInstance().getBoolean(FLAT_KEY, false);
    }

    void setFlat(boolean flat) {
        PropertiesComponent.getInstance().setValue(FLAT_KEY, flat, false);
        if (source != null) build(source, shownFound);
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
        if (!filter().isEmpty()) {
            if (matches.isEmpty()) sb.append(" · no matches");
            else if (currentMatch >= 0) sb.append(" · match ").append(currentMatch + 1).append(" of ").append(matches.size());
            else sb.append(" · ").append(matches.size()).append(matches.size() == 1 ? " match" : " matches");
        }
        if (stale) sb.append(" · changed, no longer a FIX message");
        header.setText(sb.toString());
        header.setToolTipText(sb.toString());   // the header is cut off when the tool window is narrow
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
                appendHighlighted(this, r, String.valueOf(value), link && !selected ? SimpleTextAttributes.LINK_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
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
                    appendHighlighted(this, r, String.valueOf(value), SimpleTextAttributes.ERROR_ATTRIBUTES);
                } else {
                    appendHighlighted(this, r, String.valueOf(value), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            }
        };
    }

    private TableCellRenderer nameRenderer(Row r) {
        return new ColoredTableCellRenderer() {
            @Override
            protected void customizeCellRenderer(@NotNull JTable t, Object value, boolean selected, boolean hasFocus, int row, int column) {
                if (value != null) appendHighlighted(this, r, String.valueOf(value), SimpleTextAttributes.REGULAR_ATTRIBUTES);
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

    /** {@link #AUTO} or a version id of {@link DictionarySet#VERSIONS}. */
    void setDictionary(String version) {
        dictionary.setSelectedItem(version);
    }

    void setSearchText(String text) {
        search.setText(text);
    }

    /** The tags of the rows that match the search, top to bottom. */
    List<String> matchedTags() {
        List<String> out = new ArrayList<>();
        for (TreePath p : matches) out.add(row(p.getLastPathComponent()).tag);
        return out;
    }

    /** The selected row, or null. */
    Row selectedRow() {
        int row = table.getSelectedRow();
        return row < 0 ? null : rowAt(row);
    }

    void nextMatch(int step) {
        moveMatch(step);
    }

    /** The tags of the rows shown, top to bottom. */
    List<String> shownTags() {
        List<String> out = new ArrayList<>();
        javax.swing.tree.TreeModel model = table.getTree().getModel();
        collectShown((DefaultMutableTreeNode) model.getRoot(), out);
        return out;
    }

    private static void collectShown(DefaultMutableTreeNode node, List<String> out) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) node.getChildAt(i);
            Row r = row(c);
            if (r != null) out.add(r.tag);
            collectShown(c, out);
        }
    }

    boolean isRootVisible() {
        return table.getTree().isRootVisible();
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
