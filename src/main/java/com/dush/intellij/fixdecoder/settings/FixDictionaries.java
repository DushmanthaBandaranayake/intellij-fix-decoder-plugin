package com.dush.intellij.fixdecoder.settings;

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
 * The project's dictionaries: the bundled ones with the custom dictionaries from settings laid over them.
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

    public String defaultVersion() {
        return FixDecoderSettings.of(project).defaultVersion;
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
                    String.join("<br>", errors), NotificationType.WARNING), project);
        }
        return s;
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
