package dev.rescata.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodifica la convención de nombres de la papelera de MediaStore (Android 11+).
 *
 * <p>Cuando una app manda un archivo a la papelera del sistema, MediaProvider lo renombra en su
 * sitio a {@code .trashed-<caducidad>-<nombre original>}; los archivos a medio escribir usan
 * {@code .pending-}. La caducidad está en segundos desde epoch. Por defecto son 30 días para la
 * papelera y 7 para pendientes. El patrón es el mismo que usa AOSP
 * (MediaProvider, util/FileUtils.java, PATTERN_EXPIRES_FILE).
 */
public final class TrashedName {

    public enum State { TRASHED, PENDING }

    static final Pattern PATTERN_EXPIRES_FILE =
            Pattern.compile("(?i)^\\.(pending|trashed)-(\\d+)-([^/]+)$");

    private final State state;
    private final long expiresEpochSeconds;
    private final String originalName;

    private TrashedName(State state, long expiresEpochSeconds, String originalName) {
        this.state = state;
        this.expiresEpochSeconds = expiresEpochSeconds;
        this.originalName = originalName;
    }

    /** Devuelve null si el nombre no sigue la convención. */
    public static TrashedName parse(String fileName) {
        if (fileName == null) return null;
        Matcher m = PATTERN_EXPIRES_FILE.matcher(fileName);
        if (!m.matches()) return null;
        long expires;
        try {
            expires = Long.parseLong(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        State st = m.group(1).equalsIgnoreCase("trashed") ? State.TRASHED : State.PENDING;
        return new TrashedName(st, expires, m.group(3));
    }

    public State getState() { return state; }
    public long getExpiresEpochSeconds() { return expiresEpochSeconds; }
    public String getOriginalName() { return originalName; }
}
