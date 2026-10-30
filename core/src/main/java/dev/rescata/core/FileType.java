package dev.rescata.core;

import java.util.Locale;

/** Tipos de archivo que el motor sabe reconocer por su firma (magic bytes). */
public enum FileType {
    JPEG("jpg", "image/jpeg", Kind.IMAGE, "jpg", "jpeg", "jpe", "jfif"),
    PNG("png", "image/png", Kind.IMAGE, "png"),
    GIF("gif", "image/gif", Kind.IMAGE, "gif"),
    WEBP("webp", "image/webp", Kind.IMAGE, "webp"),
    HEIF("heic", "image/heif", Kind.IMAGE, "heic", "heif", "hif"),
    AVIF("avif", "image/avif", Kind.IMAGE, "avif"),
    MP4("mp4", "video/mp4", Kind.VIDEO, "mp4", "m4v", "mov", "3gp", "3g2", "3gpp"),
    MKV("mkv", "video/x-matroska", Kind.VIDEO, "mkv", "webm", "mka"),
    M4A("m4a", "audio/mp4", Kind.AUDIO, "m4a", "aac", "mp4"),
    MP3("mp3", "audio/mpeg", Kind.AUDIO, "mp3"),
    OGG("ogg", "audio/ogg", Kind.AUDIO, "ogg", "oga", "opus"),
    AMR("amr", "audio/amr", Kind.AUDIO, "amr"),
    WAV("wav", "audio/wav", Kind.AUDIO, "wav"),
    PDF("pdf", "application/pdf", Kind.DOCUMENT, "pdf"),
    ZIP("zip", "application/zip", Kind.ARCHIVE, "zip", "docx", "xlsx", "pptx", "odt", "ods", "apk", "jar", "epub"),
    SQLITE("db", "application/vnd.sqlite3", Kind.DATA, "db", "sqlite", "sqlite3"),
    UNKNOWN("bin", "application/octet-stream", Kind.OTHER);

    public enum Kind { IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, DATA, OTHER }

    private final String defaultExtension;
    private final String mimeType;
    private final Kind kind;
    private final String[] extensions;

    FileType(String defaultExtension, String mimeType, Kind kind, String... extensions) {
        this.defaultExtension = defaultExtension;
        this.mimeType = mimeType;
        this.kind = kind;
        this.extensions = extensions;
    }

    public String getDefaultExtension() { return defaultExtension; }
    public String getMimeType() { return mimeType; }
    public Kind getKind() { return kind; }

    public boolean isMedia() {
        return kind == Kind.IMAGE || kind == Kind.VIDEO || kind == Kind.AUDIO;
    }

    public boolean isImage() { return kind == Kind.IMAGE; }

    /** ¿La extensión (sin punto, en cualquier capitalización) es una de las habituales de este tipo? */
    public boolean matchesExtension(String extension) {
        if (extension == null || extension.isEmpty()) return false;
        String e = extension.toLowerCase(Locale.ROOT);
        for (String x : extensions) {
            if (x.equals(e)) return true;
        }
        return false;
    }

    /** Extensión de un nombre de archivo, en minúsculas y sin punto ("" si no tiene). */
    public static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** ¿Es una extensión conocida de cualquiera de los tipos? */
    public static boolean isKnownExtension(String extension) {
        if (extension == null || extension.isEmpty()) return false;
        for (FileType t : values()) {
            if (t.matchesExtension(extension)) return true;
        }
        return false;
    }
}
