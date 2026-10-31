package dev.rescata.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Exportaciones grandes: el tiempo por archivo no debe crecer con lo que ya hay en el destino. */
public class ExportScaleTest {

    private static final int N = 20000;

    /** N archivos distintos y todos del mismo tamaño (el peor caso para buscar duplicados). */
    private static List<Finding> manyFiles(File dir, int n) throws Exception {
        List<Finding> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte[] b = TestData.junk(700, 1000 + i);
            File f = TestData.write(new File(dir, String.format("f%05d.bin", i)), b);
            out.add(new Finding(i + 1, Finding.Category.HIDDEN_MEDIA, FileType.UNKNOWN, f.getPath(), -1,
                    f.length(), 0, f.getName(), 0, -1, ""));
        }
        return out;
    }

    @Test
    public void exportaMilesSinRalentizarse() throws Exception {
        File base = TestData.tempDir();
        List<Finding> all = manyFiles(new File(base, "origen"), N);
        File out = new File(base, "destino");
        final long[] at = new long[N + 1];
        Exporter.Listener l = new Exporter.Listener() {
            @Override public void onProgress(int done, int total, Finding current) { at[done] = System.nanoTime(); }
        };
        Exporter.Result first = new Exporter().export(all, out, l, null);
        assertEquals(N, first.exported);
        assertEquals(0, first.failed);
        assertEquals(N + 1, out.listFiles().length); // + informe.csv

        // Los últimos 2000 no deben tardar mucho más que los primeros 2000 (antes: unas 5 veces más)
        long early = at[4000] - at[2000];
        long late = at[N] - at[N - 2000];
        System.out.println("ExportScaleTest: primeros 2000 = " + early / 1_000_000 + " ms, últimos 2000 = "
                + late / 1_000_000 + " ms, total = " + (at[N] - at[0]) / 1_000_000 + " ms");
        assertTrue("Se ralentiza: " + early / 1_000_000 + " ms → " + late / 1_000_000 + " ms",
                late < 3 * early + 300_000_000L);

        // Volver a exportar todo: nada nuevo, y sin ralentizarse tampoco (cada copia previa se lee una vez)
        Exporter.Result second = new Exporter().export(all, out, l, null);
        assertEquals(0, second.exported);
        assertEquals(N, second.alreadyPresent);
        assertEquals(N + 1, out.listFiles().length);
        long early2 = at[4000] - at[2000];
        long late2 = at[N] - at[N - 2000];
        System.out.println("ExportScaleTest (reexportar): primeros 2000 = " + early2 / 1_000_000 + " ms, últimos 2000 = "
                + late2 / 1_000_000 + " ms");
        assertTrue("Reexportar se ralentiza", late2 < 3 * early2 + 300_000_000L);
    }

    @Test
    public void dosIgualesEnLaMismaExportacionSoloUnaCopia() throws Exception {
        File base = TestData.tempDir();
        byte[] same = TestData.jpeg(20, 20, 9);
        File a = TestData.write(new File(base, "a/x.jpg"), same);
        File b = TestData.write(new File(base, "b/y.jpg"), same);
        List<Finding> two = new ArrayList<>();
        two.add(new Finding(1, Finding.Category.TRASH_FOLDER, FileType.JPEG, a.getPath(), -1, a.length(), 0, "x.jpg", 0, -1, ""));
        two.add(new Finding(2, Finding.Category.TRASH_FOLDER, FileType.JPEG, b.getPath(), -1, b.length(), 0, "y.jpg", 0, -1, ""));
        File out = new File(base, "salida");
        Exporter.Result r = new Exporter().export(two, out, null, null);
        assertEquals(1, r.exported);
        assertEquals(1, r.alreadyPresent);
        assertTrue(new File(out, "x.jpg").isFile());
        assertTrue(!new File(out, "y.jpg").exists());
    }

    @Test
    public void siSeBorraLaCopiaPreviaSeVuelveACopiar() throws Exception {
        File base = TestData.tempDir();
        byte[] c1 = TestData.jpeg(20, 20, 11);
        byte[] c2 = TestData.jpeg(20, 20, 12);
        File a = TestData.write(new File(base, "o/a.jpg"), c1);
        File b = TestData.write(new File(base, "o/b.jpg"), c2);
        File out = new File(base, "album");
        File previous = TestData.write(new File(out, "a.jpg"), c1); // copia de una exportación anterior

        DirectoryDestination d = new DirectoryDestination(out, "album");
        // La primera consulta lista la carpeta (la copia previa está)
        assertEquals(null, d.findExisting(null, DirectoryDestination.sha256(b), b.length()));
        assertTrue(previous.delete()); // el usuario la borra a mitad de la exportación
        assertEquals(null, d.findExisting(null, DirectoryDestination.sha256(a), a.length()));

        List<Finding> one = new ArrayList<>();
        one.add(new Finding(1, Finding.Category.TRASH_FOLDER, FileType.JPEG, a.getPath(), -1, a.length(), 0, "a.jpg", 0, -1, ""));
        final DirectoryDestination dest = d;
        Exporter.Result r = new Exporter().export(one, new Exporter.Router() {
            @Override public Destination destinationFor(Finding f) { return dest; }
        }, null, null, null);
        assertEquals(1, r.exported);
        assertArrayEquals(c1, readAll(new File(out, "a.jpg")));
    }

    @Test
    public void indiceIgnoraLoQueFiltraYDetectaCopiasPrevias() throws Exception {
        File base = TestData.tempDir();
        byte[] c = TestData.jpeg(20, 20, 21);
        File dir = new File(base, "album");
        File hidden = TestData.write(new File(dir, ".pending-123-foto.jpg"), c);
        ContentIndex onlyVisible = new ContentIndex(dir, new ContentIndex.Filter() {
            @Override public boolean accept(File f) { return !f.getName().startsWith("."); }
        });
        String sha = DirectoryDestination.sha256(hidden);
        assertEquals(null, onlyVisible.find(sha, c.length));

        File visible = TestData.write(new File(dir, "foto.jpg"), c);
        ContentIndex fresh = new ContentIndex(dir, new ContentIndex.Filter() {
            @Override public boolean accept(File f) { return !f.getName().startsWith("."); }
        });
        assertEquals(visible.getAbsolutePath(), fresh.find(sha, c.length));
        assertEquals(null, fresh.find(sha, c.length + 1));
        fresh.add("/ruta/nueva.jpg", "abc", 5);
        assertEquals("/ruta/nueva.jpg", fresh.find("abc", 5));
    }

    private static byte[] readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) break;
                off += r;
            }
            return b;
        } finally {
            in.close();
        }
    }
}
