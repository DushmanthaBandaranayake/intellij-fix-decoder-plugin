package com.dush.intellij.fixdecoder.settings;

import com.dush.intellij.fixdecoder.FixTestCase;
import com.dush.intellij.fixdecoder.core.dictionary.DictionarySet;
import com.dush.intellij.fixdecoder.core.parse.FixMessage;
import com.dush.intellij.fixdecoder.hover.FieldInfo;
import com.intellij.openapi.options.ConfigurationException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SettingsTest extends FixTestCase {

    private Path venueDictionary() throws Exception {
        Path file = Files.createTempFile("venue", ".xml");
        Files.write(file, ("<fix major=\"4\" minor=\"4\"><fields>"
                + "<field number=\"5001\" name=\"VenueFlag\" type=\"CHAR\"><value enum=\"Y\" description=\"YES\"/></field>"
                + "</fields></fix>").getBytes(StandardCharsets.UTF_8));
        file.toFile().deleteOnExit();
        return file;
    }

    public void testCustomDictionaryAddsTags() throws Exception {
        Path file = venueDictionary();
        FixDecoderSettings.of(getProject()).customDictionaries.add(file.toString());
        FixDictionaries.get(getProject()).reload();
        java("String m = \"8=FIX.4.4|35=D|5001=Y|55=IBM|\";");
        FixMessage m = only().message();
        assertEquals("Yes", m.meaning(m.first(5001)));
        String html = FieldInfo.html(m, m.first(5001));
        assertTrue(html, html.contains("<b>VenueFlag</b>"));
        assertTrue(m.dictionary().label(), m.dictionary().label().endsWith(file.getFileName().toString()));
    }

    public void testBadDictionaryIsReportedInTheDialog() throws Exception {
        Path bad = Files.createTempFile("bad", ".xml");
        Files.write(bad, "<nope/>".getBytes(StandardCharsets.UTF_8));
        bad.toFile().deleteOnExit();
        try {
            FixDecoderConfigurable.check(getProject(), List.of(bad.toString()));
            fail("expected an error");
        } catch (ConfigurationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("root element must be <fix>"));
        }
        FixDecoderConfigurable.check(getProject(), List.of(venueDictionary().toString()));
    }

    public void testDefaultVersionForFragments() {
        java("String m = \"35=D|55=IBM|54=1|\";");
        // a fragment has no BeginString: FIX 4.4, unless the dictionary in use is a version
        assertEquals("FIX 4.4", only().message().dictionary().label());
        FixActiveDictionary.choose(getProject(), DictionarySet.FIX42);
        assertEquals("FIX 4.2", only().message().dictionary().label());
    }

    public void testConfigurableRoundTrip() throws Exception {
        FixDecoderConfigurable c = new FixDecoderConfigurable(getProject());
        c.createComponent();
        assertFalse(c.isModified());
        c.apply();
        c.disposeUIResources();
    }

    public void testActiveDictionaryAppliesToTheEditor() throws Exception {
        Path file = venueDictionary();
        FixDecoderSettings.of(getProject()).customDictionaries.add(file.toString());
        java("String m = \"8=FIX.4.2|35=D|5001=Y|55=IBM|\";");
        // auto: the file is for FIX 4.4, the message says 4.2
        assertNull(only().message().node(2).def());

        // the custom dictionary on its own: every message is decoded with FIX 4.4 plus that file
        FixActiveDictionary.choose(getProject(), FixActiveDictionary.FILE + file);
        FixMessage m = only().message();
        assertEquals("VenueFlag", m.node(2).name());
        assertTrue(m.dictionary().label(), m.dictionary().label().startsWith("FIX 4.4 + "));

        // a standard version: no custom dictionary
        FixActiveDictionary.choose(getProject(), DictionarySet.FIX44);
        assertNull(only().message().node(2).def());
        assertEquals("FIX 4.4", only().message().dictionary().label());

        // applying the settings page keeps the choice
        FixDecoderConfigurable c = new FixDecoderConfigurable(getProject());
        c.createComponent();
        assertFalse(c.isModified());
        c.apply();
        c.disposeUIResources();
        assertEquals(DictionarySet.FIX44, FixActiveDictionary.of(getProject()));

        // a choice that is no longer offered falls back to auto
        FixActiveDictionary.choose(getProject(), FixActiveDictionary.FILE + file);
        FixDecoderSettings.of(getProject()).customDictionaries.clear();
        assertEquals(FixActiveDictionary.AUTO, FixActiveDictionary.of(getProject()));
    }
}
