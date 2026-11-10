package com.dush.intellij.fixdecoder.core.parse;

import java.util.regex.Pattern;

/** Cleans up text that a user pastes, so that a message copied from source code can be decoded. */
public final class FixText {
    /** SOH written as an escape: backslash-u-0001, backslash-001 or backslash-x01 (also with doubled backslashes). */
    private static final Pattern SOH_ESCAPE = Pattern.compile("\\\\+(?:u0001|001|x01)");
    /** Where a Java concatenation joins two string literals: closing quote, plus, opening quote. */
    private static final Pattern JOIN = Pattern.compile("\"\\s*\\+\\s*\"");

    private FixText() {
    }

    /**
     * Turns SOH escapes into real SOH characters, joins string literals concatenated with {@code +},
     * and removes the quotes around the whole text. Other text is left as it is.
     */
    public static String fromSource(String text) {
        String s = JOIN.matcher(text.trim()).replaceAll("");
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return SOH_ESCAPE.matcher(s).replaceAll(FixScanner.SOH);
    }
}
