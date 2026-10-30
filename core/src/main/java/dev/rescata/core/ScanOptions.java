package dev.rescata.core;

import java.util.ArrayList;
import java.util.List;

/** Qué buscar y con qué límites. Campos públicos para usarlo cómodamente desde Kotlin. */
public final class ScanOptions {
    /** Archivos .trashed- / .pending- de la papelera de MediaStore. */
    public boolean systemTrash = true;
    /** Carpetas con nombre de papelera (.Trash, RecycleBin, .recycle, ...). */
    public boolean trashFolders = true;
    /** Miniaturas (.thumbnails) y contenedores de miniaturas antiguos (.thumbdata*). */
    public boolean thumbnails = true;
    /** Multimedia oculto, sin extensión o con extensión que no coincide con su contenido. */
    public boolean hiddenMedia = true;
    /** Carving de imágenes dentro de archivos no multimedia (lento). */
    public boolean carving = false;

    public long carveMaxFileBytes = 1024L * 1024 * 1024;
    public long carveMinFileBytes = 1024;
    public int maxCarvedPerFile = 20000;
    public int maxDepth = 48;
    /** Rutas absolutas que no se recorren (p. ej. la carpeta donde se exporta lo recuperado). */
    public List<String> excludedPaths = new ArrayList<>();
}
