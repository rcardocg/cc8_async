# Originales, directorio de trabajo y catálogo local

Revisión documental: 2026-10-06. Arquitectura y decisiones de la solución:
[RFC interno GTP-001](../protocolo.md). Esta guía conserva configuración y uso operativo.

## Configuración multiplataforma (P0)

Se usan dos carpetas separadas, fuera del repositorio para archivos grandes:

| Variable | Propiedad Spring | Valor por defecto | Uso |
|---|---|---|---|
| `GTP_IMAGES` | `images.originals-directory` | `./data/originales` | Originales; no se modifican ni se sirve su contenido completo |
| `GTP_WORK` | `images.directory` | `./data/work` | Catálogo y tiles preparados; debe permitir escritura |
| `GTP_PORT` | `server.port` | `8081` | Puerto HTTP/WebSocket |
| `GTP_INGEST_MEMORY_MIB` | `images.ingest-memory-mib` | `128` | Presupuesto de buffers/margen para P3 HTTP; limitado también por heap máximo |
| `GTP_TILE_CACHE_BYTES` | `tiles.cache-bytes` | `52428800` | Límite de bytes codificados de caché compartida LFU |
| `GTP_TILE_CACHE_TTL_MS` | `tiles.cache-ttl-ms` | `30000` | TTL por inactividad y periodo de envejecimiento de frecuencia |

Al arrancar, `ImagesLayout` crea las carpetas que falten y resuelve sus rutas
canónicas una vez. Comprueba lectura en ambas y escritura efectiva en el work
mediante un archivo temporal que elimina. Un directorio de originales existente
puede ser de solo lectura. Las raíces no pueden coincidir ni estar una dentro de
la otra, tampoco mediante enlaces. Una ruta inválida o no escribible detiene el
arranque con la ruta y la causa en el mensaje.

### Fedora / Bash

```bash
export GTP_IMAGES="$HOME/gpx/originales"
export GTP_WORK="$HOME/gpx/work"
bash scripts/build.sh
bash scripts/gtp.sh
```

### Windows / cmd

```bat
set "GTP_IMAGES=D:\gpx\originales"
set "GTP_WORK=D:\w"
scripts\build.cmd
scripts\gtp.cmd
```

En PowerShell se configuran con `$env:GTP_IMAGES = 'D:/gpx/originales'` y
`$env:GTP_WORK = 'D:/w'`; los mismos archivos `.cmd` pueden ejecutarse allí.
Estas variables duran la sesión de la terminal. Se puede usar `setx` para futuras
terminales, pero no cambia la sesión actual.

Los lanzadores aceptan argumentos, conservan el código de salida y ejecutan desde
la raíz del proyecto, incluso al invocarlos desde otra carpeta. `build` ejecuta las
pruebas y genera `.build/server.jar`; `gtp` ejecuta ese JAR. Java 21 y Maven deben
estar en `PATH`. Las rutas locales de Java del IDE se configuran en las preferencias
del usuario, no en `.vscode/settings.json` compartido.

El equivalente sin lanzadores, desde la raíz, es:

```text
mvn package -Dgigapixel.buildDirectory=.build
java -jar .build/server.jar
```

Con `java -jar` directo, las rutas relativas se interpretan respecto del directorio
actual. Para trabajar desde cualquier carpeta, usar rutas absolutas y entre comillas
cuando contengan espacios. Las propiedades por argumento tienen precedencia:

```text
java -jar .build/server.jar --images.originals-directory="D:/gpx/originales" --images.directory="D:/w" --server.port=8082
```

Opcionalmente, copiar `config/local.properties.example` a `config/local.properties`
y definir `SPRING_CONFIG_IMPORT=optional:file:./config/local.properties`. En Windows
usar `/` en las rutas del archivo `.properties` para evitar escapes. El archivo
local, `/data/`, `/work/` y `/.build/` están ignorados por Git.

### Uso del disco

- Usar disco local (NTFS en Windows, ext4 u otro sistema nativo en Fedora) y work
  corto, por ejemplo `D:/w`. Evitar carpetas sincronizadas para grandes conjuntos
  de tiles. FAT32 no admite originales de más de 4 GiB.
- Probar un dataset por vez. Borrar sus tiles solo después de terminar la prueba;
  conservar siempre los originales. El borrado del work es manual en P0.
- `ImagesLayout.freeBytes()` expone el espacio utilizable. P3 comprueba estimación,
  banda temporal y margen de disco antes y durante el procesamiento.
- Si ya existe un catálogo en `data/images`, configurar `GTP_WORK` con esa ruta o
  mover manualmente catálogo y tiles a la nueva carpeta. No hay migración automática.
- P1 añade inspección/preflight; P3 incorpora generación real para PNG no Adam7 de hasta 8 bits.

## P1 · Selección desde el cliente e inspección PNG

