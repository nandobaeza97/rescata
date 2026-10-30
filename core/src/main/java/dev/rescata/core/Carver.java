package dev.rescata.core;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/**
 * Carving: busca imágenes completas (JPEG, PNG, GIF, WebP) incrustadas dentro de un archivo
 * contenedor (cachés de miniaturas, bases de datos SQLite, blobs de caché...).
 *
 * <p>No se fía solo de la firma inicial: recorre la estructura interna de cada formato
 * (segmentos JPEG, chunks PNG con CRC, bloques GIF, tamaño RIFF) para saber dónde termina
 * y descartar falsos positivos.
 */
public final class Carver {

    public interface Sink {
        /** @return false para detener el carving de este archivo */
        boolean onCarved(long offset, long length, FileType type);
    }

    public static final long MAX_ITEM_BYTES = 64L * 1024 * 1024;
    public static final long MIN_ITEM_BYTES = 128;
    private static final int SCAN_BUF = 1024 * 1024;
    private static final int OVERLAP = 16;

    private final int maxItemsPerFile;

    public Carver(int maxItemsPerFile) {
        this.maxItemsPerFile = maxItemsPerFile;
    }

    /** @return número de elementos encontrados */
    public int carve(File file, Sink sink, AtomicBoolean cancel) throws IOException {
        RandomAccessSource src = new RandomAccessSource(file);
        try {
            return carve(src, sink, cancel);
        } finally {
            src.close();
        }
    }

    int carve(RandomAccessSource src, Sink sink, AtomicBoolean cancel) throws IOException {
        final long size = src.size();
        final byte[] buf = new byte[SCAN_BUF];
        long pos = 0;
        int found = 0;

        outer:
        while (pos < size) {
            if (cancel != null && cancel.get()) break;
            int n = src.read(pos, buf, 0, buf.length);
            if (n <= 0) break;
            boolean atEnd = pos + n >= size;
            int limit = atEnd ? n : Math.max(1, n - OVERLAP);

            for (int i = 0; i < limit; i++) {
                int b0 = buf[i] & 0xFF;
                if (b0 != 0xFF && b0 != 0x89 && b0 != 'G' && b0 != 'R') continue;
                FileType t = quickMatch(buf, i, n);
                if (t == null) continue;

                long abs = pos + i;
                long len = lengthOf(src, abs, t);
                if (len >= MIN_ITEM_BYTES) {
                    found++;
                    boolean more = sink.onCarved(abs, len, t);
                    if (!more || found >= maxItemsPerFile) break outer;
                    pos = abs + len; // salta el elemento entero: no se recortan sus miniaturas EXIF internas
                    continue outer;
                }
            }
            pos += limit;
        }
        return found;
    }

