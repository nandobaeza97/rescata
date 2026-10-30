package dev.rescata.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class CarverTest {

    private static final class Hit {
        final long off; final long len; final FileType type;
        Hit(long off, long len, FileType type) { this.off = off; this.len = len; this.type = type; }
    }

    private static List<Hit> carve(File f) throws Exception {
        final List<Hit> hits = new ArrayList<>();
        new Carver(10000).carve(f, new Carver.Sink() {
            @Override public boolean onCarved(long offset, long length, FileType type) {
                hits.add(new Hit(offset, length, type));
                return true;
            }
        }, null);
        return hits;
    }

    private static byte[] slice(byte[] b, Hit h) {
        return Arrays.copyOfRange(b, (int) h.off, (int) (h.off + h.len));
    }

    @Test
    public void extraeImagenesExactasDeUnBlob() throws Exception {
        byte[] j1 = TestData.jpeg(120, 90, 10);
        byte[] p1 = TestData.png(40, 30, 11);
        byte[] g1 = TestData.gif(30, 20, 12);
        byte[] w1 = TestData.webp(300);
        byte[] j2 = TestData.jpeg(64, 64, 13);
        byte[] blob = TestData.concat(TestData.junk(777, 1), j1, TestData.junk(50, 2), p1, g1,
                TestData.junk(3, 3), w1, j2, TestData.junk(900, 4));

        File dir = TestData.tempDir();
        File f = TestData.write(new File(dir, "blob.bin"), blob);
        List<Hit> hits = carve(f);

        assertEquals(5, hits.size());
        assertEquals(FileType.JPEG, hits.get(0).type);
        assertArrayEquals(j1, slice(blob, hits.get(0)));
        assertEquals(FileType.PNG, hits.get(1).type);
        assertArrayEquals(p1, slice(blob, hits.get(1)));
        assertEquals(FileType.GIF, hits.get(2).type);
        assertArrayEquals(g1, slice(blob, hits.get(2)));
        assertEquals(FileType.WEBP, hits.get(3).type);
        assertArrayEquals(w1, slice(blob, hits.get(3)));
        assertArrayEquals(j2, slice(blob, hits.get(4)));
    }

    @Test
    public void descartaTruncadosYCorruptos() throws Exception {
        byte[] j = TestData.jpeg(100, 80, 20);
        byte[] truncated = Arrays.copyOf(j, j.length - 200); // sin EOI
        byte[] badPng = TestData.corruptPngKeepStructure(TestData.png(20, 20, 21));
        byte[] good = TestData.png(25, 25, 22);
        byte[] blob = TestData.concat(truncated, TestData.junk(100, 5), badPng, TestData.junk(100, 6), good);

        File f = TestData.write(new File(TestData.tempDir(), "mix.bin"), blob);
        List<Hit> hits = carve(f);

        // El JPEG truncado se "come" bytes hasta encontrar un marcador inválido y se descarta;
        // el PNG con CRC erróneo se descarta; el PNG bueno se extrae entero.
        assertEquals(1, hits.size());
        assertEquals(FileType.PNG, hits.get(0).type);
        assertArrayEquals(good, slice(blob, hits.get(0)));
    }

    @Test
    public void noTrocealaMiniaturaExifInterna() throws Exception {
        byte[] withThumb = TestData.jpegWithEmbeddedThumb(30);
        byte[] blob = TestData.concat(TestData.junk(64, 7), withThumb, TestData.junk(64, 8));
        File f = TestData.write(new File(TestData.tempDir(), "exif.bin"), blob);
        List<Hit> hits = carve(f);
        assertEquals(1, hits.size());
        assertArrayEquals(withThumb, slice(blob, hits.get(0)));
    }

    @Test
    public void encuentraFirmasQueCruzanElLimiteDelBuffer() throws Exception {
        byte[] j = TestData.jpeg(90, 90, 40);
        int start = 1024 * 1024 - 5; // la firma cae a caballo entre dos lecturas de 1 MiB
        byte[] blob = TestData.concat(TestData.junk(start, 9), j, TestData.junk(1000, 10));
        File f = TestData.write(new File(TestData.tempDir(), "edge.bin"), blob);
        List<Hit> hits = carve(f);
        assertEquals(1, hits.size());
        assertEquals(start, hits.get(0).off);
        assertArrayEquals(j, slice(blob, hits.get(0)));
    }

    @Test
    public void contenedorDeMiniaturasAntiguo() throws Exception {
        byte[] a = TestData.jpeg(32, 32, 50), b = TestData.jpeg(48, 32, 51), c = TestData.jpeg(32, 48, 52);
        assertTrue(a.length < 9000 && b.length < 9000 && c.length < 9000);
        byte[] cont = TestData.miniThumbContainer(a, b, c);
        File f = TestData.write(new File(TestData.tempDir(), ".thumbdata3--1967290299"), cont);
        List<Hit> hits = carve(f);
        assertEquals(3, hits.size());
        assertArrayEquals(a, slice(cont, hits.get(0)));
        assertArrayEquals(b, slice(cont, hits.get(1)));
        assertArrayEquals(c, slice(cont, hits.get(2)));
    }
}
