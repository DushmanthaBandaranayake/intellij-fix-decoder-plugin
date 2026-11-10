package com.dush.intellij.fixdecoder.inspection;

import com.dush.intellij.fixdecoder.core.parse.Problem;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Values of enumerated fields that are not defined in the dictionary. */
public final class FixEnumValueInspection extends FixInspectionBase {

    @Override
    protected Problem.Category category() {
        return Problem.Category.ENUM;
    }

    @Override
    protected boolean enabled(@NotNull Project project) {
        return true;
    }
}