**Corrección de alcance:** los originales del curso son PNG, no ZIP. No se
implementan extracción de archivos ni lectores TIFF/PSB. El objetivo del visor
es navegar normalmente con pan y zoom y refinar las regiones visibles. Para
lograrlo con originales gigantes, primero se generan tiles y niveles con buffers
de memoria acotados. La navegación multinivel está integrada para imágenes `ready`;
queda validar originales gigantes, RAM total y legibilidad en el entorno del curso.

El CLI se despacha antes de Spring. `inspect`, `ladder` e `ingest --dry-run` no crean
directorios ni tiles. La inspección lee firma e IHDR (33 bytes), verifica CRC de IHDR, dimensiones,
profundidad/color y método de entrelazado. Detecta PNG por firma aunque la extensión
sea distinta. **No verifica IDAT/IEND ni demuestra que todo el PNG sea decodificable.**

### Abrir desde el navegador, sin rutas del proyecto

Arrancar el servidor y abrir `http://localhost:8081/`. En **Abre tu PNG**,
elegir un archivo mediante el explorador del sistema. Puede estar en cualquier
carpeta, sin moverlo al repositorio ni configurar su ruta. El navegador usa
`file.slice(0, 33)` y envía la cabecera a `POST /api/png/inspect`. Servidor y CLI
comparten el mismo validador y cálculo de pirámide.

El servidor recibe el nombre y tamaño declarado por el navegador, no la ruta
real ni el permiso de leer ese archivo desde Java. Solo se transmite la cabecera;
no se sube, guarda ni registra el original en el catálogo. El campo `path` del
informe HTTP es el nombre suministrado, mientras que en CLI es una ruta de disco.
El endpoint limita el cuerpo a 33 bytes (413 si excede), verifica IHDR y devuelve
400 ante una cabecera inválida. No comprueba el tamaño real del archivo remoto.

La interfaz muestra dimensiones, color, entrelazado, tiles y estimaciones de
memoria/disco; permite recalcular con tiles de 256/512 y descargar el JSON.
Cerrar o cambiar archivo libera la vista previa y descarta respuestas antiguas.

La **vista previa local** solo se decodifica si el PNG tiene como máximo
4 megapíxeles y el archivo ocupa como máximo 16 MiB. Incluye ajustar, 1:1, zoom
y navegación por arrastre/desplazamiento. No circula por GTP y no valida el
preprocesador. En originales grandes se omite la vista para evitar abrirlos
completos en RAM. La inspección de cabecera sigue disponible.

Seleccionar el original solo lo inspecciona. **Registrar imagen** persiste metadata;
**Transferir y preparar imagen** genera la pirámide. Cuando está `ready`, **Tu imagen**
permite navegarla con canvas, pan/zoom y respaldo. Demo/catálogo plano quedan en
Diagnóstico, sin segmento público «Visor de tiles preparados».
Ver [registro_p2.md](registro_p2.md), [ingesta_p3.md](ingesta_p3.md) y [visor_p6.md](visor_p6.md).

### CLI para inventario y preflight

```text
java -Xmx128m -jar .build/server.jar cli inspect "ruta/imagen.png" --pretty
java -Xmx128m -jar .build/server.jar cli ladder "ruta/originales" --pretty
java -Xmx128m -jar .build/server.jar cli ingest "ruta/imagen.png" --dry-run --work "ruta/work" --pretty
java -jar .build/server.jar cli help
```

También funcionan `bash scripts/gtp.sh cli ...` y `scripts\gtp.cmd cli ...`.
Para imponer `-Xmx`, usar Java directamente como arriba; los argumentos del
lanzador se pasan al programa, no a la JVM.

- `inspect`: JSON con tamaño del archivo, dimensiones, canales/profundidad,
  entrelazado, RAM de una decodificación completa RGBA8, tamaño de fila y mínimo
  de dos buffers de filas. Incluye niveles, total de tiles y espacio estimado.
- `ladder`: inspecciona archivos del primer nivel de la carpeta (sin recursión),
  los ordena por tamaño y conserva errores por archivo. Incluye también archivos
  con extensión equivocada; otros formatos se reportan como errores.
- `ingest --dry-run`: inspección + espacio libre en el work o su ancestro existente.
  Si work falta, no lo crea. Rechaza originales dentro del work descartable.
  Acepta `--image-id ID`, `--tile-size 64..512`, `--max-memory-mib N` (256 por
  defecto) y `--work RUTA` (por defecto `GTP_WORK` o `./data/work`).
- El CLI no carga `config/local.properties`; para preflight usar `GTP_WORK` o
  `--work`. El servidor sí admite la configuración Spring documentada arriba.
- `ingest` sin `--dry-run` ejecuta P3; requiere `--image-id`, fuente y work separados.
  Procedimientos equivalentes en Linux/Windows: [ingesta_p3.md](ingesta_p3.md).

