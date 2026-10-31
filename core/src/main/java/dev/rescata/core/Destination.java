package dev.rescata.core;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Dónde se guarda una copia recuperada: una carpeta normal, o (en la app) un álbum de la galería
 * a través de MediaStore.
 */
public interface Destination {

    /** Escribe el contenido del hallazgo en el flujo que le pasa el destino. */
    interface ContentWriter {
        void writeTo(OutputStream out) throws IOException;
    }

    /** Nombre corto y legible del destino para el informe y los mensajes (p. ej. "Galería › Rescata"). */
    String label();

    /**
     * Si ya existe en el destino una copia con exactamente el mismo contenido, devuelve su ubicación;
     * si no, null. Sirve para no duplicar al exportar varias veces.
     */
    String findExisting(Finding finding, String sha256, long size) throws IOException;

    /**
     * Crea la copia con el nombre indicado (el destino puede ajustarlo si ya existe otro igual)
     * y devuelve su ubicación final.
     */
    String write(Finding finding, String fileName, ContentWriter writer) throws IOException;

    /**
     * Aviso tras escribir una copia con éxito, con su contenido ya comprobado. Sirve para que el
     * destino la tenga en cuenta al buscar duplicados sin volver a leer la carpeta.
     */
    default void written(String location, String sha256, long size) { }
}
