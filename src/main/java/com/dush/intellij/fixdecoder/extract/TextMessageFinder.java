package com.dush.intellij.fixdecoder.extract;

import com.dush.intellij.fixdecoder.core.dictionary.DictionarySet;
import com.dush.intellij.fixdecoder.core.parse.FixMessage;
import com.dush.intellij.fixdecoder.core.parse.FixParser;
import com.dush.intellij.fixdecoder.core.parse.FixScanner;
import com.dush.intellij.fixdecoder.settings.FixDictionaries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Finds messages in text as it is written, for files that no {@link FixMessageFinder} handles. */
public final class TextMessageFinder {
    /** Larger files are not scanned: highlighting and hover would get slow. */
    public static final int MAX_LENGTH = 2 * 1024 * 1024;

    private TextMessageFinder() {
    }

    public static List<FoundMessage> find(CharSequence text, FixDictionaries dictionaries) {
        if (text.length() > MAX_LENGTH) return Collections.emptyList();
        DictionarySet set = dictionaries.set();
        List<FoundMessage> out = new ArrayList<>();
        for (FixScanner.Span span : FixScanner.scan(text, set::isKnownTag)) {
            FoundMessage m = message(text.subSequence(span.start(), span.end()).toString(), span.start(), dictionaries);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** Parses {@code message} (text that is one message) written at {@code offset} in the document. */
    static FoundMessage message(String message, int offset, FixDictionaries dictionaries) {
        FixMessage parsed = FixParser.parse(message, dictionaries.set(), dictionaries.defaultVersion());
        if (parsed == null) return null;
        int[] starts = new int[message.length()];
        int[] ends = new int[message.length()];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = offset + i;
            ends[i] = offset + i + 1;
        }
        return new FoundMessage(parsed, starts, ends, FoundMessage.Escaper.NONE, false);
    }
}
