package dev.rescata.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Copia los hallazgos a su destino (carpeta o álbum de la galería) y escribe un informe CSV con
 * el SHA-256 de cada copia. El origen se abre siempre en modo solo lectura. Si el destino ya tiene
 * una copia idéntica, no se duplica.
 */
public final class Exporter {

    public interface Listener {
        void onProgress(int done, int total, Finding current);
    }

    /** Decide a qué destino va cada hallazgo. */
    public interface Router {
        Destination destinationFor(Finding finding);
    }

    public static final class Result {
        public int exported;
        public int alreadyPresent;
        public int failed;
        public File report;
        /** Copias nuevas por destino (etiqueta → número), en orden de aparición. */
        public final Map<String, Integer> exportedByDestination = new LinkedHashMap<>();
    }

    public static final String STATUS_EXPORTED = "exportado";
    public static final String STATUS_ALREADY = "ya existía";
    public static final String STATUS_ERROR = "error";

    /** Atajo: todo a una carpeta y el informe dentro de ella como informe.csv. */
    public Result export(List<Finding> findings, File outDir, Listener listener, AtomicBoolean cancel)
            throws IOException {
        final DirectoryDestination dest = new DirectoryDestination(outDir, outDir.getName());
        return export(findings, new Router() {
            @Override public Destination destinationFor(Finding f) { return dest; }
        }, new File(outDir, "informe.csv"), listener, cancel);
    }

    public Result export(List<Finding> findings, Router router, File reportFile, Listener listener,
                         AtomicBoolean cancel) throws IOException {
        Result res = new Result();
        StringBuilder csv = new StringBuilder();
        csv.append("id;categoria;tipo;origen;offset;bytes;sha256;nombre_original;caduca_utc;modificado_utc;")
           .append("estado;destino;ubicacion;nota;error\r\n");

        int done = 0;
        for (final Finding f : findings) {
            if (cancel != null && cancel.get()) break;
            if (listener != null) listener.onProgress(done, findings.size(), f);
            String status;
            String destLabel = "";
            String location = "";
            String sha = "";
            String error = "";
            long size = 0;
            try {
                long[] sizeOut = new long[1];
                sha = hashSource(f, sizeOut);
                size = sizeOut[0];
                Destination d = router.destinationFor(f);
                destLabel = d.label();
                String existing = d.findExisting(f, sha, size);
                if (existing != null) {
                    status = STATUS_ALREADY;
                    location = existing;
                    res.alreadyPresent++;
                } else {
                    final String[] copySha = new String[1];
                    final long[] copied = new long[1];
                    location = d.write(f, targetName(f), new Destination.ContentWriter() {
                        @Override public void writeTo(OutputStream out) throws IOException {
                            copySha[0] = copy(f, out, copied);
                        }
                    });
                    if (copied[0] != size || !sha.equals(copySha[0])) {
                        throw new IOException("El origen cambió durante la copia");
                    }
                    status = STATUS_EXPORTED;
                    res.exported++;
                    Integer n = res.exportedByDestination.get(destLabel);
                    res.exportedByDestination.put(destLabel, n == null ? 1 : n + 1);
                }
            } catch (IOException | RuntimeException e) {
                status = STATUS_ERROR;
                res.failed++;
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            csv.append(f.getId()).append(';')
               .append(f.getCategory()).append(';')
               .append(f.getType()).append(';')
               .append(cell(f.getSourcePath())).append(';')
               .append(f.isWholeFile() ? "" : String.valueOf(f.getOffset())).append(';')
               .append(size).append(';')
               .append(sha).append(';')
               .append(cell(f.getSuggestedName())).append(';')
               .append(f.getExpiresEpochSeconds() > 0 ? iso(f.getExpiresEpochSeconds() * 1000L) : "").append(';')
               .append(f.getLastModified() > 0 ? iso(f.getLastModified()) : "").append(';')
               .append(status).append(';')
               .append(cell(destLabel)).append(';')
               .append(cell(location)).append(';')
               .append(cell(f.getNote())).append(';')
               .append(cell(error)).append("\r\n");
            done++;
        }
        if (listener != null) listener.onProgress(done, findings.size(), null);

        if (reportFile != null) {
            File parent = reportFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("No se pudo crear " + parent);
            }
            OutputStream os = new FileOutputStream(reportFile);
            try {
                os.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // BOM: Excel abre bien los acentos
                Writer w = new OutputStreamWriter(os, Charset.forName("UTF-8"));
                w.write(csv.toString());
                w.flush();
            } finally {
                os.close();
            }
            res.report = reportFile;
        }
        return res;
    }

    /** SHA-256 del contenido del hallazgo (archivo entero o tramo), sin copiarlo. */
    static String hashSource(Finding f, long[] sizeOut) throws IOException {
        return copy(f, null, sizeOut);
    }

    /** Copia el hallazgo a {@code out} (o solo lo lee si es null) y devuelve su SHA-256. */
    static String copy(Finding f, OutputStream out, long[] copiedOut) throws IOException {
        MessageDigest md = newSha256();
        RandomAccessFile in = new RandomAccessFile(f.getSourcePath(), "r");
        long copied = 0;
        try {
            byte[] buf = new byte[256 * 1024];
            if (f.isWholeFile()) {
                int r;
                while ((r = in.read(buf)) > 0) {
                    if (out != null) out.write(buf, 0, r);
                    md.update(buf, 0, r);
                    copied += r;
                }
            } else {
                in.seek(f.getOffset());
                long remaining = f.getLength();
                while (remaining > 0) {
                    int r = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (r < 0) break;
                    if (out != null) out.write(buf, 0, r);
                    md.update(buf, 0, r);
                    remaining -= r;
                    copied += r;
                }
                if (remaining > 0) throw new IOException("Lectura incompleta: el origen ha cambiado");
            }
        } finally {
            in.close();
        }
        copiedOut[0] = copied;
        return hex(md.digest());
    }

    /** Nombre de la copia: el original (saneado) con la extensión que corresponde a su contenido. */
    public static String targetName(Finding f) {
        String base = f.getSuggestedName();
        if (base == null || base.trim().isEmpty()) base = "recuperado";
        base = sanitize(base);
        FileType t = f.getType();
        if (t != FileType.UNKNOWN && !t.matchesExtension(FileType.extensionOf(base))) {
            base = base + "." + t.getDefaultExtension();
        }
        return base;
    }

    static String sanitize(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_");
        if (s.length() > 120) {
            String ext = FileType.extensionOf(s);
            int keep = 120 - (ext.isEmpty() ? 0 : ext.length() + 1);
            s = s.substring(0, keep) + (ext.isEmpty() ? "" : "." + ext);
        }
        return s;
    }

    public static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 no disponible", e);
        }
    }

    private static String cell(String s) {
        if (s == null) return "";
        if (s.indexOf(';') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static String iso(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    public static String hex(byte[] b) {
        char[] d = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = d[(b[i] >> 4) & 0xF];
            out[2 * i + 1] = d[b[i] & 0xF];
        }
        return new String(out);
    }
}
