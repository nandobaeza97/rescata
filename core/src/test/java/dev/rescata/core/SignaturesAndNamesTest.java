package dev.rescata.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SignaturesAndNamesTest {

    private static byte[] ftyp(String brand) {
        byte[] b = new byte[16];
        b[3] = 16;
        b[4] = 'f'; b[5] = 't'; b[6] = 'y'; b[7] = 'p';
        for (int i = 0; i < 4; i++) b[8 + i] = (byte) brand.charAt(i);
        return b;
    }

    private static byte[] ascii(String s) {
        byte[] b = new byte[Math.max(16, s.length())];
        for (int i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    @Test
    public void identificaPorFirma() throws Exception {
        byte[] j = TestData.jpeg(8, 8, 1);
        assertEquals(FileType.JPEG, Signatures.identify(j, j.length));
        byte[] p = TestData.png(8, 8, 1);
        assertEquals(FileType.PNG, Signatures.identify(p, p.length));
        byte[] g = TestData.gif(8, 8, 1);
        assertEquals(FileType.GIF, Signatures.identify(g, g.length));
        byte[] w = TestData.webp(10);
        assertEquals(FileType.WEBP, Signatures.identify(w, w.length));
        assertEquals(FileType.HEIF, Signatures.identify(ftyp("heic"), 16));
        assertEquals(FileType.AVIF, Signatures.identify(ftyp("avif"), 16));
        assertEquals(FileType.MP4, Signatures.identify(ftyp("isom"), 16));
        assertEquals(FileType.M4A, Signatures.identify(ftyp("M4A "), 16));
        assertEquals(FileType.PDF, Signatures.identify(ascii("%PDF-1.7"), 16));
        assertEquals(FileType.MP3, Signatures.identify(ascii("ID3\u0004"), 16));
        assertEquals(FileType.OGG, Signatures.identify(ascii("OggS"), 16));
        assertEquals(FileType.AMR, Signatures.identify(ascii("#!AMR\n"), 16));
        byte[] sqlite = ascii("SQLite format 3");
        sqlite[15] = 0;
        assertEquals(FileType.SQLITE, Signatures.identify(sqlite, 16));
        assertEquals(FileType.UNKNOWN, Signatures.identify(ascii("hola, mundo"), 16));
        assertEquals(FileType.UNKNOWN, Signatures.identify(new byte[0], 0));
    }

    @Test
    public void extensiones() {
        assertEquals("jpg", FileType.extensionOf("IMG_1.JPG"));
        assertEquals("", FileType.extensionOf("sin_extension"));
        assertEquals("", FileType.extensionOf(".nomedia"));
        assertTrue(FileType.JPEG.matchesExtension("jpeg"));
        assertFalse(FileType.PNG.matchesExtension("jpg"));
    }

    @Test
    public void decodificaPapeleraDeMediaStore() {
        TrashedName t = TrashedName.parse(".trashed-1767225600-IMG_20250101_120000.jpg");
        assertNotNull(t);
        assertEquals(TrashedName.State.TRASHED, t.getState());
        assertEquals(1767225600L, t.getExpiresEpochSeconds());
        assertEquals("IMG_20250101_120000.jpg", t.getOriginalName());

        TrashedName p = TrashedName.parse(".pending-1700000000-clip.mp4");
        assertNotNull(p);
        assertEquals(TrashedName.State.PENDING, p.getState());

        TrashedName guiones = TrashedName.parse(".trashed-1767225600-mis-vacaciones-2024-04.jpg");
        assertNotNull(guiones);
        assertEquals("mis-vacaciones-2024-04.jpg", guiones.getOriginalName());

        assertNotNull(TrashedName.parse(".TRASHED-1767225600-a.jpg")); // el prefijo no distingue mayúsculas
        assertNull(TrashedName.parse(".trashed-abc-foto.jpg"));
        assertNull(TrashedName.parse("trashed-1767225600-foto.jpg"));
        assertNull(TrashedName.parse("factura-2024-final.pdf"));
        assertNull(TrashedName.parse(".trashed-99999999999999999999999-foto.jpg")); // no cabe en long
    }

    @Test
    public void nombresDePapelera() {
        assertTrue(ScanEngine.isTrashDirName(".Trash"));
        assertTrue(ScanEngine.isTrashDirName(".trash-storage"));
        assertTrue(ScanEngine.isTrashDirName("RecycleBin"));
        assertTrue(ScanEngine.isTrashDirName(".recycle"));
        assertTrue(ScanEngine.isTrashDirName("Recently deleted"));
        assertTrue(ScanEngine.isTrashDirName("Papelera"));
        assertFalse(ScanEngine.isTrashDirName("Camera"));
        assertFalse(ScanEngine.isTrashDirName(".thumbnails"));
    }
}
