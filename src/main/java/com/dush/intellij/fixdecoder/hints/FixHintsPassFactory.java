package com.dush.intellij.fixdecoder.hints;

import com.dush.intellij.fixdecoder.core.parse.FixMessage;
import com.dush.intellij.fixdecoder.core.parse.RawField;
import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.dush.intellij.fixdecoder.settings.FixDecoderSettings;
import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory;
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar;
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Inline hints after values that have a meaning ({@code 54=1} <i>Buy</i>, {@code 35=D} <i>NewOrderSingle</i>).
 * Made by a highlighting pass that adds inlays itself, because the inlay-provider APIs changed between 2021.3 and now.
 */
public final class FixHintsPassFactory implements TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar, DumbAware {

    @Override
    public void registerHighlightingPassFactory(@NotNull TextEditorHighlightingPassRegistrar registrar, @NotNull Project project) {
        registrar.registerTextEditorHighlightingPass(this, null, null, false, -1);
    }

    @Override
    public @Nullable TextEditorHighlightingPass createHighlightingPass(@NotNull PsiFile file, @NotNull Editor editor) {
        return new Pass(file, editor);
    }

    /** One hint: the text to show after a document offset. */
    static final class Hint {
        final int offset;
        final String text;

        Hint(int offset, String text) {
            this.offset = offset;
            this.text = text;
        }
    }

    /** The hints for a file; empty when hints are switched off. */
    static List<Hint> hints(@NotNull PsiFile file) {
        List<Hint> out = new ArrayList<>();
        if (!FixDecoderSettings.of(file.getProject()).inlineHints) return out;
        for (FoundMessage m : FixMessages.in(file)) {
            FixMessage message = m.message();
            for (RawField f : message.fields()) {
                String meaning = message.meaning(f);
                if (meaning == null) continue;
                TextRange value = m.valueRange(f);
                if (value != null) out.add(new Hint(value.getEndOffset(), meaning));
            }
        }
        return out;
    }

    private static final class Pass extends TextEditorHighlightingPass {
        private final PsiFile file;
        private final Editor editor;
        private List<Hint> hints = new ArrayList<>();

        Pass(PsiFile file, Editor editor) {
            super(file.getProject(), editor.getDocument(), false);
            this.file = file;
            this.editor = editor;
        }

        @Override
        public void doCollectInformation(@NotNull ProgressIndicator progress) {
            hints = hints(file);
        }

        @Override
        public void doApplyInformationToEditor() {
            if (editor.isDisposed()) return;
            List<Inlay<? extends FixHintRenderer>> existing = editor.getInlayModel()
                    .getInlineElementsInRange(0, editor.getDocument().getTextLength(), FixHintRenderer.class);
            if (same(existing, hints)) return;
            for (Inlay<?> inlay : existing) inlay.dispose();
            int length = editor.getDocument().getTextLength();
            for (Hint h : hints) {
                if (h.offset <= length) editor.getInlayModel().addInlineElement(h.offset, true, new FixHintRenderer(h.text));
            }
        }

        private static boolean same(List<Inlay<? extends FixHintRenderer>> existing, List<Hint> hints) {
            if (existing.size() != hints.size()) return false;
            for (int i = 0; i < hints.size(); i++) {
                Inlay<? extends FixHintRenderer> inlay = existing.get(i);
                if (inlay.getOffset() != hints.get(i).offset || !inlay.getRenderer().text().equals(hints.get(i).text)) return false;
            }
            return true;
        }
    }
}
