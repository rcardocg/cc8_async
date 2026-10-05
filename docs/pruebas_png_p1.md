# P1: pruebas con PNG reales

## Desde la interfaz: elegir archivos sin escribir rutas

1. Compilar con `bash scripts/build.sh` (Fedora) o `.\scripts\build.cmd` (Windows).
2. Ejecutar `bash scripts/gtp.sh` o `.\scripts\gtp.cmd` y abrir
   **http://localhost:8081/**.
3. Pulsar **Elegir PNG** y abrir tu PNG pequeño desde cualquier carpeta.
   Debe aparecer **Cabecera válida**, dimensiones, color y estimaciones.
4. Si tiene hasta 4 megapíxeles y 16 MiB, aparece la vista previa local. Probar
   **Ajustar**, **1:1**, **+ / −** y arrastrar para desplazarse con zoom.
5. Cambiar tamaño de tile entre 256 y 512: debe recalcular niveles y total de tiles.
6. Pulsar **Descargar informe JSON**: conservar `p1-inspeccion.json` para compartirlo.
7. Elegir un PNG grande: debe mostrar informe y aviso de vista omitida por tamaño,
   sin intentar mostrar/decodificar el original completo. Revisar RAM en el monitor
   del sistema. La imagen aún no queda registrada para transferencia GTP.
8. Abrir un archivo de prueba inválido: debe indicar error, quitar la vista anterior
   y deshabilitar descarga. Cambiar rápidamente entre archivos: debe quedar el
   informe del último seleccionado. **Cerrar archivo** elimina informe y vista.
9. En DevTools → Network, filtrar `/api/png/inspect`: el cuerpo de cada POST contiene
   **33 bytes** (cabecera binaria). No debe haber un upload completo ni un GET del
   original. El tamaño HTTP total será mayor por URL/cabeceras; no confundirlo con
   el tamaño del cuerpo.
10. En la sección inferior, probar la demo: cargar región, omitir ACK, simular memoria,
    cancelar y reconectar. Esa sección verifica GTP; la vista previa local no.

El navegador no entrega a Java la ruta del archivo seleccionado. La inspección
funciona incluso si el archivo está en una máquina distinta del servidor. El
preprocesado real requerirá integrar una transferencia/lectura por bloques; P1
no copia originales ni escribe tiles.

Prueba automatizada de navegador (herramientas de desarrollo opcionales):

```text
node scripts/verify-browser.cjs .build/server.jar
```

Requiere Playwright y Edge; usar `BROWSER_CHANNEL=chrome` para Chrome o
`BROWSER_EXECUTABLE_PATH` con un Chromium instalado. Estas herramientas no son
dependencias del cliente ni se necesitan para ejecutar el proyecto offline.

## 1. Compilar y verificar el CLI

Fedora:

```bash
bash scripts/build.sh
java -Xmx128m -jar .build/server.jar cli help
```

Windows (PowerShell):

```powershell
.\scripts\build.cmd
java -Xmx128m -jar .build/server.jar cli help
```

Debe terminar y mostrar ayuda sin banner Spring ni puerto abierto. El build
ejecuta pruebas de cabeceras, CRC, límites, pirámide, orden de escalera y preflight,
además de las pruebas existentes del servidor.

## 2. Empezar con el PNG más pequeño

Usar rutas absolutas y comillas. Fedora, por ejemplo:

```bash
export GTP_IMAGES="$HOME/gpx/originales"
export GTP_WORK="$HOME/gpx/work"
java -Xmx128m -jar .build/server.jar cli inspect "$GTP_IMAGES/pequena.png" --pretty
java -Xmx128m -jar .build/server.jar cli ingest "$GTP_IMAGES/pequena.png" --dry-run --pretty
```

Windows / PowerShell:

