package com.dush.intellij.fixdecoder;

import com.intellij.openapi.util.IconLoader;

import javax.swing.Icon;

/** The plugin's icons; each has a {@code _dark} variant next to it that the IDE picks for dark themes. */
public final class FixDecoderIcons {
    /** 16x16: menus and the Alt+Enter list. */
    public static final Icon DECODER = IconLoader.getIcon("/icons/fixDecoder.svg", FixDecoderIcons.class);

    private FixDecoderIcons() {
    }
}