    private static FileType quickMatch(byte[] b, int i, int n) {
        int avail = n - i;
        if (avail >= 4 && (b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xD8 && (b[i + 2] & 0xFF) == 0xFF) {
            int m = b[i + 3] & 0xFF;
            // Tras SOI debe venir un marcador real: APPn, DQT, DHT, SOF, COM, DRI.
            if ((m >= 0xE0 && m <= 0xEF) || m == 0xDB || m == 0xC4 || m == 0xFE || m == 0xDD
                    || (m >= 0xC0 && m <= 0xCF && m != 0xC8)) return FileType.JPEG;
            return null;
        }
        if (avail >= 8 && (b[i] & 0xFF) == 0x89 && b[i + 1] == 'P' && b[i + 2] == 'N' && b[i + 3] == 'G'
                && b[i + 4] == 0x0D && b[i + 5] == 0x0A && b[i + 6] == 0x1A && b[i + 7] == 0x0A) return FileType.PNG;
        if (avail >= 6 && b[i] == 'G' && b[i + 1] == 'I' && b[i + 2] == 'F' && b[i + 3] == '8'
                && (b[i + 4] == '7' || b[i + 4] == '9') && b[i + 5] == 'a') return FileType.GIF;
        if (avail >= 12 && b[i] == 'R' && b[i + 1] == 'I' && b[i + 2] == 'F' && b[i + 3] == 'F'
                && b[i + 8] == 'W' && b[i + 9] == 'E' && b[i + 10] == 'B' && b[i + 11] == 'P') return FileType.WEBP;
        return null;
    }

    static long lengthOf(RandomAccessSource s, long start, FileType t) throws IOException {
        switch (t) {
            case JPEG: return jpegLength(s, start);
            case PNG: return pngLength(s, start);
            case GIF: return gifLength(s, start);
            case WEBP: return webpLength(s, start);
            default: return -1;
        }
    }

    // ---------------------------------------------------------------- JPEG

    static long jpegLength(RandomAccessSource s, long start) throws IOException {
        final long limit = Math.min(s.size(), start + MAX_ITEM_BYTES);
        long p = start + 2; // después de FFD8
        boolean sawSof = false, sawSos = false;

        while (p + 1 < limit) {
            if (s.byteAt(p) != 0xFF) return -1;
            int m = s.byteAt(p + 1);
            while (m == 0xFF) { // bytes de relleno entre marcadores
                p++;
                if (p + 1 >= limit) return -1;
                m = s.byteAt(p + 1);
            }
            if (m == 0xD9) return (sawSof && sawSos) ? (p + 2 - start) : -1; // EOI
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { // TEM / RSTn: sin longitud
                p += 2;
                continue;
            }
            // 0x02-0xBF son códigos reservados y un SOI aquí indica que empieza otra imagen: no es un JPEG válido.
            if (m < 0xC0 || m == 0xD8) return -1;
            int segLen = s.u16be(p + 2);
            if (segLen < 2) return -1;
            long segEnd = p + 2 + segLen;
            if (segEnd > limit) return -1;

            if (isSof(m)) sawSof = true;

            if (m == 0xDA) { // SOS: a continuación, datos de entropía hasta el siguiente marcador
                sawSos = true;
                p = segEnd;
                while (p + 1 < limit) {
                    if (s.byteAt(p) != 0xFF) { p++; continue; }
                    int nx = s.byteAt(p + 1);
                    if (nx == 0x00 || (nx >= 0xD0 && nx <= 0xD7)) { p += 2; continue; } // relleno / RST
                    if (nx == 0xFF) { p++; continue; }
                    if (nx < 0xC0) return -1; // en datos de entropía, 0xFF solo puede ir seguido de 00, RST o un marcador
                    break; // marcador real en p
                }
                if (p + 1 >= limit) return -1;
            } else {
                p = segEnd;
            }
        }
        return -1;
    }

    private static boolean isSof(int m) {
        return m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
    }

    // ---------------------------------------------------------------- PNG

    static long pngLength(RandomAccessSource s, long start) throws IOException {
        final long limit = Math.min(s.size(), start + MAX_ITEM_BYTES);
        long p = start + 8;
        boolean first = true, sawIdat = false;
        byte[] tmp = new byte[64 * 1024];

        while (p + 12 <= limit) {
            long len = s.u32be(p);
            if (len < 0 || len > 0x7FFFFFFFL) return -1;
            long chunkEnd = p + 12 + len;
            if (chunkEnd > limit) return -1;

            StringBuilder type = new StringBuilder(4);
            for (int i = 0; i < 4; i++) {
                int c = s.byteAt(p + 4 + i);
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) return -1;
                type.append((char) c);
            }
            String ty = type.toString();
            if (first && (!ty.equals("IHDR") || len != 13)) return -1;
            first = false;

            CRC32 crc = new CRC32();
            long q = p + 4, end = p + 8 + len;
            while (q < end) {
                int want = (int) Math.min(tmp.length, end - q);
                int got = s.read(q, tmp, 0, want);
                if (got <= 0) return -1;
                crc.update(tmp, 0, got);
                q += got;
            }
            if (crc.getValue() != s.u32be(p + 8 + len)) return -1;

            if (ty.equals("IDAT")) sawIdat = true;
            if (ty.equals("IEND")) return sawIdat ? (chunkEnd - start) : -1;
            p = chunkEnd;
        }
        return -1;
    }

    // ---------------------------------------------------------------- GIF

    static long gifLength(RandomAccessSource s, long start) throws IOException {
        final long limit = Math.min(s.size(), start + MAX_ITEM_BYTES);
        int w = s.u16le(start + 6), h = s.u16le(start + 8);
        int packed = s.byteAt(start + 10);
        if (w <= 0 || h <= 0 || packed < 0) return -1;
        long p = start + 13;
        if ((packed & 0x80) != 0) p += 3L * (1 << ((packed & 7) + 1));
        boolean sawImage = false;

        while (p < limit) {
            int b = s.byteAt(p);
            if (b == 0x3B) return sawImage ? (p + 1 - start) : -1; // trailer
            if (b == 0x21) { // extensión
                p = skipSubBlocks(s, p + 2, limit);
                if (p < 0) return -1;
            } else if (b == 0x2C) { // descriptor de imagen
                int lp = s.byteAt(p + 9);
                if (lp < 0) return -1;
                p += 10;
                if ((lp & 0x80) != 0) p += 3L * (1 << ((lp & 7) + 1));
                int lzw = s.byteAt(p);
                if (lzw < 2 || lzw > 12) return -1;
                p = skipSubBlocks(s, p + 1, limit);
                if (p < 0) return -1;
                sawImage = true;
            } else {
                return -1;
            }
        }
        return -1;
    }

    private static long skipSubBlocks(RandomAccessSource s, long p, long limit) throws IOException {
        while (p < limit) {
            int sz = s.byteAt(p);
            if (sz < 0) return -1;
            p += 1;
            if (sz == 0) return p;
            p += sz;
        }
        return -1;
    }

    // ---------------------------------------------------------------- WebP

    static long webpLength(RandomAccessSource s, long start) throws IOException {
        long riffSize = s.u32le(start + 4);
        if (riffSize < 12 || (riffSize & 1) != 0) return -1;
        long total = riffSize + 8;
        if (total > MAX_ITEM_BYTES || start + total > s.size()) return -1;
        if (!(s.asciiAt(start + 12, "VP8 ") || s.asciiAt(start + 12, "VP8L") || s.asciiAt(start + 12, "VP8X"))) return -1;
        return total;
    }
}
