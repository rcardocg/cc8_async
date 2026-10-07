# Registro e ingesta PNG — API, estados y almacenamiento

Actualización documental: 2026-10-07. Integra el contrato de registro P2 y la
ingesta P3 para seguir el recorrido completo en una sola referencia.
Código compartido Linux/Fedora y Windows,
sin dependencias nuevas de producción. La revisión vigente pasó el build/E2E en
Fedora; la evidencia histórica por plataforma está en [verificacion.md](verificacion.md).
La solución completa se especifica en el [RFC interno GTP-001](../protocolo.md).
Los ensayos de los originales gigantes y la última revisión en Windows están pendientes.

## Alcance funcional

- P1 sigue leyendo **33 bytes**; P2 sigue registrando `pending/awaiting_transfer`.
- P3 conecta ese registro con un original bajo `GTP_IMAGES`, o recibe una **copia**
  desde el navegador en bloques de hasta **1 MiB**, almacenada en work.
- Lee el PNG secuencialmente, valida CRC de chunks, filtros, Deflate, IDAT e IEND,
  rechaza truncamientos/datos adicionales y calcula SHA-256 del archivo procesado.
- Produce tiles PNG RGBA8 nativos y niveles reducidos, sin abrir el original con
  ImageIO ni acumular `ancho × alto`. ImageIO solo codifica/lee tiles acotados.
- El progreso se persiste en `meta.json`; una pirámide terminada pasa a `ready`.
- Admite cancelación, reintento desde el principio y reanudación de la **subida**.
   Reanudar Deflate desde una fila intermedia sigue pendiente.

### Por qué se separan inspección, registro e ingesta

La inspección permite estimar el trabajo leyendo solo la cabecera; el registro
asigna una identidad persistente aunque el original aún esté en el navegador.
La ingesta valida el archivo completo y prepara acceso por regiones: PNG/Deflate
es secuencial, de modo que navegar tiles no puede resolverse saltando a un offset
arbitrario del original. Esta separación permite informar errores y reanudar la
subida sin anunciar como utilizable una pirámide incompleta.

