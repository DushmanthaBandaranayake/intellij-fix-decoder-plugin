package com.dush.intellij.fixdecoder.settings;

import com.dush.intellij.fixdecoder.FixTestCase;
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
        FixDecoderSettings.of(getProject()).defaultVersion = "FIX.4.2";
        FixDictionaries.get(getProject()).reload();
        java("String m = \"35=D|55=IBM|54=1|\";");
        assertEquals("FIX 4.2", only().message().dictionary().label());
    }

    public void testConfigurableRoundTrip() throws Exception {
        FixDecoderConfigurable c = new FixDecoderConfigurable(getProject());
        c.createComponent();
        assertFalse(c.isModified());
        c.apply();
        c.disposeUIResources();
    }
}
