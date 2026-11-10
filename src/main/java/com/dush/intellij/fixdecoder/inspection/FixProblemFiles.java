package com.dush.intellij.fixdecoder.inspection;

import com.dush.intellij.fixdecoder.core.parse.Problem;
import com.dush.intellij.fixdecoder.extract.FixMessages;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.dush.intellij.fixdecoder.settings.FixDecoderSettings;
import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory;
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar;
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marks files that contain FIX problems the way compile errors are marked: a red wavy underline on the file name in the
 * Project view and on its editor tab.
 * <p>
 * The IDE marks files only for errors found by the compiler or annotators, not by inspections, so a highlighting pass
 * reports the file to {@link WolfTheProblemSolver} itself whenever an open file is analysed. The problem tracker only
 * marks files its filters accept; {@link Filter} accepts the files reported here, so plain-text files are marked too.
 */
public final class FixProblemFiles implements TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar, DumbAware {
    /** Identifies this plugin's reports to the problem tracker. */
    private static final Object SOURCE = FixProblemFiles.class;
    /** Files reported as having problems, for {@link Filter}. */
    private static final Set<VirtualFile> REPORTED = ConcurrentHashMap.newKeySet();

    @Override
    public void registerHighlightingPassFactory(@NotNull TextEditorHighlightingPassRegistrar registrar, @NotNull Project project) {
        registrar.registerTextEditorHighlightingPass(this, null, null, false, -1);
    }

    @Override
    public @Nullable TextEditorHighlightingPass createHighlightingPass(@NotNull PsiFile file, @NotNull Editor editor) {
        VirtualFile vf = file.getVirtualFile();
        return vf == null ? null : new Pass(file, vf, editor);
    }

    /** True when the file has a problem that one of the switched-on checks reports. */
    static boolean hasProblems(@NotNull PsiFile file) {
        FixDecoderSettings.State settings = FixDecoderSettings.of(file.getProject());
        for (FoundMessage m : FixMessages.in(file)) {
            for (Problem p : m.message().problems()) {
                switch (p.kind().category()) {
                    case GROUP:
                        if (settings.groupValidation) return true;
                        break;
                    case LENGTH_CHECKSUM:
                        if (settings.lengthChecksumFix) return true;
                        break;
                    default:
                        return true;
                }
            }
        }
        return false;
    }

    private static final class Pass extends TextEditorHighlightingPass {
        private final PsiFile file;
        private final VirtualFile virtualFile;
        private boolean problems;

        Pass(PsiFile file, VirtualFile virtualFile, Editor editor) {
            super(file.getProject(), editor.getDocument(), false);
            this.file = file;
            this.virtualFile = virtualFile;
        }

        @Override
        public void doCollectInformation(@NotNull ProgressIndicator progress) {
            problems = hasProblems(file);
        }

        @Override
        public void doApplyInformationToEditor() {
            if (myProject.isDisposed() || !virtualFile.isValid()) return;
            WolfTheProblemSolver wolf = WolfTheProblemSolver.getInstance(myProject);
            if (problems) {
                REPORTED.add(virtualFile);
                wolf.reportProblemsFromExternalSource(virtualFile, SOURCE);
            } else if (REPORTED.remove(virtualFile)) {
                wolf.clearProblemsFromExternalSource(virtualFile, SOURCE);
            }
        }
    }

    /** Lets the problem tracker mark the files reported here, whatever their type or location. */
    public static final class Filter implements Condition<VirtualFile> {
        @Override
        public boolean value(VirtualFile file) {
            return REPORTED.contains(file);
        }
    }

    /** For tests. */
    static boolean isReported(VirtualFile file) {
        return REPORTED.contains(file);
    }
}
