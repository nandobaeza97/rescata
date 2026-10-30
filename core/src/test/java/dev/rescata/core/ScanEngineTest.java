package dev.rescata.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ScanEngineTest {

    /** Árbol que imita /storage/emulated/0 con los casos típicos. */
    private static final class Tree {
        File root;
        byte[] trashedJpeg, thumb42, statusPng, sentJpeg, vendorTrash, embeddedPng;
        byte[][] miniThumbs;
    }

    private static Tree buildTree() throws Exception {
        Tree t = new Tree();
        t.root = TestData.tempDir();
        File r = t.root;

        // Vivos y visibles: no se deben reportar
        TestData.write(new File(r, "DCIM/Camera/IMG_0001.jpg"), TestData.jpeg(50, 40, 1));
        TestData.write(new File(r, "Download/manual.pdf"), "%PDF-1.4 hola".getBytes("UTF-8"));
        TestData.write(new File(r, "Download/vacio.jpg"), new byte[0]);

        // Papelera del sistema y pendiente
        t.trashedJpeg = TestData.jpeg(60, 40, 2);
        TestData.write(new File(r, "DCIM/Camera/.trashed-1767225600-IMG_0002.jpg"), t.trashedJpeg);
        TestData.write(new File(r, "Download/.pending-1767225600-informe.pdf"), "%PDF-1.7 x".getBytes("UTF-8"));

        // Miniatura moderna y contenedor antiguo
        t.thumb42 = TestData.jpeg(32, 32, 3);
        TestData.write(new File(r, "Pictures/.thumbnails/42.jpg"), t.thumb42);
        t.miniThumbs = new byte[][] {TestData.jpeg(24, 24, 4), TestData.jpeg(24, 24, 5)};
        TestData.write(new File(r, "DCIM/.thumbnails/.thumbdata3--1967290299"),
                TestData.miniThumbContainer(t.miniThumbs[0], t.miniThumbs[1]));

        // Multimedia oculto: sin extensión en carpeta oculta, y carpeta con .nomedia
        t.statusPng = TestData.png(20, 20, 6);
        TestData.write(new File(r, "Android/media/com.example.chat/Media/.Statuses/a1b2c3"), t.statusPng);
        TestData.write(new File(r, "Chat/Sent/.nomedia"), new byte[0]);
        t.sentJpeg = TestData.jpeg(40, 30, 7);
        TestData.write(new File(r, "Chat/Sent/foto.jpg"), t.sentJpeg);
        TestData.write(new File(r, "Music/nota.txt"), "ID3\u0004\u0000 audio".getBytes("ISO-8859-1"));

        // Papelera de un fabricante / app
        t.vendorTrash = TestData.jpeg(44, 33, 8);
        TestData.write(new File(r, "Android/.Trash/com.vendor.gallery/1234567890.jpg"), t.vendorTrash);

        // Contenedor desconocido con una imagen dentro (solo con carving)
        t.embeddedPng = TestData.png(30, 30, 9);
        TestData.write(new File(r, "Documents/cache.bin"),
                TestData.concat(TestData.junk(5000, 1), t.embeddedPng, TestData.junk(3000, 2)));

        // Carpeta de exportación anterior: debe excluirse
        TestData.write(new File(r, "Download/Rescata/.trashed-1767225600-copia.jpg"), TestData.jpeg(10, 10, 10));
        return t;
    }

    private static List<Finding> scan(File root, ScanOptions o) {
        final List<Finding> out = Collections.synchronizedList(new ArrayList<Finding>());
        ScanSummary s = new ScanEngine(o).scan(Collections.singletonList(root), new ScanListener() {
            @Override public void onProgress(long files, long dirs, String path) { }
            @Override public void onFinding(Finding f) { out.add(f); }
        }, null);
        assertEquals(out.size(), s.findings);
        return out;
    }

    private static List<Finding> of(List<Finding> all, Finding.Category c) {
        List<Finding> r = new ArrayList<>();
        for (Finding f : all) if (f.getCategory() == c) r.add(f);
        return r;
    }

    private static ScanOptions baseOptions(Tree t) {
        ScanOptions o = new ScanOptions();
        o.excludedPaths.add(new File(t.root, "Download/Rescata").getAbsolutePath());
        return o;
    }

    @Test
    public void clasificaSinCarving() throws Exception {
        Tree t = buildTree();
        List<Finding> all = scan(t.root, baseOptions(t));

        List<Finding> trash = of(all, Finding.Category.SYSTEM_TRASH);
        assertEquals(1, trash.size());
        assertEquals("IMG_0002.jpg", trash.get(0).getSuggestedName());
        assertEquals(1767225600L, trash.get(0).getExpiresEpochSeconds());
        assertEquals(FileType.JPEG, trash.get(0).getType());

        assertEquals(1, of(all, Finding.Category.PENDING).size());
        assertEquals(FileType.PDF, of(all, Finding.Category.PENDING).get(0).getType());

        List<Finding> thumbs = of(all, Finding.Category.THUMBNAIL);
        assertEquals(3, thumbs.size()); // 42.jpg + 2 del contenedor antiguo
        Finding modern = null;
        for (Finding f : thumbs) if (f.isWholeFile()) modern = f;
        assertNotNull(modern);
        assertEquals(42L, modern.getThumbnailMediaId());

        List<Finding> hidden = of(all, Finding.Category.HIDDEN_MEDIA);
        assertEquals(3, hidden.size()); // a1b2c3 (png), Sent/foto.jpg (.nomedia), nota.txt (mp3)
        List<String> names = new ArrayList<>();
        for (Finding f : hidden) names.add(new File(f.getSourcePath()).getName());
        assertTrue(names.containsAll(Arrays.asList("a1b2c3", "foto.jpg", "nota.txt")));

        List<Finding> vendor = of(all, Finding.Category.TRASH_FOLDER);
        assertEquals(1, vendor.size());
        assertTrue(vendor.get(0).getSourcePath().endsWith("1234567890.jpg"));

        assertEquals(0, of(all, Finding.Category.CARVED).size());
        assertEquals(1 + 1 + 3 + 3 + 1, all.size());

        for (Finding f : all) {
            assertTrue("no debe entrar en la carpeta excluida: " + f, !f.getSourcePath().contains("Rescata"));
            assertTrue("no debe reportar archivos vivos: " + f, !f.getSourcePath().endsWith("IMG_0001.jpg"));
        }
    }

    @Test
    public void conCarvingEncuentraLaImagenIncrustada() throws Exception {
        Tree t = buildTree();
        ScanOptions o = baseOptions(t);
        o.carving = true;
        List<Finding> all = scan(t.root, o);
        List<Finding> carved = of(all, Finding.Category.CARVED);
        assertEquals(1, carved.size());
        assertEquals(FileType.PNG, carved.get(0).getType());
        assertEquals(t.embeddedPng.length, carved.get(0).getLength());
    }

    @Test
    public void respetaLasOpciones() throws Exception {
        Tree t = buildTree();
        ScanOptions o = baseOptions(t);
        o.systemTrash = false;
        o.trashFolders = false;
        o.thumbnails = false;
        o.hiddenMedia = false;
        assertEquals(0, scan(t.root, o).size());
    }

    @Test
    public void exportaConHashEInforme() throws Exception {
        Tree t = buildTree();
        ScanOptions o = baseOptions(t);
        o.carving = true;
        List<Finding> all = scan(t.root, o);
        File out = new File(TestData.tempDir(), "salida");
        Exporter.Result res = new Exporter().export(all, out, null, null);

        assertEquals(all.size(), res.exported);
        assertEquals(0, res.failed);
        assertTrue(res.report.isFile());

        // Cada copia tiene exactamente los bytes del original y el informe lleva su SHA-256
        String[] lines = readReport(res.report);
        assertEquals(all.size() + 1, lines.length);
        for (int i = 1; i < lines.length; i++) {
            String[] cols = lines[i].split(";", -1);
            assertEquals(Exporter.STATUS_EXPORTED, cols[10]);
            File copy = new File(cols[12]);
            assertTrue("falta " + copy, copy.isFile());
            assertEquals(cols[6], sha256(copy));
        }

        // El de la papelera conserva su nombre original y los bytes exactos
        File restored = new File(out, "IMG_0002.jpg");
        assertTrue(restored.isFile());
        assertArrayEquals(t.trashedJpeg, readAll(restored));

        // El extraído por carving recibe la extensión correcta y es idéntico
        File carvedPng = null;
        for (File f : out.listFiles()) if (f.getName().startsWith("cache_0x") && f.getName().endsWith(".png")) carvedPng = f;
        assertNotNull(carvedPng);
        assertArrayEquals(t.embeddedPng, readAll(carvedPng));

        // El origen no se ha tocado
        assertArrayEquals(t.trashedJpeg, readAll(new File(t.root, "DCIM/Camera/.trashed-1767225600-IMG_0002.jpg")));
    }

    @Test
    public void reexportarNoDuplica() throws Exception {
        Tree t = buildTree();
        List<Finding> all = scan(t.root, baseOptions(t));
        File out = new File(TestData.tempDir(), "album");
        Exporter.Result first = new Exporter().export(all, out, null, null);
        int files = out.listFiles().length;
        Exporter.Result second = new Exporter().export(all, out, null, null);

        assertEquals(all.size(), first.exported);
        assertEquals(0, second.exported);
        assertEquals(all.size(), second.alreadyPresent);
        assertEquals(files, out.listFiles().length); // el informe se sobrescribe; ninguna copia nueva
        String[] lines = readReport(second.report);
        for (int i = 1; i < lines.length; i++) assertEquals(Exporter.STATUS_ALREADY, lines[i].split(";", -1)[10]);
    }

    @Test
    public void mismoNombreDistintoContenidoNoSePisa() throws Exception {
        File dir = TestData.tempDir();
        File a = TestData.write(new File(dir, "a/foto.jpg"), TestData.jpeg(20, 20, 1));
        File b = TestData.write(new File(dir, "b/foto.jpg"), TestData.jpeg(20, 20, 2));
        List<Finding> two = new ArrayList<>();
        two.add(new Finding(1, Finding.Category.TRASH_FOLDER, FileType.JPEG, a.getPath(), -1, a.length(), 0, "foto.jpg", 0, -1, ""));
        two.add(new Finding(2, Finding.Category.TRASH_FOLDER, FileType.JPEG, b.getPath(), -1, b.length(), 0, "foto.jpg", 0, -1, ""));
        File out = new File(dir, "salida");
        new Exporter().export(two, out, null, null);
        assertArrayEquals(readAll(a), readAll(new File(out, "foto.jpg")));
        assertArrayEquals(readAll(b), readAll(new File(out, "foto (1).jpg")));
    }

    @Test
    public void enrutaPorDestino() throws Exception {
        Tree t = buildTree();
        List<Finding> all = scan(t.root, baseOptions(t));
        File base = TestData.tempDir();
        final DirectoryDestination thumbs = new DirectoryDestination(new File(base, "Miniaturas"), "Miniaturas");
        final DirectoryDestination media = new DirectoryDestination(new File(base, "Rescata"), "Rescata");
        Exporter.Result res = new Exporter().export(all, new Exporter.Router() {
            @Override public Destination destinationFor(Finding f) {
                return f.getCategory() == Finding.Category.THUMBNAIL ? thumbs : media;
            }
        }, new File(base, "informes/informe.csv"), null, null);
        assertEquals(Integer.valueOf(3), res.exportedByDestination.get("Miniaturas"));
        assertEquals(3, thumbs.getDir().listFiles().length);
        assertEquals(all.size() - 3, media.getDir().listFiles().length);
        assertTrue(res.report.isFile());
    }

    @Test
    public void nombresDeSalidaSeguros() {
        Finding f = new Finding(7, Finding.Category.HIDDEN_MEDIA, FileType.PNG, "/x/a:b", -1, 1, 0,
                "a:b*c?.dat", 0, -1, "");
        assertEquals("a_b_c_.dat.png", Exporter.targetName(f));
    }

    private static String[] readReport(File report) throws Exception {
        String csv = new String(readAll(report), "UTF-8");
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
        return csv.split("\r\n");
    }

    private static byte[] readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return b;
        } finally {
            in.close();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(readAll(f));
        return Exporter.hex(md.digest());
    }
}