### Interpretar el preflight

| `status` | Salida | Significado |
|---|---:|---|
| `preflight_ok` | 0 | Cabecera y estimaciones pasan; no prueba ingesta ni lectura completa |
| `insufficient_disk` | 2 | Espacio libre menor que la estimación |
| `memory_review` | 1 | Dos filas empaquetadas + fila RGBA8 + margen de 32 MiB exceden el presupuesto efectivo |
| `interlace_review` | 1 | Adam7 detectado; hace falta pipeline adicional de pasadas |
| `depth_review` | 1 | 16 bits detectados; no se reduce precisión silenciosamente |
| `work_not_writable` | 1 | Work o ancestro no escribible |
| `error` | 1 | Archivo, cabecera u opciones inválidos |

Los informes van a stdout; errores generales en JSON a stderr. `ladder` devuelve
1 si algún archivo falla, pero mantiene el informe de los demás. Espacio estimado:
RGBA8 de todos los niveles + 4096 bytes por tile, sin suponer un ratio de compresión.
No es una reserva ni garantía de ocupación: compresión, precisión de 16 bits y
overhead del sistema de archivos deben medirse con los originales reales. Dos filas
son un **mínimo**, no el consumo máximo del decoder, la JVM o una banda de tiles.

### Pipeline PNG P3 inicial

PNG no permite saltar a un tile arbitrario dentro del flujo Deflate. El pipeline
recorre IDAT secuencialmente, invierte filtros con filas anterior/actual y genera
tiles sin retener la imagen completa. **No usa `ImageIO.read(original)`** ni acumula
`ancho × alto` píxeles. La banda se vuelca a disco; los niveles reducidos se generan
desde tiles acotados. Soporta grayscale, paletas, RGB y alpha hasta 8 bits;
Adam7 y 16 bits siguen pendientes. Ver [ingesta_p3.md](ingesta_p3.md).

Pruebas recomendadas: [pruebas_png_p1.md](pruebas_png_p1.md).

## Formato implementado

`images.directory` apunta al directorio de trabajo (por defecto `./data/work`,
relativo al directorio desde donde se inicia Java). No se incluye en Git.

```text
<GTP_WORK>/
  catalog.json
  ejemplo/
    0_0.png
    1_0.png
    0_1.png
    1_1.png
```

`catalog.json`:

```json
[
  {
    "imageId": "ejemplo",
    "width": 300,
    "height": 280,
    "tileSize": 256,
    "totalTiles": 4,
    "maxZoom": null,
    "format": "png"
  }
]
```

Los cuatro tiles de este ejemplo tienen dimensiones 256×256, 44×256, 256×24 y
44×24 respectivamente. Deben ser recortes reales del original sin relleno en los
bordes. Para JPEG se usa `format: "jpeg"` y extensión `.jpeg`.

## Validación

- Identificador de 1–64 caracteres ASCII: letras, números, `_` y `-`.
- `demo_numeros` es reservado; los identificadores no pueden repetirse.
- Dimensiones positivas representables como `int`; tamaño de tile de 64–512 px.
- `totalTiles = ceil(width/tileSize) * ceil(height/tileSize)`, calculado con `long`.
- En el catálogo plano `maxZoom` debe ser `null`. Los registros P2 en `meta.json`
  tienen niveles previstos. P3 publica los niveles completos después de generarlos y validarlos.
- PNG/JPEG; máximo 2 MiB codificados por tile; catálogo máximo 1 MiB.
- Cabecera de imagen, formato y dimensiones verificadas antes de transferir.
- Rutas normalizadas y resueltas para impedir salir del directorio mediante enlaces.

El catálogo plano se carga una vez al iniciar; los registros P2 se refrescan al
consultar catálogo/estado y se validan también al arrancar. Un catálogo inválido detiene el arranque
con una causa explícita. Un archivo de tile ausente o inválido genera `tile_error`
en su solicitud; el resto de tiles puede continuar. No se descarga ningún dato
desde los enlaces externos incluidos en los PDFs durante la ejecución.

## Alcance y siguiente paso

El formato plano permite probar transferencia real sin cargar un original gigante
en RAM y **requiere tiles preparados previamente**. P3 genera una pirámide en su
propia estructura `tiles/{z}/{x}_{y}.png`, ya integrada al transporte/visor P5/P6.
Falta validar el decoder con los PNG gigantes reales del curso.
La validación de cabecera de un tile acota dimensiones; no prueba por sí sola que
todo el archivo sea decodificable. El cliente confirma sólo después de decodificar.

La demo integrada es 4096×4096 con tiles 256×256: genera PNG con coordenadas y números
legibles al vuelo, sirve para verificar el protocolo y está marcada como sintética.