```powershell
$env:GTP_IMAGES = 'D:/gpx/originales'
$env:GTP_WORK = 'D:/w'
java -Xmx128m -jar .build/server.jar cli inspect "$env:GTP_IMAGES/pequena.png" --pretty
java -Xmx128m -jar .build/server.jar cli ingest "$env:GTP_IMAGES/pequena.png" --dry-run --pretty
$LASTEXITCODE
```

Comprobar:
- `format` es `png`; dimensiones coinciden con las propiedades del archivo.
- `pyramid.maxZoom` es 0 cuando ambos lados son ≤256; no es negativo.
- El último nivel tiene dimensiones originales y sus columnas/filas son redondeadas
  hacia arriba; un borde parcial también ocupa un tile.
- `validation` avisa que solo se verificó firma/IHDR/CRC; `ingestionImplemented` es false.
- No se crean tiles ni carpetas de work inexistentes. La memoria se mantiene baja.
- `preflight_ok` solo aprueba estimaciones; no significa que la imagen ya se pueda
  visualizar en el servidor. No agregar el original al catálogo plano de tiles.

## 3. Inventariar los originales

```text
java -Xmx128m -jar .build/server.jar cli ladder "RUTA_ABSOLUTA_ORIGINALES" --pretty
```

El informe ordena archivos por `sizeBytes` ascendente, sin entrar en subcarpetas.
Mantiene informes válidos aunque algún archivo falle; en ese caso termina con 1.
Guardar la salida **fuera de la carpeta inspeccionada** para que no se inventaríe
el propio reporte como un archivo inválido:

```text
java -Xmx128m -jar .build/server.jar cli ladder "RUTA_ABSOLUTA_ORIGINALES" --pretty > p1-escalera.json
```

## 4. Subir de tamaño, una imagen por vez

Ejecutar `inspect` y `ingest --dry-run` sobre 32 KB → MB → GB → imágenes del curso.
No hay descompresión ni extracción: cada inspección lee 33 bytes del original.
Con `-Xmx128m`, incluso el PNG de 93 GB debe poder inspeccionarse sin cargar su
contenido. Medir consumo con el Administrador de tareas o Monitor del sistema.

En cada imagen revisar:
- `width`, `height`, `bitDepth`, `colorType`, `interlaced`.
- `fullDecodeRgbaBytes`: por qué no podemos abrirla entera en RAM (estimación RGBA8).
- `minimumRowBuffersBytes`: si incluso las filas son demasiado anchas.
- `estimatedWorkBytes` frente a `usableBytes`. Es una estimación sin ratio de
  compresión, no una garantía del uso real de disco.
- Adam7 devuelve `interlace_review`; filas excesivas devuelven `memory_review`.
  Esos resultados sirven para elegir el decoder; no intentar forzar una ingesta.

Comparar tile-size 256 y 512 para el archivo mayor:

```text
java -Xmx128m -jar .build/server.jar cli inspect "RUTA_IMAGEN_GRANDE.png" --tile-size 512 --pretty
```

Debe bajar el número de archivos estimado. No cambia el original ni procesa tiles.

## 5. Casos de error sin tocar los originales

- Ruta inexistente: salida 1 y error JSON en stderr.
- Un archivo de texto de prueba renombrado `.png`: rechazo por cabecera/firma.
- `--tile-size 0`: error de rango.
- `ingest` sin `--dry-run`: rechazo porque aún no hay ingesta.
- Un PNG pequeño con `--max-memory-mib 1`: `memory_review`, salida 1.
- `--work` apuntando a la carpeta que contiene el original: rechazo para impedir
  que quede dentro de la zona descartable.

No hace falta modificar ni truncar las imágenes reales para estas pruebas.

## Qué compartir para continuar

Enviar `p1-escalera.json` y el resultado de `ingest --dry-run --pretty` del PNG
más pequeño y del primero grande. Incluir SO, RAM disponible y espacio libre del
disco de work. Con esos datos se puede implementar y medir el preprocesador
secuencial antes de incorporar pan/zoom al visor.
