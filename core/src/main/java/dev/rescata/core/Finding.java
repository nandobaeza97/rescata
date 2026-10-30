package dev.rescata.core;

/** Un elemento recuperable encontrado durante el escaneo. Inmutable salvo {@link #externalRef}. */
public final class Finding {

    public enum Category {
        /** Archivo en la papelera del sistema (MediaStore, prefijo .trashed-). */
        SYSTEM_TRASH,
        /** Archivo a medio escribir (.pending-), que el sistema borrará al caducar. */
        PENDING,
        /** Archivo dentro de una carpeta con nombre de papelera de una app o fabricante. */
        TRASH_FOLDER,
        /** Miniatura (puede sobrevivir a la foto original). */
        THUMBNAIL,
        /** Archivo multimedia oculto, sin extensión o con extensión engañosa. */
        HIDDEN_MEDIA,
        /** Imagen incrustada extraída por carving de un archivo contenedor. */
        CARVED,
        /** Copia local (cifrada) de los chats de WhatsApp: msgstore*.db.crypt*. */
        WHATSAPP_BACKUP,
        /** Foto, vídeo o audio guardado por WhatsApp (opcional). */
        WHATSAPP_MEDIA
    }

    private final long id;
    private final Category category;
    private final FileType type;
    private final String sourcePath;
    private final long offset;
    private final long length;
    private final long lastModified;
    private final String suggestedName;
    private final long expiresEpochSeconds;
    private final long thumbnailMediaId;
    private final String note;

    /** Referencia externa opcional (p. ej. la URI content:// de MediaStore), la rellena la capa Android. */
    private volatile String externalRef;

    public Finding(long id, Category category, FileType type, String sourcePath, long offset, long length,
                   long lastModified, String suggestedName, long expiresEpochSeconds, long thumbnailMediaId,
                   String note) {
        this.id = id;
        this.category = category;
        this.type = type;
        this.sourcePath = sourcePath;
        this.offset = offset;
        this.length = length;
        this.lastModified = lastModified;
        this.suggestedName = suggestedName;
        this.expiresEpochSeconds = expiresEpochSeconds;
        this.thumbnailMediaId = thumbnailMediaId;
        this.note = note;
    }

    public long getId() { return id; }
    public Category getCategory() { return category; }
    public FileType getType() { return type; }
    /** Ruta del archivo de origen (o del contenedor, si es un elemento extraído por carving). */
    public String getSourcePath() { return sourcePath; }
    /** Desplazamiento dentro del archivo de origen; -1 si el hallazgo es el archivo entero. */
    public long getOffset() { return offset; }
    public long getLength() { return length; }
    public boolean isWholeFile() { return offset < 0; }
    public long getLastModified() { return lastModified; }
    public String getSuggestedName() { return suggestedName; }
    /** Caducidad en segundos desde epoch, o 0 si no aplica. */
    public long getExpiresEpochSeconds() { return expiresEpochSeconds; }
    /** ID de MediaStore deducido del nombre de una miniatura, o -1. */
    public long getThumbnailMediaId() { return thumbnailMediaId; }
    public String getNote() { return note; }
    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String ref) { this.externalRef = ref; }

    @Override
    public String toString() {
        return category + " " + type + " " + sourcePath + (isWholeFile() ? "" : "@" + offset) + " (" + length + " B)";
    }
}
