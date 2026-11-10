package com.dush.intellij.fixdecoder.extract;

import com.dush.intellij.fixdecoder.settings.FixDictionaries;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Entry point for the IDE features: the messages in a file, cached until the file or the dictionaries change. */
public final class FixMessages {
    private static final Key<CachedValue<List<FoundMessage>>> KEY = Key.create("com.dush.intellij.fixdecoder.messages");

    private FixMessages() {
    }

    /** All messages in the file, in document order. Call in a read action. */
    @NotNull
    public static List<FoundMessage> in(@NotNull PsiFile file) {
        PsiFile top = file.getOriginalFile();
        FixDictionaries dictionaries = FixDictionaries.get(top.getProject());
        return CachedValuesManager.getManager(top.getProject()).getCachedValue(top, KEY,
                () -> CachedValueProvider.Result.create(Collections.unmodifiableList(compute(top, dictionaries)), top, dictionaries),
                false);
    }

    private static List<FoundMessage> compute(PsiFile file, FixDictionaries dictionaries) {
        for (FixMessageFinder finder : FixMessageFinder.EP.getExtensionList()) {
            List<FoundMessage> found = finder.find(file, dictionaries);
            if (found != null) return found;
        }
        return TextMessageFinder.find(file.getViewProvider().getContents(), dictionaries);
    }

    /**
     * Runs {@code compute} on the document's file in a background read action, once the document is committed, then
     * passes the result (null when there is no file) to {@code onUiThread}. Finding messages resolves Java constants,
     * which must not happen on the UI thread.
     */
    public static <T> void inBackground(@NotNull Project project, @NotNull Document document,
                                        @NotNull Function<PsiFile, T> compute, @NotNull Consumer<T> onUiThread) {
        ReadAction.nonBlocking(() -> {
                    PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
                    return file == null ? null : compute.apply(file);
                })
                .withDocumentsCommitted(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), onUiThread::accept)
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /** The message containing the document offset, or null. */
    @Nullable
    public static FoundMessage at(@NotNull PsiFile file, int offset) {
        for (FoundMessage m : in(file)) {
            if (m.contains(offset)) return m;
        }
        return null;
    }

    /** The message containing the offset, or ending just before it (the caret is often right after a literal). */
    @Nullable
    public static FoundMessage atCaret(@NotNull PsiFile file, int offset) {
        FoundMessage m = at(file, offset);
        return m != null || offset == 0 ? m : at(file, offset - 1);
    }
}
