package com.dush.intellij.fixdecoder.settings;

import com.dush.intellij.fixdecoder.core.dictionary.DictionarySet;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The dictionary in use for the project's FIX messages, in the editor (hover, inspections, hints) and in the decoder
 * table alike. It is chosen in the decoder's drop-down, as a key:
 * <ul>
 *   <li>{@link #AUTO}, the default: each message's version comes from its BeginString (8) and ApplVerID (1128),
 *       and every custom dictionary is laid over the standard ones; a message without BeginString is FIX 4.4</li>
 *   <li>a version id of {@link DictionarySet#VERSIONS}: that standard dictionary for every message, whatever its
 *       BeginString says, without custom dictionaries</li>
 *   <li>{@link #FILE} + a custom dictionary path from the settings: the standard dictionary with only that file laid
 *       over it. A file for one version is used for every message; a file for all versions leaves the version to
 *       the message</li>
 * </ul>
 * Stored per user in the workspace file, not in {@code .idea/fixDecoder.xml}: switching between venues should not
 * show up in version control or change the choice of other people.
 */
@State(name = "FixDecoderActiveDictionary", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class FixActiveDictionary implements PersistentStateComponent<FixActiveDictionary.State> {
    public static final String AUTO = "";
    public static final String FILE = "file:";

    public static final class State {
        public String choice = AUTO;
    }

    private State state = new State();

    public static FixActiveDictionary get(@NotNull Project project) {
        return project.getService(FixActiveDictionary.class);
    }

    /** The choice in effect: the stored key, or {@link #AUTO} when it names something that is no longer offered. */
    @NotNull
    public static String of(@NotNull Project project) {
        String choice = get(project).state.choice;
        return choice != null && choices(FixDecoderSettings.of(project).customDictionaries).contains(choice) ? choice : AUTO;
    }

    /** Stores the choice only; see {@link #choose} to put it into effect. */
    public void set(@Nullable String choice) {
        state.choice = choice == null ? AUTO : choice;
    }

    /** Stores the choice, loads the dictionaries again and restarts highlighting, so the editor follows at once. */
    public static void choose(@NotNull Project project, @Nullable String choice) {
        get(project).set(choice);
        FixDictionaries.get(project).reload();
        DaemonCodeAnalyzer.getInstance(project).restart();
    }

    /** The keys offered: auto, the versions, then one per custom dictionary path. */
    @NotNull
    public static List<String> choices(@NotNull List<String> customPaths) {
        List<String> keys = new ArrayList<>();
        keys.add(AUTO);
        keys.addAll(DictionarySet.VERSIONS);
        for (String path : customPaths) {
            if (!keys.contains(FILE + path)) keys.add(FILE + path);
        }
        return keys;
    }

    /** What a key is called in the drop-downs: "Auto", the version's name, or the custom file's name. */
    @NotNull
    public static String label(@Nullable String key) {
        if (key == null || AUTO.equals(key)) return "Auto";
        if (key.startsWith(FILE)) {
            String path = key.substring(FILE.length()).replace('\\', '/');
            return path.substring(path.lastIndexOf('/') + 1);
        }
        return FixDecoderConfigurable.versionLabel(key);
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
