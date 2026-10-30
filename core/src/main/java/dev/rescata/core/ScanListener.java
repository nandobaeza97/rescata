package dev.rescata.core;

public interface ScanListener {
    /** Se llama con frecuencia; la interfaz debe limitar la tasa de refresco por su cuenta. */
    void onProgress(long filesVisited, long dirsVisited, String currentPath);

    void onFinding(Finding finding);
}
