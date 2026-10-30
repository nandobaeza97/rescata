# Rescata: recuperación de archivos borrados en Android sin root

App Android (Kotlin + Jetpack Compose) con un motor de escaneo en Java puro. Busca en el
almacenamiento del teléfono lo que todavía se puede recuperar **sin root** y lo exporta con un
informe de hashes SHA-256.

## Antes de nada: qué es posible y qué no

Los móviles actuales no exponen la NAND al software. Usan eMMC o UFS, cuyo controlador interno
oculta las páginas físicas. Sin root, una app ni siquiera puede leer los dispositivos de bloque
(`/dev/block/...`), así que **no puede hacer un escaneo sector a sector del espacio libre**.
Además, desde Android 10 todos los dispositivos nuevos usan cifrado basado en archivos, y el
TRIM del sistema de archivos descarta los bloques liberados. Por eso, incluso con root, un
borrado "de verdad" en un móvil moderno casi nunca se puede recuperar.

Lo que sí se puede recuperar es lo que **sigue existiendo como archivo**, aunque el usuario ya no
lo vea:

| Fuente | Por qué funciona |
|---|---|
| **Papelera del sistema** (Android 11+) | Al mandar algo a la papelera, MediaStore lo renombra en su sitio a `.trashed-<caducidad>-<nombre>` y lo conserva 30 días. Lo usa, por ejemplo, Google Fotos al borrar fotos del dispositivo. |
| **Archivos `.pending-`** | Escrituras sin terminar (descargas o capturas interrumpidas). Se conservan 7 días. |
| **Papeleras de apps y fabricantes** | Carpetas como `.Trash`, `RecycleBin` o `.recycle` dentro del almacenamiento compartido. |
| **Miniaturas** | `Pictures/.thumbnails/<id>.jpg` (Android 10+) y los contenedores antiguos `.thumbdata*`. Muchas veces sobreviven a la foto original. La app marca las miniaturas cuyo ID ya no existe en MediaStore. |
| **Multimedia oculto** | Copias en carpetas ocultas o con `.nomedia` (por ejemplo, estados y enviados de apps de mensajería), archivos sin extensión o con una extensión falsa. La app los identifica por su firma, no por la extensión. |
| **Carving** (opcional, lento) | Imágenes JPEG/PNG/GIF/WebP incrustadas dentro de cachés y bases de datos SQLite accesibles. |

Fuera de alcance sin root: los datos privados de otras apps (`/data/data`, `Android/data` en
Android 11+), la papelera en la nube de Google Fotos y todo lo que ya se borró del sistema de archivos.

## Cómo usarla

1. Abre la carpeta `Rescata` en Android Studio y deja que sincronice Gradle (si te propone
   actualizar el Android Gradle Plugin, puedes aceptar).
2. Conecta el móvil con la depuración USB activada y pulsa **Run**, o desde la terminal:
   ```
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. En la app, pulsa **Conceder acceso** y activa «Permitir acceso para gestionar todos los
   archivos». Con ADB también se puede conceder directamente:
   ```
   adb shell appops set --uid dev.rescata.app MANAGE_EXTERNAL_STORAGE allow
   ```
4. Elige qué buscar, pulsa **Escanear**, marca lo que te interese y pulsa **Exportar**.
   Se copia a `Download/Rescata/<fecha>/` junto con `informe.csv`, que lleva el origen, el
   offset, el SHA-256 y la fecha de caducidad de cada archivo. Si hay tarjeta SD o USB OTG, la
   app ofrece exportar allí para no escribir en la memoria interna.
5. Los elementos de la papelera del sistema también se pueden **Restaurar** en su ubicación
   original. Android pide confirmación al usuario en un diálogo del sistema.

La app solo lee el origen: abre los archivos en modo lectura y nunca mueve ni borra nada. Solo
escribe al exportar.

### Sin Android Studio: compilar el APK en GitHub

El proyecto incluye `.github/workflows/compilar-apk.yml`. Sube la carpeta a un repositorio de
GitHub y GitHub Actions compilará el APK en sus servidores y lo publicará en la pestaña
**Releases**. Desde ahí se descarga en el móvil y se instala directamente. También puedes
lanzarlo a mano en **Actions → Compilar APK → Run workflow**.

### Instalar el APK en el móvil

Abre el `.apk` desde el navegador o el gestor de archivos. Android pedirá permitir «Instalar
apps desconocidas» para esa app, y Play Protect puede avisar porque la app no viene de Play.
Gmail no permite adjuntar archivos `.apk`, así que usa un enlace de descarga, un cable USB o
`adb install`.

### Comprobación rápida solo con ADB (sin instalar nada)

```
adb shell find /sdcard/ -name '.trashed-*' -o -name '.pending-*'
adb pull "/sdcard/DCIM/Camera/.trashed-1767225600-IMG_0001.jpg" IMG_0001.jpg
```

## Estructura

```
core/   Motor en Java puro (sin dependencias de Android), compilado con --release 8
  Signatures      identifica el tipo de archivo por sus magic bytes
  TrashedName     decodifica .trashed-/.pending- (mismo patrón que AOSP MediaProvider)
  ScanEngine      recorre el almacenamiento y clasifica los hallazgos
  Carver          carving estructural de JPEG/PNG/GIF/WebP (valida marcadores, CRC, bloques)
  Exporter        copia de solo lectura del origen + informe CSV con SHA-256
app/    Interfaz Android
  StorageAccess   permisos y volúmenes (interno, SD, USB OTG)
  MediaIndex      consultas a MediaStore: papelera (IS_TRASHED) e IDs para miniaturas huérfanas
  ScanViewModel   orquesta escaneo y exportación en segundo plano
  RescataScreen   UI en Compose: opciones, progreso, filtros, vistas previas y acciones
```

Tests del motor (se ejecutan en el PC, sin móvil):

```
./gradlew :core:test
```

Cubren la identificación de firmas, el decodificador de la papelera, el carving (extracción
exacta byte a byte, descarte de imágenes truncadas o con CRC incorrecto, firmas que cruzan el
límite del búfer, contenedores de miniaturas antiguos y miniaturas EXIF internas), la
clasificación sobre un árbol de carpetas que imita `/storage/emulated/0` y la exportación con
verificación de hashes.

## Notas

- **Google Play**: `MANAGE_EXTERNAL_STORAGE` está restringido en Play y requiere justificación.
  Para uso personal o distribución directa del APK no hay problema.
- **Android 8 a 10**: usa los permisos clásicos de almacenamiento. En Android 10 se apoya en
  `requestLegacyExternalStorage`.
- **Con root** se podría añadir un modo que lea `/dev/block/by-name/userdata` y haga carving
  sobre todo el volumen, pero el cifrado basado en archivos y el TRIM hacen que en dispositivos
  modernos casi nunca dé resultados. El `Carver` ya acepta cualquier archivo, así que serviría
  igual sobre una imagen volcada.
