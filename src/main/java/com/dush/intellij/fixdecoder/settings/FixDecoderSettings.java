package com.dush.intellij.fixdecoder.settings;

import com.dush.intellij.fixdecoder.core.dictionary.DictionarySet;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** The project's FIX Decoder settings, edited in Settings > Tools > FIX Decoder and stored in {@code .idea/fixDecoder.xml}. */
@State(name = "FixDecoderSettings", storages = @Storage("fixDecoder.xml"))
public final class FixDecoderSettings implements PersistentStateComponent<FixDecoderSettings.State> {

    public static final class State {
        /** Paths of QuickFIX/J XML dictionaries laid over the bundled ones; absolute or relative to the project folder. */
        public List<String> customDictionaries = new ArrayList<>();
        /** Version for messages without a BeginString (8). */
        public String defaultVersion = DictionarySet.FIX44;
        public boolean groupValidation = true;
        public boolean lengthChecksumFix = true;
        public boolean inlineHints = false;
        public boolean pastePopup = true;

        public State copy() {
            State s = new State();
            s.customDictionaries = new ArrayList<>(customDictionaries);
            s.defaultVersion = defaultVersion;
            s.groupValidation = groupValidation;
            s.lengthChecksumFix = lengthChecksumFix;
            s.inlineHints = inlineHints;
            s.pastePopup = pastePopup;
            return s;
        }
    }

    private State state = new State();

    public static FixDecoderSettings get(@NotNull Project project) {
        return project.getService(FixDecoderSettings.class);
    }

    public static State of(@NotNull Project project) {
        return get(project).state;
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }
}
