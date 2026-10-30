package dev.rescata.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recorre el almacenamiento accesible (sin root) y clasifica lo que puede ser recuperable.
 * Solo lee: nunca modifica, mueve ni borra nada del origen.
 */
public final class ScanEngine {

    /** En Android 10+ MediaProvider guarda las miniaturas como {@code <colección>/.thumbnails/<id>.jpg}. */
    private static final Pattern THUMB_ID =
            Pattern.compile("^(\\d{1,18})\\.(jpe?g|png|webp)$", Pattern.CASE_INSENSITIVE);

    /** Carpetas de WhatsApp y WhatsApp Business (antigua /WhatsApp o Android/media/com.whatsapp/WhatsApp). */
    private static final Pattern WA_MEDIA =
            Pattern.compile("(?i)/(WhatsApp|WhatsApp Business)/Media/");
    private static final Pattern WA_DATABASES =
            Pattern.compile("(?i)/(WhatsApp|WhatsApp Business)/(Databases|Backups)/");
    /** msgstore.db.crypt15 (la más reciente) o msgstore-AAAA-MM-DD.1.db.crypt15 (diarias). */
    static final Pattern WA_BACKUP_NAME =
            Pattern.compile("(?i)^msgstore(?:-(\\d{4})-(\\d{2})-(\\d{2})(?:\\.\\d+)?)?\\.db\\.crypt\\d+$");

    private final ScanOptions opt;
    private final Carver carver;
    private final AtomicLong ids = new AtomicLong();

    public ScanEngine(ScanOptions options) {
        this.opt = options;
        this.carver = new Carver(options.maxCarvedPerFile);
    }

    public ScanSummary scan(List<File> roots, ScanListener listener, AtomicBoolean cancel) {
        Ctx c = new Ctx(listener, cancel == null ? new AtomicBoolean(false) : cancel);
        long t0 = System.currentTimeMillis();
        for (String p : opt.excludedPaths) c.excluded.add(canonical(new File(p)));
        for (File root : roots) {
            if (c.cancel.get()) break;
            walk(c, root, 0, false, false, true);
        }
        c.sum.cancelled = c.cancel.get();
        c.sum.durationMs = System.currentTimeMillis() - t0;
        return c.sum;
    }

    private static final class Ctx {
        final ScanListener l;
        final AtomicBoolean cancel;
        final ScanSummary sum = new ScanSummary();
        final Set<String> visited = new HashSet<>();
        final Set<String> excluded = new HashSet<>();

        Ctx(ScanListener l, AtomicBoolean cancel) {
            this.l = l;
            this.cancel = cancel;
        }
    }

    private void walk(Ctx c, File dir, int depth, boolean hiddenCtx, boolean trashCtx, boolean isRoot) {
        if (c.cancel.get()) return;
        String canon = canonical(dir);
        if (c.excluded.contains(canon) || !c.visited.add(canon)) return; // evita bucles por enlaces

        File[] children;
        try {
            children = dir.listFiles();
        } catch (SecurityException e) {
            children = null;
        }
        c.sum.dirsVisited++;
        if (children == null) {
            c.sum.unreadableDirs++;
            if (c.sum.unreadableSample.size() < 50) c.sum.unreadableSample.add(dir.getAbsolutePath());
            return;
        }
        Arrays.sort(children, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });

        boolean hasNomedia = false;
        for (File ch : children) {
            if (ch.getName().equals(".nomedia")) { hasNomedia = true; break; }
        }
        String dirName = dir.getName();
        boolean hidden = hiddenCtx || hasNomedia || (!isRoot && dirName.startsWith("."));
        boolean trash = trashCtx || (!isRoot && isTrashDirName(dirName));
        c.l.onProgress(c.sum.filesVisited, c.sum.dirsVisited, dir.getAbsolutePath());

