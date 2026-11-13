package com.dush.intellij.fixdecoder.settings;

import com.dush.intellij.fixdecoder.core.dictionary.DictionaryParser;
import com.dush.intellij.fixdecoder.core.dictionary.DictionarySet;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Settings > Tools > FIX Decoder. */
public final class FixDecoderConfigurable implements Configurable {
    private final Project project;

    private CollectionListModel<String> dictionaries;
    private ComboBox<String> version;
    private JBCheckBox groupValidation;
    private JBCheckBox lengthChecksum;
    private JBCheckBox inlineHints;
    private JBCheckBox pastePopup;

    public FixDecoderConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nls String getDisplayName() {
        return "FIX Decoder";
    }

    @Override
    public @Nullable JComponent createComponent() {
        dictionaries = new CollectionListModel<>();
        JBList<String> list = new JBList<>(dictionaries);
        list.getEmptyText().setText("Only the bundled dictionaries");
        JPanel dictionaryPanel = ToolbarDecorator.createDecorator(list)
                .setAddAction(b -> chooseDictionary())
                .disableUpDownActions()
                .setPreferredSize(new java.awt.Dimension(400, 100))
                .createPanel();

        version = new ComboBox<>(DictionarySet.VERSIONS.toArray(new String[0]));
        // A plain Swing renderer: SimpleListCellRenderer.create(String, Function) is scheduled for removal.
        version.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value == null ? "" : versionLabel((String) value), index, selected, focus);
            }
        });

        groupValidation = new JBCheckBox("Check repeating groups");
        lengthChecksum = new JBCheckBox("Check and fix BodyLength (9) and CheckSum (10)");
        inlineHints = new JBCheckBox("Show value meanings as inline hints (54=1 Buy)");
        pastePopup = new JBCheckBox("Ask how to paste a FIX message into Java code");

        JBLabel dictionaryHelp = new JBLabel("QuickFIX/J XML files, laid over the bundled FIX 4.2, 4.4 and 5.0 SP2 dictionaries. "
                + "A file applies to the version in its <fix> element, or to all versions when it has none.");
        dictionaryHelp.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        dictionaryHelp.setForeground(UIUtil.getContextHelpForeground());

        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Custom dictionaries:", dictionaryPanel, true)
                .addComponentToRightColumn(dictionaryHelp)
                .addLabeledComponent("Version for messages without BeginString (8):", version)
                .addSeparator()
                .addComponent(groupValidation)
                .addComponent(lengthChecksum)
                .addComponent(inlineHints)
                .addComponent(pastePopup)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    public static String versionLabel(String v) {
        switch (v) {
            case DictionarySet.FIX42:
                return "FIX 4.2";
            case DictionarySet.FIX44:
                return "FIX 4.4";
            case DictionarySet.FIX50SP2:
                return "FIXT 1.1 / FIX 5.0 SP2";
            default:
                return v;
        }
    }

    private void chooseDictionary() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withFileFilter(f -> "xml".equalsIgnoreCase(f.getExtension()))
                .withTitle("Choose a QuickFIX/J Dictionary");
        VirtualFile file = FileChooser.chooseFile(descriptor, project, null);
        if (file == null) return;
        String path = file.getPath();
        String base = project.getBasePath();
        if (base != null && FileUtil.isAncestor(base, path, true)) {
            String relative = FileUtil.getRelativePath(base, path, '/');
            if (relative != null) path = relative;
        }
        if (!dictionaries.contains(path)) dictionaries.add(path);
    }

    private FixDecoderSettings.State state() {
        return FixDecoderSettings.of(project);
    }

    @Override
    public boolean isModified() {
        FixDecoderSettings.State s = state();
        return !dictionaries.getItems().equals(s.customDictionaries)
                || !Objects.equals(version.getSelectedItem(), s.defaultVersion)
                || groupValidation.isSelected() != s.groupValidation
                || lengthChecksum.isSelected() != s.lengthChecksumFix
                || inlineHints.isSelected() != s.inlineHints
                || pastePopup.isSelected() != s.pastePopup;
    }

    @Override
    public void apply() throws ConfigurationException {
        List<String> paths = new ArrayList<>(dictionaries.getItems());
        check(project, paths);
        FixDecoderSettings.State s = state();
        s.customDictionaries = paths;
        s.defaultVersion = (String) version.getSelectedItem();
        s.groupValidation = groupValidation.isSelected();
        s.lengthChecksumFix = lengthChecksum.isSelected();
        s.inlineHints = inlineHints.isSelected();
        s.pastePopup = pastePopup.isSelected();
        FixDictionaries.get(project).reload();
        DaemonCodeAnalyzer.getInstance(project).restart();
        com.dush.intellij.fixdecoder.toolwindow.FixDecoderToolWindow.dictionariesChanged(project);
    }

    /** Loads every custom dictionary once, so that a bad file is reported in the dialog. */
    static void check(Project project, List<String> paths) throws ConfigurationException {
        DictionarySet set = DictionarySet.bundled();
        for (String p : paths) {
            Path file = FixDictionaries.resolve(project, p);
            try {
                set = FixDictionaries.withCustom(set, file);
            } catch (IOException e) {
                throw new ConfigurationException("Can't read " + file + ": " + e.getMessage(), "FIX Decoder");
            } catch (DictionaryParser.DictionaryException e) {
                throw new ConfigurationException(file.getFileName() + ": " + e.getMessage(), "FIX Decoder");
            }
        }
    }

    @Override
    public void reset() {
        FixDecoderSettings.State s = state();
        dictionaries.replaceAll(s.customDictionaries);
        version.setSelectedItem(s.defaultVersion);
        groupValidation.setSelected(s.groupValidation);
        lengthChecksum.setSelected(s.lengthChecksumFix);
        inlineHints.setSelected(s.inlineHints);
        pastePopup.setSelected(s.pastePopup);
    }

    @Override
    public void disposeUIResources() {
        dictionaries = null;
        version = null;
        groupValidation = null;
        lengthChecksum = null;
        inlineHints = null;
        pastePopup = null;
    }
}
