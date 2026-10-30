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
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Copia los hallazgos a una carpeta de salida y escribe un informe CSV con el SHA-256 de cada copia.
 * El origen se abre siempre en modo solo lectura.
 */
public final class Exporter {

    public interface Listener {
        void onProgress(int done, int total, Finding current);
    }

    public static final class Result {
        public int exported;
        public int failed;
        public File outputDir;
        public File report;
    }

    public static final String REPORT_NAME = "informe.csv";

    public Result export(List<Finding> findings, File outDir, Listener listener, AtomicBoolean cancel)
            throws IOException {
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("No se pudo crear la carpeta de salida: " + outDir);
        }
        Result res = new Result();
        res.outputDir = outDir;

        StringBuilder csv = new StringBuilder();
        csv.append("id;categoria;tipo;origen;offset;bytes;sha256;nombre_original;caduca_utc;modificado_utc;archivo_exportado;nota;error\r\n");

        int done = 0;
        for (Finding f : findings) {
            if (cancel != null && cancel.get()) break;
            if (listener != null) listener.onProgress(done, findings.size(), f);
            String outName = "";
            String sha = "";
            String error = "";
            long bytes = 0;
            try {
                File out = uniqueFile(outDir, targetName(f));
                outName = out.getName();
                long[] copied = new long[1];
                sha = copy(f, out, copied);
                bytes = copied[0];
                if (f.isWholeFile() && f.getLastModified() > 0) {
                    //noinspection ResultOfMethodCallIgnored
                    out.setLastModified(f.getLastModified());
                }
                res.exported++;
            } catch (IOException | SecurityException e) {
                res.failed++;
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            csv.append(f.getId()).append(';')
               .append(f.getCategory()).append(';')
               .append(f.getType()).append(';')
               .append(cell(f.getSourcePath())).append(';')
               .append(f.isWholeFile() ? "" : String.valueOf(f.getOffset())).append(';')
               .append(bytes).append(';')
               .append(sha).append(';')
               .append(cell(f.getSuggestedName())).append(';')
               .append(f.getExpiresEpochSeconds() > 0 ? iso(f.getExpiresEpochSeconds() * 1000L) : "").append(';')
               .append(f.getLastModified() > 0 ? iso(f.getLastModified()) : "").append(';')
               .append(cell(outName)).append(';')
               .append(cell(f.getNote())).append(';')
               .append(cell(error)).append("\r\n");
            done++;
        }
        if (listener != null) listener.onProgress(done, findings.size(), null);

        File report = uniqueFile(outDir, REPORT_NAME);
        OutputStream os = new FileOutputStream(report);
        try {
            os.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // BOM: Excel abre bien los acentos
            Writer w = new OutputStreamWriter(os, Charset.forName("UTF-8"));
            w.write(csv.toString());
            w.flush();
        } finally {
            os.close();
        }
        res.report = report;
        return res;
    }

    /** Copia el hallazgo (archivo entero o tramo) y devuelve el SHA-256 en hexadecimal. */
    static String copy(Finding f, File out, long[] copiedOut) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 no disponible", e);
        }
        RandomAccessFile in = new RandomAccessFile(f.getSourcePath(), "r");
        OutputStream os = null;
        long copied = 0;
        try {
            os = new FileOutputStream(out);
            byte[] buf = new byte[256 * 1024];
            if (f.isWholeFile()) {
                int r;
                while ((r = in.read(buf)) > 0) {
                    os.write(buf, 0, r);
                    md.update(buf, 0, r);
                    copied += r;
                }
            } else {
                in.seek(f.getOffset());
                long remaining = f.getLength();
                while (remaining > 0) {
                    int r = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (r < 0) break;
                    os.write(buf, 0, r);
                    md.update(buf, 0, r);
                    remaining -= r;
                    copied += r;
                }
                if (remaining > 0) throw new IOException("Lectura incompleta: el origen ha cambiado");
            }
        } finally {
            in.close();
            if (os != null) os.close();
        }
        copiedOut[0] = copied;
        return hex(md.digest());
    }

    static String targetName(Finding f) {
        String base = f.getSuggestedName();
        if (base == null || base.trim().isEmpty()) base = "recuperado";
        base = sanitize(base);
        FileType t = f.getType();
        if (t != FileType.UNKNOWN && !t.matchesExtension(FileType.extensionOf(base))) {
            base = base + "." + t.getDefaultExtension();
        }
        return String.format(Locale.ROOT, "%05d_%s", f.getId(), base);
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

    static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String ext = FileType.extensionOf(name);
        String stem = ext.isEmpty() ? name : name.substring(0, name.length() - ext.length() - 1);
        for (int i = 1; ; i++) {
            File g = new File(dir, stem + "_" + i + (ext.isEmpty() ? "" : "." + ext));
            if (!g.exists()) return g;
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

    static String hex(byte[] b) {
        char[] d = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = d[(b[i] >> 4) & 0xF];
            out[2 * i + 1] = d[b[i] & 0xF];
        }
        return new String(out);
    }
}