El algoritmo y sus alternativas están en el
[RFC, sección 4](../protocolo.md#4-recepción-y-procesamiento-del-original).
Esta guía mantiene el contrato HTTP, la persistencia y los procedimientos.

### Variantes admitidas

| PNG no entrelazado | Profundidades | Transparencia |
|---|---|---|
| Grayscale | 1/2/4/8 | `tRNS` |
| Indexado | 1/2/4/8 | PLTE y `tRNS` |
| RGB | 8 | `tRNS` |
| Grayscale + alpha / RGBA | 8 | Canal alpha |

Los cinco filtros PNG se invierten con las filas actual/anterior. PNG de **16 bits**
y **Adam7** se rechazan antes de iniciar el decoder. Su inspección y registro siguen
disponibles; no se realiza conversión silenciosa de 16 a 8 bits. APNG se rechaza.
La salida nativa conserva los valores de píxel admitidos, no el archivo ni sus chunks
auxiliares: perfiles ICC/gamma/texto no se propagan. La fidelidad de gestión de color
y legibilidad con los originales del curso requiere evaluación específica.

## Registro y metadata (P2)

### API de registro

| Método y ruta | Resultado normal |
|---|---|
| `POST /api/images?imageId=ID&name=archivo.png&sizeBytes=N&tileSize=256` | 202, metadata y `Location: /api/image/ID/status` |
| `GET /api/images` | IDs del catálogo plano y registros; refresca manifests |
| `GET /api/image/ID/metadata` | Dimensiones, estado y niveles previstos/completados |
| `GET /api/image/ID/status` | Estado, progreso, origen y causa de fallo |

El POST lleva `Content-Type: application/octet-stream` y exactamente 33 bytes
(firma/IHDR/CRC). No acepta una ruta del navegador ni el PNG completo. Nombre y
tamaño son declarados por el cliente; el original todavía no queda almacenado.
El registro no inicia un decoder ni un trabajo en segundo plano.

- ID: 1–64 caracteres ASCII, letras, números, `_` y `-`.
- `demo_numeros` está reservado incluso si la demo está deshabilitada.
- 400: parámetros/cabecera inválidos; 409: ID duplicado/reservado o directorio
  existente; 413: cuerpo mayor de 33 bytes; 500: error de persistencia/refresco.
- Consultar un ID desconocido devuelve 404.

### Persistencia y compatibilidad

Se guarda `<GTP_WORK>/<imageId>/meta.json` mediante temporal y rename atómico en
el mismo directorio. El registro no sobrescribe directorios preexistentes ni
genera originales o tiles. El manifest P2 (`schemaVersion=1`) conserva metadata,
origen `browser_header`, nombre, tamaño declarado y cabecera Base64.

Los manifests se validan al arrancar y al refrescar catálogo/estado. Las dimensiones
y niveles se recalculan desde la cabecera; un manifest corrupto hace fallar el
arranque o el refresco explícitamente. Refrescar no carga imágenes completas en RAM.

| Campo al registrar | Valor / significado |
|---|---|
| `state`, `sourceState` | `pending`, `awaiting_transfer` |
| `maxZoom`, `levels`, `totalTiles` | Pirámide prevista por `PyramidMath`; total suma todos los niveles, no archivos existentes |
| `completedLevels`, `availableQualities` | Listas vacías: aún no hay salida publicada |
| `processedTiles`, `currentLevel` | 0 y null |
| `message`, `error` | Explicación de la espera; error vacío salvo fallo real |

P2 permite persistir `failed`; `processing` y `ready` requieren el manifest de
procesamiento P3 (`schemaVersion=2`). Para `ready` se exige además la publicación
completa correspondiente. Un manifest v1 sigue rechazando esos dos estados.
No hay endpoint para simular que una imagen ha terminado.

El catálogo plano `catalog.json` conserva `maxZoom=null`, estado `ready` e identidad
`imageId:x:y`; se carga al arrancar. Los registros `meta.json` se incorporan mediante
refresco sin reiniciar. Su identidad de tile es `imageId:z:x:y:q`; las coordenadas
se validan contra el nivel, incluidos bordes parciales. Una coordenada prevista
válida no prueba disponibilidad: solo registros `ready` pueden servir tiles.
Los campos GTP y la compatibilidad de z/q se definen en el [contrato](protocolo.md).

### Comprobar el registro antes de procesar

1. Elegir un PNG, registrar un ID nuevo y consultar metadata/estado.
2. Comprobar `pending`, listas vacías y `meta.json` sin tiles; la carga del visor
   está deshabilitada hasta `ready`.
3. Repetir el ID: debe rechazarse sin alterar su manifest.
4. Reiniciar con el mismo work: el registro debe seguir presente.
5. Volver a `demo_numeros`: el recorrido GTP de diagnóstico debe seguir disponible.

La ejecución P2 original y sus límites se conservan en la
[bitácora de bootstrap](fases/fase_01_bootstrap.md#2026-10-05--p2-registro-persistente-y-metadata-multinivel).

## Uso desde el navegador (igual en ambos sistemas)

1. Compilar/arrancar y abrir `http://localhost:8081/`.
2. En **Abre tu PNG**, elegir archivo, inspeccionar y **Registrar imagen** con un ID;
   el registro se selecciona automáticamente en **Tu imagen**.
3. Pulsar **Transferir y preparar imagen**. Muestra bytes subidos y después
   estado/progreso de procesamiento. Las peticiones contienen `File.slice`, no un
   buffer de la imagen completa.
4. **Detener** aborta la subida del cliente o solicita interrumpir el decoder.
   Los bloques persistidos se conservan. Un procesamiento cancelado queda `failed`.
5. Para continuar una subida, volver a seleccionar **el mismo archivo** y escribir
    **el mismo ID**; pulsar Transferir y preparar imagen sin registrarlo otra vez.
    El servidor informa el offset.
6. **Reiniciar transferencia** borra únicamente `source.part` de un registro
   no listo/no activo; permite corregir una copia corrupta. No borra el original local.

Tras una recarga, un trabajo activo continúa en Java; su estado se consulta en el
catálogo. Tras reiniciar Java, un `processing` sin propietario activo se marca
`failed` con causa. Se reintenta explícitamente desde el inicio; nunca se declara
`ready` por encontrar archivos incompletos.

Una pirámide `ready` habilita el canvas multinivel: ajustar, pan/zoom y 1:1, con
respaldo nivel 0. No se sirve el original completo ni los tiles por un endpoint
HTTP del visor: viajan por GTP. La demo/catálogo plano quedan en Diagnóstico.
Contrato: [protocolo.md](protocolo.md); recorrido: [visor_p6.md](visor_p6.md).

## Fuente del servidor y CLI

El CLI `ingest` sin `--dry-run` requiere `--image-id`, registra si el ID no existe y
usa el mismo pipeline que HTTP. Un registro pendiente/fallido puede reintentarse;
uno listo no se sobrescribe. La cabecera y el tamaño deben coincidir con P2.

### Fedora / Bash

```bash
export GTP_IMAGES="$HOME/gpx/originales"
export GTP_WORK="$HOME/gpx/work"
export GTP_PORT=8081
bash scripts/build.sh
java -Xmx128m -jar .build/server.jar cli inspect "$GTP_IMAGES/pequena.png" --pretty
java -Xmx128m -jar .build/server.jar cli ingest "$GTP_IMAGES/pequena.png" --dry-run --image-id pequena --max-memory-mib 64 --pretty
java -Xmx128m -jar .build/server.jar cli ingest "$GTP_IMAGES/pequena.png" --image-id pequena --max-memory-mib 64 --pretty
bash scripts/gtp.sh
```

### Windows / PowerShell

```powershell
$env:GTP_IMAGES = 'D:/gpx/originales'
$env:GTP_WORK = 'D:/gpx/work'
$env:GTP_PORT = '8081'
.\scripts\build.cmd
java -Xmx128m -jar .build/server.jar cli inspect "$env:GTP_IMAGES/pequena.png" --pretty
java -Xmx128m -jar .build/server.jar cli ingest "$env:GTP_IMAGES/pequena.png" --dry-run --image-id pequena --max-memory-mib 64 --pretty
java -Xmx128m -jar .build/server.jar cli ingest "$env:GTP_IMAGES/pequena.png" --image-id pequena --max-memory-mib 64 --pretty
.\scripts\gtp.cmd
```

En cmd: `set "GTP_IMAGES=D:\gpx\originales"`, `set "GTP_WORK=D:\gpx\work"`, y
`scripts\gtp.cmd cli ingest "%GTP_IMAGES%\pequena.png" --image-id pequena --pretty`.
Los lanzadores interpretan rutas relativas desde la raíz del proyecto. Java directo
las interpreta desde la carpeta actual. Usar comillas para rutas con espacios.

Sin `GTP_IMAGES`, el CLI real usa la carpeta padre del archivo como raíz de originales;
debe estar separada del work. El CLI no carga propiedades Spring. `--dry-run` conserva
su garantía de no escribir; `ingestionImplemented` indica si la variante tiene decoder.

## Contrato HTTP P3

Rutas relativas a `/api/image/{id}`:

| Método/ruta | Cuerpo / parámetros | Resultado |
|---|---|---|
| `GET /upload` | — | 200: `receivedBytes`, `declaredSizeBytes`, `maxChunkBytes` |
| `PUT /upload?offset=N` | `application/octet-stream`, 1..1048576 bytes | 200 con offset persistido; exige offset exacto |
| `DELETE /upload` | — | 204: descarta copia de subida pendiente/fallida |
| `POST /ingest` | Sin cuerpo | 202 y `Location` de status; procesa la copia subida completa |
| `POST /ingest?sourceName=pequena.png` | Sin cuerpo | 202; lee un nombre relativo bajo `GTP_IMAGES` |
| `POST /ingest/cancel` | Sin cuerpo | 202: interrupción solicitada en este proceso |
| `GET /status` | — | Estado P2/P3 persistido |

- 400: offset negativo/bloque inválido, cabecera/tamaño incompatibles, ruta no válida.
- 404: ID no registrado en P2.
- 409: offset desactualizado, ingesta/escritura concurrente, registro listo/activo o cancelación sin trabajo activo.
- 413: bloque mayor de 1 MiB (también con longitud de cuerpo desconocida).
- 422: fallo de E/S/preflight, fuente inexistente, variante no soportada o disco/memoria insuficientes.
- Después del 202, los errores del decoder se consultan en `/status` (`failed`, causa).

El primer bloque debe contener y coincidir con los 33 bytes de P2. El offset procede
del tamaño real en disco; cada bloque exitoso fuerza su escritura antes de responder.
Ante pérdida de respuesta, consultar `/upload` en lugar de repetir a ciegas. El tamaño
declarado limita la subida; el PNG completo solo se valida al procesarlo.

Nombre/cabecera/tamaño iguales **no prueban identidad de contenido**. SHA-256 registra
los bytes realmente procesados en el servidor; aún no se compara con un hash completo
calculado por el cliente. No mezclar archivos durante una reanudación; reiniciar la
subida o usar otro ID si se cambia de original.

## Memoria, disco y publicación

```text
<GTP_IMAGES>/pequena.png           original local del servidor: solo lectura
<GTP_WORK>/.p3.lock               exclusión de escritura/ingesta entre procesos
<GTP_WORK>/<id>/
  meta.json                      schemaVersion 1 (P2) o 2 (procesamiento P3)
  source.part                    copia comprimida subida, si procede
  .p3-tiles/                     staging privado de un intento (se elimina al fallar)
    band.rgba                    banda temporal EN DISCO
  tiles/                         rename atómico de staging tras éxito
    {z}/{x}_{y}.png
    complete.sha256              comprobante del procesamiento terminado
```

- RAM del decoder: dos filas empaquetadas + una RGBA8; el generador retiene una
  imagen de tile nativo o hasta cuatro tiles hijos + uno reducido. La banda completa
  ocupa disco, no RAM. No se acumulan listas con todas las rutas de tiles.
- Presupuesto HTTP: `GTP_INGEST_MEMORY_MIB` / `images.ingest-memory-mib`, 128 MiB por
  defecto. CLI: `--max-memory-mib`, 256 MiB por defecto. Ambos se limitan por el heap
  máximo de la JVM y exigen margen de 32 MiB sobre las filas. Es un preflight de buffers,
  no un límite de RSS ni de todo Spring, WebSocket o los buffers HTTP.
- Disco preflight: estimación RGBA8 de pirámide + 4096 bytes/tile + una banda de hasta
  `ancho × tileSize × 4` + reserva de 16 MiB. Se comprueba espacio también al escribir.
  La copia subida ya ocupa espacio adicional al comenzar el decoder. No es una reserva
  atómica de disco; un error posterior queda `failed`.
- Una ingesta activa por work, mediante lock de archivo (también CLI frente a servidor).
  Las escrituras de subida usan el mismo lock; se rechazan mientras se procesa.
- `processedTiles` cuenta salida provisional; `completedLevels=[]` y
  `availableQualities=[]` mientras se procesa. Se publica la **pirámide completa** de una
  vez tras validar integridad y terminar todos los niveles; no se expone salida parcial.
- La reducción usa promedio 2×2 con alpha premultiplicado, incluyendo bordes impares.
  q3 es la única calidad publicada en este incremento; q0/q1/q2 corresponden a P4.
- La copia `source.part` se conserva después de éxito para auditoría; es descartable
  junto al work. Nunca es la única copia original del usuario. Su limpieza automática
  y cuotas globales por conjunto quedan pendientes.

## Pruebas reproducibles en ambos ambientes

```text
node scripts/verify-ingestion.cjs .build/server.jar
node scripts/verify-browser.cjs .build/server.jar
```

El primer script usa solo Node estándar, genera PNG sintético completo por filas y
ejecuta CLI con `-Xmx64m`. Comprueba SHA-256, bordes/niveles y persistencia de registros
`pending`, `ready` y offset de subida entre dos procesos Java. Limpia su directorio temporal.

El segundo requiere Playwright instalado como herramienta de desarrollo y un navegador:

```bash
NODE_PATH="/ruta/herramientas/node_modules" BROWSER_EXECUTABLE_PATH="/usr/bin/chromium" node scripts/verify-browser.cjs .build/server.jar
```

```powershell
$env:NODE_PATH = 'C:/herramientas/node_modules'
$env:BROWSER_CHANNEL = 'msedge'
node scripts/verify-browser.cjs .build/server.jar
```

Usar la ruta real del navegador en Fedora; también se admite `BROWSER_CHANNEL=chrome`.
No se instala ninguna herramienta ni dependencia al arrancar la aplicación.

## Límites pendientes de evaluación

Inventariar/procesar originales del curso; repetir la revisión vigente en Windows;
medir RSS, disco máximo y rendimiento en ambos equipos; verificar lectura de originales
de solo lectura; ampliar 16 bits/Adam7 si lo requieren los datos; validar gestión de
color y `image_Indicators.pdf`; mejorar checkpoints/reanudación de procesamiento y
recuperación tras caída durante publicación. P3 no acredita aún imágenes de 17–93 GB.
