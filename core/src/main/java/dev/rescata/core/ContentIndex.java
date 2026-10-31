package dev.rescata.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Índice de lo que ya hay en una carpeta de destino, para saber si una copia ya existe sin
 * recorrer la carpeta entera cada vez.
 *
 * La carpeta se lista una sola vez (la primera vez que se consulta). El SHA-256 de un archivo que
 * ya estaba solo se calcula si llega otro con su mismo tamaño, y una sola vez. Lo que se escribe
 * durante la exportación se añade con {@link #add} con su hash ya conocido. Así el coste por archivo
 * ya no crece con el número de archivos que hay en el destino.
 */
public final class ContentIndex {

    /** Qué archivos de la carpeta cuentan como copias (p. ej. sin los ocultos). */
    public interface Filter {
        boolean accept(File f);
    }

    private static final class Known {
        final String location;
        final File file;   // archivo que ya estaba (se comprueba que sigue ahí); null si lo escribimos ahora
        final long size;

        Known(String location, File file, long size) {
            this.location = location;
            this.file = file;
            this.size = size;
        }
    }

    private final File dir;
    private final Filter filter;
    /** Archivos que ya estaban y cuyo hash aún no hizo falta calcular, por tamaño. */
    private Map<Long, List<File>> pendingBySize;
    /** Copias con el hash conocido: SHA-256 → copia. */
    private final Map<String, List<Known>> byHash = new HashMap<>();

    public ContentIndex(File dir, Filter filter) {
        this.dir = dir;
        this.filter = filter;
    }

    private Map<Long, List<File>> pending() {
        if (pendingBySize != null) return pendingBySize;
        pendingBySize = new HashMap<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || (filter != null && !filter.accept(f))) continue;
                long size = f.length();
                List<File> l = pendingBySize.get(size);
                if (l == null) {
                    l = new ArrayList<>(1);
                    pendingBySize.put(size, l);
                }
                l.add(f);
            }
        }
        return pendingBySize;
    }

    /** Ubicación de una copia con este contenido exacto, o null si no hay. */
    public synchronized String find(String sha256, long size) throws IOException {
        String hit = known(sha256, size);
        if (hit != null) return hit;
        // Se calculan (una sola vez) los hashes de los archivos que ya estaban con ese mismo tamaño
        List<File> same = pending().get(size);
        if (same == null) return null;
        while (!same.isEmpty()) {
            File f = same.get(same.size() - 1); // desde el final: quitarlo no desplaza la lista
            if (!f.isFile() || f.length() != size) {
                same.remove(same.size() - 1); // se borró o cambió mientras tanto
                continue;
            }
            String h = DirectoryDestination.sha256(f); // si falla, se queda pendiente (como antes, da error)
            same.remove(same.size() - 1);
            remember(h, new Known(f.getAbsolutePath(), f, size));
            if (h.equals(sha256)) return f.getAbsolutePath();
        }
        return null;
    }

    private String known(String sha256, long size) {
        List<Known> l = byHash.get(sha256);
        if (l == null) return null;
        for (Iterator<Known> it = l.iterator(); it.hasNext(); ) {
            Known k = it.next();
            if (k.size != size) continue;
            if (k.file != null && (!k.file.isFile() || k.file.length() != size)) {
                it.remove(); // se borró o cambió mientras tanto
                continue;
            }
            return k.location;
        }
        return null;
    }

    private void remember(String sha256, Known k) {
        List<Known> l = byHash.get(sha256);
        if (l == null) {
            l = new ArrayList<>(1);
            byHash.put(sha256, l);
        }
        l.add(k);
    }

    /** Registra una copia recién escrita (su contenido ya se conoce). */
    public synchronized void add(String location, String sha256, long size) {
        pending(); // la carpeta se lista antes, para no contar dos veces lo que acabamos de escribir
        remember(sha256, new Known(location, null, size));
    }
}
