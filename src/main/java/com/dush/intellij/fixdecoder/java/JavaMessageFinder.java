package com.dush.intellij.fixdecoder.java;

import com.dush.intellij.fixdecoder.core.dictionary.DictionarySet;
import com.dush.intellij.fixdecoder.core.parse.FixMessage;
import com.dush.intellij.fixdecoder.core.parse.FixParser;
import com.dush.intellij.fixdecoder.core.parse.FixScanner;
import com.dush.intellij.fixdecoder.extract.FixMessageFinder;
import com.dush.intellij.fixdecoder.extract.FoundMessage;
import com.dush.intellij.fixdecoder.settings.FixDictionaries;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.LiteralTextEscaper;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiPolyadicExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds messages in Java string literals, text blocks and {@code +} concatenations of them.
 * <p>
 * Literal parts are decoded with the literal's own escaper, which also gives the document offset of every decoded
 * character. Other parts are evaluated as constants (e.g. {@code SOH}); their characters have no document range.
 * Parts that are not constant, such as method calls, become {@link FixScanner#UNKNOWN}.
 */
public final class JavaMessageFinder implements FixMessageFinder {

    @Override
    public @Nullable List<FoundMessage> find(@NotNull PsiFile file, @NotNull FixDictionaries dictionaries) {
        if (!(file instanceof PsiJavaFile)) return null;
        List<FoundMessage> out = new ArrayList<>();
        Set<PsiElement> done = new HashSet<>();
        file.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitPolyadicExpression(PsiPolyadicExpression expression) {
                if (isStringConcatenation(expression) && !isStringConcatenation(parentSkippingParentheses(expression))) {
                    List<PsiExpression> parts = new ArrayList<>();
                    collectParts(expression, parts);
                    boolean hasLiteral = false;
                    for (PsiExpression p : parts) {
                        if (isStringLiteral(p)) {
                            done.add(p);
                            hasLiteral |= p.getText().indexOf('=') >= 0;
                        }
                    }
                    if (hasLiteral) addMessages(parts, dictionaries, out);
                }
                super.visitPolyadicExpression(expression);
            }

            @Override
            public void visitLiteralExpression(PsiLiteralExpression expression) {
                if (!done.contains(expression) && isStringLiteral(expression) && expression.getText().indexOf('=') >= 0) {
                    addMessages(List.of(expression), dictionaries, out);
                }
            }
        });
        out.sort((a, b) -> Integer.compare(start(a), start(b)));
        return out;
    }

    private static int start(FoundMessage m) {
        TextRange r = m.displayRange();
        return r == null ? Integer.MAX_VALUE : r.getStartOffset();
    }

    static boolean isStringLiteral(PsiElement e) {
        return e instanceof PsiLiteralExpression && ((PsiLiteralExpression) e).getValue() instanceof String;
    }

    /**
     * A {@code +} expression with a string literal among its operands (looking through parentheses and nested {@code +}).
     * Decided from the syntax alone: resolving the expression's type would be a slow operation.
     */
    private static boolean isStringConcatenation(PsiElement e) {
        if (!(e instanceof PsiPolyadicExpression)) return false;
        PsiPolyadicExpression p = (PsiPolyadicExpression) e;
        if (p.getOperationTokenType() != JavaTokenType.PLUS) return false;
        for (PsiExpression operand : p.getOperands()) {
            PsiExpression o = operand;
            while (o instanceof PsiParenthesizedExpression) o = ((PsiParenthesizedExpression) o).getExpression();
            if (isStringLiteral(o) || isStringConcatenation(o)) return true;
        }
        return false;
    }

    private static PsiElement parentSkippingParentheses(PsiElement e) {
        PsiElement p = e.getParent();
        while (p instanceof PsiParenthesizedExpression) p = p.getParent();
        return p;
    }

    private static void collectParts(PsiExpression e, List<PsiExpression> out) {
        while (e instanceof PsiParenthesizedExpression) e = ((PsiParenthesizedExpression) e).getExpression();
        if (e == null) return;
        if (isStringConcatenation(e)) {
            for (PsiExpression operand : ((PsiPolyadicExpression) e).getOperands()) collectParts(operand, out);
        } else {
            out.add(e);
        }
    }

    /** Builds the decoded text of the parts, finds the messages in it and adds them to {@code out}. */
    private static void addMessages(List<PsiExpression> parts, FixDictionaries dictionaries, List<FoundMessage> out) {
        StringBuilder text = new StringBuilder();
        IntList starts = new IntList();
        IntList ends = new IntList();
        boolean octalSoh = false;
        boolean unicodeSoh = false;
        for (PsiExpression part : parts) {
            if (isStringLiteral(part)) {
                String literal = part.getText();
                octalSoh |= literal.contains("\\001");
                unicodeSoh |= literal.contains("\\u0001");
                if (decodeLiteral((PsiLiteralExpression) part, text, starts, ends)) continue;
                append(text, starts, ends, String.valueOf(((PsiLiteralExpression) part).getValue()));
            } else {
                Object value = JavaPsiFacade.getInstance(part.getProject()).getConstantEvaluationHelper().computeConstantExpression(part);
                append(text, starts, ends, value != null ? String.valueOf(value) : String.valueOf(FixScanner.UNKNOWN));
            }
        }
        if (text.indexOf("=") < 0) return;
        DictionarySet set = dictionaries.set();
        FoundMessage.Escaper escaper = new JavaEscaper(octalSoh && !unicodeSoh ? "\\001" : "\\u0001");
        for (FixScanner.Span span : FixScanner.scan(text, set::isKnownTag)) {
            String message = text.substring(span.start(), span.end());
            FixMessage parsed = FixParser.parse(message, set, dictionaries.defaultVersion());
            if (parsed == null) continue;
            out.add(new FoundMessage(parsed,
                    Arrays.copyOfRange(starts.values, span.start(), span.end()),
                    Arrays.copyOfRange(ends.values, span.start(), span.end()),
                    escaper, true));
        }
    }

    /** Decodes a literal with its own escaper, recording where each decoded character is in the document. */
    private static boolean decodeLiteral(PsiLiteralExpression literal, StringBuilder text, IntList starts, IntList ends) {
        if (!(literal instanceof PsiLanguageInjectionHost)) return false;
        PsiLanguageInjectionHost host = (PsiLanguageInjectionHost) literal;
        LiteralTextEscaper<? extends PsiLanguageInjectionHost> escaper = host.createLiteralTextEscaper();
        TextRange range = escaper.getRelevantTextRange();
        StringBuilder decoded = new StringBuilder();
        if (!escaper.decode(range, decoded)) return false;
        int base = literal.getTextRange().getStartOffset();
        int[] hostOffsets = new int[decoded.length() + 1];
        for (int i = 0; i <= decoded.length(); i++) {
            hostOffsets[i] = escaper.getOffsetInHost(i, range);
        }
        for (int i = 0; i < decoded.length(); i++) {
            int s = hostOffsets[i];
            int e = hostOffsets[i + 1];
            text.append(decoded.charAt(i));
            if (s < 0 || e < s) {
                starts.add(-1);
                ends.add(-1);
            } else {
                starts.add(base + s);
                ends.add(base + e);
            }
        }
        return true;
    }

    private static void append(StringBuilder text, IntList starts, IntList ends, String s) {
        for (int i = 0; i < s.length(); i++) {
            text.append(s.charAt(i));
            starts.add(-1);
            ends.add(-1);
        }
    }

    private static final class IntList {
        int[] values = new int[64];
        int size;

        void add(int v) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = v;
        }
    }
}
