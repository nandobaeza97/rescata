package dev.rescata.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Genera imágenes reales (con ImageIO) y estructuras de prueba para los tests. */
final class TestData {

    private TestData() {}

    static byte[] jpeg(int w, int h, long seed) throws IOException {
        return encode(noise(w, h, seed, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    static byte[] png(int w, int h, long seed) throws IOException {
        return encode(noise(w, h, seed, BufferedImage.TYPE_INT_RGB), "png");
    }

    static byte[] gif(int w, int h, long seed) throws IOException {
        return encode(noise(w, h, seed, BufferedImage.TYPE_BYTE_INDEXED), "gif");
    }

    /** WebP mínimo estructuralmente válido (RIFF + chunk VP8L). */
    static byte[] webp(int payload) {
        int chunk = payload + (payload & 1);
        int riffSize = 4 + 8 + chunk;
        byte[] b = new byte[8 + riffSize];
        put(b, 0, "RIFF");
        le32(b, 4, riffSize);
        put(b, 8, "WEBP");
        put(b, 12, "VP8L");
        le32(b, 16, payload);
        b[20] = 0x2F;
        for (int i = 21; i < 20 + payload; i++) b[i] = (byte) (i * 31);
        return b;
    }

    /** JPEG con un segmento APP1 "Exif" que lleva otra miniatura JPEG dentro. */
    static byte[] jpegWithEmbeddedThumb(long seed) throws IOException {
        byte[] outer = jpeg(160, 120, seed);
        byte[] thumb = jpeg(16, 12, seed + 1);
        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.write(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        app1.write(thumb);
        byte[] payload = app1.toByteArray();
        int segLen = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(outer, 0, 2);                       // SOI
        out.write(0xFF); out.write(0xE1);             // APP1
        out.write((segLen >> 8) & 0xFF); out.write(segLen & 0xFF);
        out.write(payload);
        out.write(outer, 2, outer.length - 2);        // resto del JPEG original
        return out.toByteArray();
    }

    /** Contenedor estilo MiniThumbFile antiguo: ranuras de 10000 bytes con cabecera de 13 bytes. */
    static byte[] miniThumbContainer(byte[]... jpegs) {
        int slot = 10000;
        byte[] b = new byte[slot * (jpegs.length + 1)];
        for (int i = 0; i < jpegs.length; i++) {
            int base = slot * (i + 1);
            b[base] = 1;
            for (int k = 1; k <= 8; k++) b[base + k] = (byte) (0x40 + i + k);
            int len = jpegs[i].length;
            b[base + 9] = (byte) (len >>> 24); b[base + 10] = (byte) (len >>> 16);
            b[base + 11] = (byte) (len >>> 8); b[base + 12] = (byte) len;
            System.arraycopy(jpegs[i], 0, b, base + 13, len);
        }
        return b;
    }

    static byte[] junk(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        // quitamos 0xFF y 0x89 para no fabricar firmas por azar
        for (int i = 0; i < n; i++) if ((b[i] & 0xFF) == 0xFF || (b[i] & 0xFF) == 0x89 || b[i] == 'G' || b[i] == 'R') b[i] = 0x11;
        return b;
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Recalcula el CRC de un PNG tras corromper un byte, para verificar que el carver lo descarta. */
    static byte[] corruptPngKeepStructure(byte[] png) {
        byte[] b = png.clone();
        b[8 + 8 + 5] ^= 0x01; // un byte de datos de IHDR; el CRC almacenado ya no coincide
        return b;
    }

    static long crc(byte[] b, int off, int len) {
        CRC32 c = new CRC32();
        c.update(b, off, len);
        return c.getValue();
    }

    static File write(File f, byte[] data) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.isDirectory() && !p.mkdirs()) throw new IOException("mkdirs " + p);
        OutputStream os = new FileOutputStream(f);
        try {
            os.write(data);
        } finally {
            os.close();
        }
        return f;
    }

    static File tempDir() throws IOException {
        return Files.createTempDirectory("rescata-test").toFile();
    }

    private static BufferedImage noise(int w, int h, long seed, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        Random r = new Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, r.nextInt(0xFFFFFF));
            }
        }
        return img;
    }

    private static byte[] encode(BufferedImage img, String fmt) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, fmt, out)) throw new IOException("sin writer para " + fmt);
        return out.toByteArray();
    }

    private static void put(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void le32(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >>> 8); b[off + 2] = (byte) (v >>> 16); b[off + 3] = (byte) (v >>> 24);
    }
}
