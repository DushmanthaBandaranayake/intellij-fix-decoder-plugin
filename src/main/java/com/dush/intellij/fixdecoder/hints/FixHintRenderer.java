package com.dush.intellij.fixdecoder.hints;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/** Draws a value's meaning after it, like a parameter name hint: small text on a rounded background. */
public final class FixHintRenderer implements EditorCustomElementRenderer {
    private static final int PADDING = 4;
    private static final int GAP = 2;

    private final String text;

    public FixHintRenderer(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    private static Font font(Editor editor) {
        Font base = editor.getColorsScheme().getFont(EditorFontType.ITALIC);
        return base.deriveFont(Math.max(base.getSize2D() - 1f, 8f));
    }

    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
        Editor editor = inlay.getEditor();
        FontMetrics fm = editor.getContentComponent().getFontMetrics(font(editor));
        return fm.stringWidth(text) + 2 * PADDING + 2 * GAP;
    }

    @Override
    public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle r, @NotNull TextAttributes textAttributes) {
        Editor editor = inlay.getEditor();
        TextAttributes hint = editor.getColorsScheme().getAttributes(DefaultLanguageHighlighterColors.INLINE_PARAMETER_HINT);
        Color background = hint != null && hint.getBackgroundColor() != null ? hint.getBackgroundColor() : JBColor.namedColor("Editor.SearchField.background", new JBColor(0xEDEDED, 0x3C3F41));
        Color foreground = hint != null && hint.getForegroundColor() != null ? hint.getForegroundColor() : JBColor.GRAY;
        Font font = font(editor);
        FontMetrics fm = g.getFontMetrics(font);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int height = Math.min(r.height, fm.getHeight() + 2);
            int y = r.y + (r.height - height) / 2;
            g2.setColor(background);
            g2.fillRoundRect(r.x + GAP, y, r.width - 2 * GAP, height, 6, 6);
            g2.setColor(foreground);
            g2.setFont(font);
            g2.drawString(text, r.x + GAP + PADDING, y + (height - fm.getHeight()) / 2 + fm.getAscent());
        } finally {
            g2.dispose();
        }
    }
}
