package dev.rescata.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScanSummary {
    public long filesVisited;
    public long dirsVisited;
    public long findings;
    public long durationMs;
    public boolean cancelled;
    /** Directorios que el sistema no dejó listar (p. ej. Android/data en Android 11+). */
    public long unreadableDirs;
    final List<String> unreadableSample = new ArrayList<>();

    public List<String> getUnreadableSample() {
        return Collections.unmodifiableList(unreadableSample);
    }
}