        for (File ch : children) {
            if (c.cancel.get()) return;
            if (ch.isDirectory()) {
                if (depth + 1 <= opt.maxDepth) walk(c, ch, depth + 1, hidden, trash, false);
            } else {
                c.sum.filesVisited++;
                try {
                    visitFile(c, ch, dir, hidden, trash);
                } catch (SecurityException ignored) {
                    // archivo no legible: se sigue con el resto
                }
                if ((c.sum.filesVisited & 0xFF) == 0) {
                    c.l.onProgress(c.sum.filesVisited, c.sum.dirsVisited, ch.getAbsolutePath());
                }
            }
        }
    }

    private void visitFile(Ctx c, File f, File parent, boolean hidden, boolean trash) {
        String name = f.getName();
        if (name.equals(".nomedia")) return;
        long size = f.length();
        if (size <= 0) return;

        String ext = FileType.extensionOf(name);
        String lower = name.toLowerCase(Locale.ROOT);
        TrashedName tn = TrashedName.parse(name);
        boolean thumbDir = parent.getName().equalsIgnoreCase(".thumbnails");
        boolean thumbContainer = lower.startsWith(".thumbdata") || lower.startsWith("thumbdata");
        boolean dotFile = name.startsWith(".");
        String unixPath = f.getAbsolutePath().replace('\\', '/');
        boolean waMedia = opt.whatsappMedia && WA_MEDIA.matcher(unixPath).find();
        Matcher waBackup = WA_BACKUP_NAME.matcher(name);
        boolean isWaBackup = opt.whatsappBackups && WA_DATABASES.matcher(unixPath).find() && waBackup.matches();

        if (isWaBackup) {
            String day = waBackup.group(1) == null ? null
                    : waBackup.group(3) + "/" + waBackup.group(2) + "/" + waBackup.group(1);
            emit(c, Finding.Category.WHATSAPP_BACKUP, FileType.UNKNOWN, f, -1, size, name, 0, -1,
                    (day == null ? "Copia más reciente de los chats de WhatsApp" : "Copia de los chats de WhatsApp del " + day)
                            + " · cifrada: solo WhatsApp puede restaurarla");
            return;
        }

        boolean needHead = tn != null || trash || thumbDir || thumbContainer || hidden || dotFile || waMedia
                || !FileType.isKnownExtension(ext) || opt.carving;
        if (!needHead) return; // archivo visible con extensión normal: está vivo, no interesa
        FileType type = Signatures.identify(f);

        if (tn != null) {
            if (!opt.systemTrash) return;
            boolean trashed = tn.getState() == TrashedName.State.TRASHED;
            emit(c, trashed ? Finding.Category.SYSTEM_TRASH : Finding.Category.PENDING, type, f, -1, size,
                    tn.getOriginalName(), tn.getExpiresEpochSeconds(), -1,
                    trashed ? "En la papelera del sistema; se borrará al caducar"
                            : "Archivo pendiente (escritura no finalizada)");
            return;
        }
        if (waMedia && type.isMedia()) {
            emit(c, Finding.Category.WHATSAPP_MEDIA, type, f, -1, size, stripLeadingDots(name), 0, -1,
                    "Guardado por WhatsApp: " + parent.getName());
            return;
        }
        if (thumbContainer && opt.thumbnails) {
            carveInto(c, f, Finding.Category.THUMBNAIL, "Extraída del contenedor de miniaturas " + name);
            return;
        }
        if (trash && opt.trashFolders) {
            emit(c, Finding.Category.TRASH_FOLDER, type, f, -1, size, stripLeadingDots(name), 0, -1,
                    "Dentro de una carpeta de papelera: " + parent.getAbsolutePath());
            return;
        }
        if (thumbDir && opt.thumbnails && type.isImage()) {
            long mediaId = -1;
            Matcher m = THUMB_ID.matcher(name);
            if (m.matches()) {
                try { mediaId = Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) { }
            }
            emit(c, Finding.Category.THUMBNAIL, type, f, -1, size, "miniatura_" + name, 0, mediaId,
                    "Miniatura generada por el sistema o la galería");
            return;
        }
        if (opt.hiddenMedia && type.isMedia()) {
            List<String> why = new ArrayList<>();
            if (ext.isEmpty()) why.add("sin extensión");
            else if (!type.matchesExtension(ext)) why.add("la extensión ." + ext + " no coincide con el contenido");
            if (dotFile) why.add("archivo oculto");
            if (hidden) why.add("en carpeta oculta o con .nomedia");
            if (!why.isEmpty()) {
                emit(c, Finding.Category.HIDDEN_MEDIA, type, f, -1, size, stripLeadingDots(name), 0, -1,
                        capitalize(join(why)));
                return;
            }
        }
        if (opt.carving && (type == FileType.UNKNOWN || type == FileType.SQLITE)
                && size >= opt.carveMinFileBytes && size <= opt.carveMaxFileBytes) {
            carveInto(c, f, Finding.Category.CARVED, "Extraída por carving de " + name);
        }
    }

    private void carveInto(final Ctx c, final File f, final Finding.Category cat, final String note) {
        final String base = stripExtension(stripLeadingDots(f.getName()));
        final long mtime = f.lastModified();
        try {
            carver.carve(f, new Carver.Sink() {
                @Override
                public boolean onCarved(long offset, long length, FileType type) {
                    Finding fd = new Finding(ids.incrementAndGet(), cat, type, f.getAbsolutePath(), offset, length,
                            mtime, base + "_0x" + Long.toHexString(offset), 0, -1, note);
                    c.sum.findings++;
                    c.l.onFinding(fd);
                    return !c.cancel.get();
                }
            }, c.cancel);
        } catch (IOException | SecurityException ignored) {
            // no legible: se ignora
        }
    }

    private void emit(Ctx c, Finding.Category cat, FileType type, File f, long offset, long length,
                      String suggested, long expires, long mediaId, String note) {
        Finding fd = new Finding(ids.incrementAndGet(), cat, type, f.getAbsolutePath(), offset, length,
                f.lastModified(), suggested, expires, mediaId, note);
        c.sum.findings++;
        c.l.onFinding(fd);
    }

    /** Nombres típicos de papelera de apps y fabricantes: .Trash, .trash-storage, RecycleBin, .recycle, Recently deleted... */
    static boolean isTrashDirName(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return s.startsWith("trash") || s.startsWith("recycle") || s.equals("deleted")
                || s.startsWith("recentlydeleted") || s.equals("papelera");
    }

    static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    static String stripLeadingDots(String n) {
        int i = 0;
        while (i < n.length() && n.charAt(i) == '.') i++;
        String r = n.substring(i);
        return r.isEmpty() ? "archivo" : r;
    }

    static String stripExtension(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p);
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
