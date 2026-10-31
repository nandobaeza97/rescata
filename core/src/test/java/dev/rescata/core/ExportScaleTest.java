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

    private static List<Finding> findingsFor(File... files) {
        List<Finding> out = new ArrayList<>();
        long id = 1;
        for (File f : files) {
            out.add(new Finding(id++, Finding.Category.TRASH_FOLDER, FileType.JPEG, f.getPath(), -1, f.length(), 0,
                    f.getName(), 0, -1, ""));
        }
        return out;
    }

    private static String[] reportLines(File report) throws Exception {
        String text = new String(readAll(report), "UTF-8").replace("\uFEFF", "");
        return text.split("\r\n");
    }

    @Test
    public void elInformeSeEscribeSobreLaMarcha() throws Exception {
        File base = TestData.tempDir();
        File[] src = new File[8];
        for (int i = 0; i < src.length; i++) src[i] = TestData.write(new File(base, "o/f" + i + ".jpg"), TestData.jpeg(20, 20, 40 + i));
        final DirectoryDestination d = new DirectoryDestination(new File(base, "salida"), "salida");
        final int[] calls = {0};
        File report = new File(base, "informe.csv");
        try {
            new Exporter().export(findingsFor(src), new Exporter.Router() {
                @Override public Destination destinationFor(Finding f) {
                    if (++calls[0] > 5) throw new AssertionError("corte brusco (p. ej. se apaga el móvil)");
                    return d;
                }
            }, report, null, null);
            throw new IllegalStateException("debía cortarse");
        } catch (AssertionError expected) {
            // lo que importa es lo que quedó escrito
        }
        String[] lines = reportLines(report);
        assertEquals(6, lines.length); // cabecera + los 5 guardados antes del corte
        for (int i = 1; i < lines.length; i++) assertEquals(Exporter.STATUS_EXPORTED, lines[i].split(";", -1)[10]);
    }

    @Test
    public void sinEspacioSeDetieneYLoIndica() throws Exception {
        File base = TestData.tempDir();
        File[] src = new File[6];
        for (int i = 0; i < src.length; i++) src[i] = TestData.write(new File(base, "o/f" + i + ".jpg"), TestData.jpeg(20, 20, 60 + i));
        File out = new File(base, "salida");
        final int[] room = {3};
        Exporter.Result r = new Exporter().spaceGuard(new Exporter.SpaceGuard() {
            @Override public boolean hasRoomFor(long bytes) { return room[0]-- > 0; }
        }).export(findingsFor(src), out, null, null);
        assertEquals(3, r.exported);
        assertTrue(r.outOfSpace);
        assertEquals(2, r.notAttempted);
        String[] lines = reportLines(r.report);
        assertEquals(5, lines.length);
        assertEquals(Exporter.STATUS_NO_SPACE, lines[4].split(";", -1)[10]);
        assertEquals(4, out.listFiles().length); // 3 copias + informe
    }

    @Test
    public void mueveLasCopiasDeLaVersionAnterior() throws Exception {
        File base = TestData.tempDir();
        byte[] c0 = TestData.jpeg(20, 20, 80), c1 = TestData.jpeg(20, 20, 81), c2 = TestData.jpeg(20, 20, 82);
        File a = TestData.write(new File(base, "o/a.jpg"), c0);
        File b = TestData.write(new File(base, "o/b.jpg"), c1);
        File c = TestData.write(new File(base, "o/c.jpg"), c2);
        File legacy = new File(base, "Rescata/20261030_221500");
        File oldA = TestData.write(new File(legacy, "00001_a.jpg"), c0);
        File oldB = TestData.write(new File(legacy, "00002_b.jpg"), c1);
        File other = TestData.write(new File(legacy, "informe.csv"), "id;x\r\n".getBytes("UTF-8"));
        File album = new File(base, "album");

        List<File> legacyDirs = new ArrayList<>();
        legacyDirs.add(legacy);
        Exporter.Result r = new Exporter().previousCopies(new ContentIndex(legacyDirs, null))
                .export(findingsFor(a, b, c), album, null, null);
        assertEquals(2, r.moved);
        assertEquals(1, r.exported);
        assertTrue(!oldA.exists() && !oldB.exists());
        assertTrue(other.isFile()); // lo que no es una copia se queda donde está
        assertArrayEquals(c0, readAll(new File(album, "a.jpg")));
        assertArrayEquals(c1, readAll(new File(album, "b.jpg")));
        assertArrayEquals(c2, readAll(new File(album, "c.jpg")));
        String[] lines = reportLines(r.report);
        assertEquals(Exporter.STATUS_MOVED, lines[1].split(";", -1)[10]);
        assertEquals(Exporter.STATUS_MOVED, lines[2].split(";", -1)[10]);
        assertEquals(Exporter.STATUS_EXPORTED, lines[3].split(";", -1)[10]);
        // El original nunca se toca
        assertArrayEquals(c0, readAll(a));

        // Si no se puede mover (otro almacenamiento), se copia como siempre y la copia antigua se queda
        File oldC = TestData.write(new File(legacy, "00003_c.jpg"), c2);
        final DirectoryDestination noMove = new DirectoryDestination(new File(base, "album2"), "album2") {
            @Override public String moveIn(Finding f, String name, File existing) { return null; }
        };
        Exporter.Result r2 = new Exporter().previousCopies(new ContentIndex(legacyDirs, null))
                .export(findingsFor(c), new Exporter.Router() {
                    @Override public Destination destinationFor(Finding f) { return noMove; }
                }, null, null, null);
        assertEquals(1, r2.exported);
        assertEquals(0, r2.moved);
        assertTrue(oldC.isFile());
        assertArrayEquals(c2, readAll(new File(base, "album2/c.jpg")));
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
