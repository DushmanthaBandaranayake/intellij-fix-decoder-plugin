package com.dush.intellij.fixdecoder.java;

import com.dush.intellij.fixdecoder.extract.FoundMessage;

/** Escapes text for a Java string literal, writing SOH the way the literal already does ({@code \u0001} or {@code \001}). */
public final class JavaEscaper implements FoundMessage.Escaper {
    private final String soh;

    public JavaEscaper(String soh) {
        this.soh = soh;
    }

    @Override
    public String escape(String text) {
        return escape(text, soh);
    }

    public static String escape(String text, String soh) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\u0001':
                    sb.append(soh);
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
