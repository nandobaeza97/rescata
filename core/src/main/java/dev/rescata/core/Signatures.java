package dev.rescata.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Identificación de tipos de archivo por sus primeros bytes, sin fiarse de la extensión. */
public final class Signatures {

    /** Bytes de cabecera que se leen para identificar un archivo. */
    public static final int HEAD_SIZE = 32;

    private Signatures() {}

    public static FileType identify(File file) {
        byte[] head = new byte[HEAD_SIZE];
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            int n = 0;
            while (n < head.length) {
                int r = in.read(head, n, head.length - n);
                if (r < 0) break;
                n += r;
            }
            return identify(head, n);
        } catch (IOException | SecurityException e) {
            return FileType.UNKNOWN;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    public static FileType identify(byte[] b, int n) {
        if (n >= 3 && u(b, 0) == 0xFF && u(b, 1) == 0xD8 && u(b, 2) == 0xFF) return FileType.JPEG;
        if (n >= 8 && u(b, 0) == 0x89 && ascii(b, 1, "PNG") && u(b, 4) == 0x0D && u(b, 5) == 0x0A
                && u(b, 6) == 0x1A && u(b, 7) == 0x0A) return FileType.PNG;
        if (n >= 6 && (ascii(b, 0, "GIF87a") || ascii(b, 0, "GIF89a"))) return FileType.GIF;
        if (n >= 12 && ascii(b, 0, "RIFF")) {
            if (ascii(b, 8, "WEBP")) return FileType.WEBP;
            if (ascii(b, 8, "WAVE")) return FileType.WAV;
        }
        if (n >= 12 && ascii(b, 4, "ftyp")) return identifyFtyp(b, n);
        if (n >= 4 && u(b, 0) == 0x1A && u(b, 1) == 0x45 && u(b, 2) == 0xDF && u(b, 3) == 0xA3) return FileType.MKV;
        if (n >= 4 && ascii(b, 0, "OggS")) return FileType.OGG;
        if (n >= 5 && ascii(b, 0, "#!AMR")) return FileType.AMR;
        if (n >= 3 && ascii(b, 0, "ID3")) return FileType.MP3;
        // Trama MPEG-1/2 Layer III sin etiqueta ID3 (sincronía 0xFFE + capa III).
        if (n >= 2 && u(b, 0) == 0xFF && (u(b, 1) == 0xFB || u(b, 1) == 0xF3 || u(b, 1) == 0xF2)) return FileType.MP3;
        if (n >= 5 && ascii(b, 0, "%PDF-")) return FileType.PDF;
        if (n >= 4 && u(b, 0) == 'P' && u(b, 1) == 'K' && u(b, 2) == 3 && u(b, 3) == 4) return FileType.ZIP;
        if (n >= 16 && ascii(b, 0, "SQLite format 3") && u(b, 15) == 0) return FileType.SQLITE;
        return FileType.UNKNOWN;
    }

    private static FileType identifyFtyp(byte[] b, int n) {
        String brand = new String(new char[] {(char) u(b, 8), (char) u(b, 9), (char) u(b, 10), (char) u(b, 11)});
        switch (brand) {
            case "heic": case "heix": case "hevc": case "hevx":
            case "heim": case "heis": case "mif1": case "msf1":
                return FileType.HEIF;
            case "avif": case "avis":
                return FileType.AVIF;
            case "M4A ": case "M4B ": case "M4P ":
                return FileType.M4A;
            default:
                return FileType.MP4;
        }
    }

    static int u(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    static boolean ascii(byte[] b, int off, String s) {
        if (off + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) {
            if ((b[off + i] & 0xFF) != s.charAt(i)) return false;
        }
        return true;
    }
}
