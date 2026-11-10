package com.dush.intellij.fixdecoder.extract;

import com.dush.intellij.fixdecoder.settings.FixDictionaries;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Finds FIX messages in one kind of file. Registered through {@code com.dush.fixdecoder.messageFinder};
 * files that no finder handles are scanned as plain text by {@link TextMessageFinder}.
 */
public interface FixMessageFinder {
    ExtensionPointName<FixMessageFinder> EP = ExtensionPointName.create("com.dush.fixdecoder.messageFinder");

    /** The messages in the file, or null when this finder does not handle this kind of file. Called in a read action. */
    @Nullable
    List<FoundMessage> find(@NotNull PsiFile file, @NotNull FixDictionaries dictionaries);
}
