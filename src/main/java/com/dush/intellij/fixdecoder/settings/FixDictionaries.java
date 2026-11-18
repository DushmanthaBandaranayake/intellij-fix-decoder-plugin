package com.dush.intellij.fixdecoder.settings;

import com.dush.intellij.fixdecoder.FixDecoderIcons;
import com.dush.intellij.fixdecoder.core.dictionary.DictionaryParser;
import com.dush.intellij.fixdecoder.core.dictionary.DictionarySet;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ModificationTracker;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The project's dictionaries: the bundled ones with the custom dictionaries from settings laid over them, or what
 * {@link FixActiveDictionary} asks for instead.
 * Loaded on first use; {@link #reload()} after the settings change. As a modification tracker it invalidates
 * everything computed from the dictionaries.
 */
public final class FixDictionaries implements ModificationTracker {
    public static final String NOTIFICATION_GROUP = "FIX Decoder";

    private final Project project;
    private final AtomicLong modificationCount = new AtomicLong();
    private volatile DictionarySet set;
    public FixDictionaries(Project project) {
        this.project = project;
    }

    public static FixDictionaries get(@NotNull Project project) {
        return project.getService(FixDictionaries.class);
    }

    public DictionarySet set() {
        DictionarySet s = set;
        if (s == null) {
            synchronized (this) {
                s = set;
                if (s == null) set = s = load();
            }
        }
        return s;
    }

    /**
     * The version for messages without a BeginString (8), such as fragments: FIX 4.4. Choosing a version or a custom
     * dictionary as the dictionary in use ({@link FixActiveDictionary}) overrides it.
     */
    public String defaultVersion() {
        return DictionarySet.FIX44;
    }

    public void reload() {
        set = null;
        modificationCount.incrementAndGet();
    }

    @Override
    public long getModificationCount() {
        return modificationCount.get();
    }

    private DictionarySet load() {
        String choice = FixActiveDictionary.of(project);
        // one standard version for every message, without custom dictionaries
        if (DictionarySet.VERSIONS.contains(choice)) return DictionarySet.bundled().forcing(choice);
        if (choice.startsWith(FixActiveDictionary.FILE)) return loadOnly(choice.substring(FixActiveDictionary.FILE.length()));
        // auto: every custom dictionary, the version from each message
        DictionarySet s = DictionarySet.bundled();
        List<String> errors = new ArrayList<>();
        for (String path : FixDecoderSettings.of(project).customDictionaries) {
            try {
                s = withCustom(s, resolve(project, path));
            } catch (IOException | DictionaryParser.DictionaryException e) {
                errors.add(path + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            Notifications.Bus.notify(new Notification(NOTIFICATION_GROUP, "FIX Decoder: custom dictionary not loaded",
                    String.join("<br>", errors), NotificationType.WARNING).setIcon(FixDecoderIcons.DECODER), project);
        }
        return s;
    }

    /**
     * The standard dictionaries with only this custom file laid over them. A file for one version is used for every
     * message; a file for all versions leaves the version to the message.
     */
    private DictionarySet loadOnly(String path) {
        try {
            Path file = resolve(project, path);
            byte[] xml = Files.readAllBytes(file);
            DictionarySet s = DictionarySet.bundled().withCustom(file.getFileName().toString(), xml);
            String version = DictionarySet.targetVersion(xml);
            return version == null ? s : s.forcing(version);
        } catch (IOException | DictionaryParser.DictionaryException | RuntimeException e) {
            Notifications.Bus.notify(new Notification(NOTIFICATION_GROUP, "FIX Decoder: custom dictionary not loaded",
                    path + ": " + e.getMessage() + "<br>The standard dictionaries are used instead.", NotificationType.WARNING)
                    .setIcon(FixDecoderIcons.DECODER), project);
            return DictionarySet.bundled();
        }
    }

    /** Lays one custom dictionary file over {@code s}. */
    public static DictionarySet withCustom(DictionarySet s, Path file) throws IOException, DictionaryParser.DictionaryException {
        return s.withCustom(file.getFileName().toString(), Files.readAllBytes(file));
    }

    /** A settings path as a file: absolute, or relative to the project folder. */
    public static Path resolve(Project project, String path) {
        Path p = Paths.get(path);
        if (!p.isAbsolute() && project.getBasePath() != null) p = Paths.get(project.getBasePath()).resolve(p);
        return p;
    }
}
