package dev.rescata.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Lectura aleatoria de solo lectura con una caché de bloque, para que los analizadores de formato vayan rápido. */
final class RandomAccessSource implements Closeable {

    private static final int BLOCK = 64 * 1024;

    private final RandomAccessFile raf;
    private final long size;
    private final byte[] block = new byte[BLOCK];
    private long blockStart = -1;
    private int blockLen = 0;

    RandomAccessSource(File file) throws IOException {
        this.raf = new RandomAccessFile(file, "r"); // nunca se abre en escritura
        this.size = raf.length();
    }

    long size() { return size; }

    /** Byte sin signo en {@code pos}, o -1 si está fuera del archivo. */
    int byteAt(long pos) throws IOException {
        if (pos < 0 || pos >= size) return -1;
        if (blockStart < 0 || pos < blockStart || pos >= blockStart + blockLen) {
            load(pos);
            if (pos >= blockStart + blockLen) return -1;
        }
        return block[(int) (pos - blockStart)] & 0xFF;
    }

    int u16be(long pos) throws IOException {
        int a = byteAt(pos), b = byteAt(pos + 1);
        if (a < 0 || b < 0) return -1;
        return (a << 8) | b;
    }

    int u16le(long pos) throws IOException {
        int a = byteAt(pos), b = byteAt(pos + 1);
        if (a < 0 || b < 0) return -1;
        return a | (b << 8);
    }

    long u32be(long pos) throws IOException {
        long r = 0;
        for (int i = 0; i < 4; i++) {
            int x = byteAt(pos + i);
            if (x < 0) return -1;
            r = (r << 8) | x;
        }
        return r;
    }

    long u32le(long pos) throws IOException {
        long r = 0;
        for (int i = 3; i >= 0; i--) {
            int x = byteAt(pos + i);
            if (x < 0) return -1;
            r = (r << 8) | x;
        }
        return r;
    }

    boolean asciiAt(long pos, String s) throws IOException {
        for (int i = 0; i < s.length(); i++) {
            if (byteAt(pos + i) != s.charAt(i)) return false;
        }
        return true;
    }

    /** Lectura directa (sin caché) para tramos grandes. Devuelve los bytes leídos. */
    int read(long pos, byte[] dst, int off, int len) throws IOException {
        if (pos >= size) return 0;
        int want = (int) Math.min(len, size - pos);
        raf.seek(pos);
        int total = 0;
        while (total < want) {
            int r = raf.read(dst, off + total, want - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private void load(long pos) throws IOException {
        blockStart = (pos / BLOCK) * BLOCK;
        blockLen = read(blockStart, block, 0, BLOCK);
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
