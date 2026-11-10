package com.dush.intellij.fixdecoder.java;

import com.dush.intellij.fixdecoder.core.parse.FixMessage;
import com.intellij.openapi.project.Project;

/** Opens the package-private parts of {@link FixPasteHandler} to tests in other packages. */
public final class PasteTestAccess {
    private PasteTestAccess() {
    }

    public static FixMessage wholeMessage(Project project, String text) {
        return FixPasteHandler.wholeMessage(project, text);
    }

    public static String javaText(FixMessage m, boolean insideLiteral) {
        return FixPasteHandler.javaText(m, insideLiteral);
    }
}
