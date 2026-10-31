package dev.rescata.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Destino que guarda las copias en una carpeta (se acumulan entre exportaciones). */
public class DirectoryDestination implements Destination {

    private final File dir;
    private final String label;
    /** Se lista la carpeta una vez por exportación, no una vez por archivo. */
    private final ContentIndex index;

    public DirectoryDestination(File dir, String label) {
        this.dir = dir;
        this.label = label;
        this.index = new ContentIndex(dir, null);
    }

    public File getDir() { return dir; }

    @Override
    public String label() { return label; }

    @Override
    public String findExisting(Finding finding, String sha256, long size) throws IOException {
        return index.find(sha256, size);
    }

    @Override
    public void written(String location, String sha256, long size) {
        index.add(location, sha256, size);
    }

    @Override
    public String write(Finding finding, String fileName, ContentWriter writer) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No se pudo crear " + dir);
        File out = uniqueFile(dir, fileName);
        OutputStream os = new FileOutputStream(out);
        boolean ok = false;
        try {
            writer.writeTo(os);
            ok = true;
        } finally {
            os.close();
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        }
        afterWrite(finding, out);
        return out.getAbsolutePath();
    }

    /** Conserva la fecha del original para que la galería lo ordene en su día. */
    protected void afterWrite(Finding finding, File out) {
        if (finding.getLastModified() > 0) {
            //noinspection ResultOfMethodCallIgnored
            out.setLastModified(finding.getLastModified());
        }
    }

    /** "foto.jpg" → "foto (1).jpg", "foto (2).jpg"... como hace Android en la galería. */
    public static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String ext = FileType.extensionOf(name);
        String stem = ext.isEmpty() ? name : name.substring(0, name.length() - ext.length() - 1);
        for (int i = 1; ; i++) {
            File g = new File(dir, stem + " (" + i + ")" + (ext.isEmpty() ? "" : "." + ext));
            if (!g.exists()) return g;
        }
    }

    public static String sha256(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            java.security.MessageDigest md = Exporter.newSha256();
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            return Exporter.hex(md.digest());
        } finally {
            in.close();
        }
    }
}
