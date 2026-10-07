# GTP/1 — Protocolo de control de transferencia de tiles

Fecha: 2026-10-06. Este contrato describe el código implementado, no la propuesta
histórica. GTP significa «Gigapixel Tile Protocol» dentro de este proyecto; no se
presenta como estándar registrado.

**Documento principal:** [RFC interno GTP-001](../protocolo.md). Este archivo es
su contrato operativo de consulta: nombres exactos, unidades, validaciones y
efectos sobre el estado. La arquitectura, justificación y evidencia se desarrollan
en el RFC. No debe interpretarse el diseño histórico como otro contrato vigente.

## Transporte y bootstrap

1. El servidor Java entrega HTML/CSS/JS mediante HTTP, desde `/`.
2. `GET /api/images` devuelve un arreglo de identificadores del catálogo.
3. `GET /api/image/{id}/metadata` devuelve `imageId`, `width`, `height`, `tileSize`,
   `totalTiles`, `maxZoom`, `format`, `state`, `levels`, `completedLevels` y
   `availableQualities`. Imagen inexistente: HTTP 404. Niveles previstos no implican tiles disponibles.
4. El cliente abre `/ws/tiles` en el mismo origen (`ws` o `wss`).
5. El servidor responde `ready` con versión y límites; entonces se aceptan solicitudes.

P1 añade `POST /api/png/inspect?name=archivo.png&sizeBytes=N&tileSize=256` para
selección local desde el cliente. Cuerpo `application/octet-stream` de exactamente
33 bytes (firma/IHDR/CRC); respuesta JSON con dimensiones y estimaciones. HTTP
400 ante cabecera/parámetros inválidos; 413 si el cuerpo excede el límite. El
tamaño del archivo es declarado por el cliente, no verificado contra un original
almacenado. La respuesta no se cachea. Este endpoint no ingiere archivos, no
modifica el catálogo y no inicia transferencias GTP. La vista previa local de PNG
pequeños tampoco usa WebSocket. Procedimiento en [pruebas_png_p1.md](pruebas_png_p1.md).

P2 añade `POST /api/images` con cabecera binaria de 33 bytes y parámetros
`imageId`, `name`, `sizeBytes`, `tileSize`. Devuelve HTTP 202, metadata pendiente
y `Location` del estado. Duplicados/reservados: 409; parámetros/cabecera inválidos:
400; cuerpo excesivo: 413. Persiste `meta.json` sin transferir el original completo.
`GET /api/image/{id}/status` devuelve progreso y `sourceState`. Un registro pendiente
no puede solicitar tiles. Contrato y pruebas en [registro_p2.md](registro_p2.md).

P3 añade transferencia del original **hacia Java**, distinta de la entrega de tiles
al visor: `GET/PUT/DELETE /api/image/{id}/upload`, bloques de hasta 1 MiB y offset
persistido; `POST /api/image/{id}/ingest` (202) y `/ingest/cancel`. La fuente puede
ser la copia subida o un nombre relativo a `GTP_IMAGES`. Procesa PNG no Adam7 de
hasta 8 bits, genera pirámides en disco y publica `ready` tras éxito completo.
Contrato, errores y reintentos: [ingesta_p3.md](ingesta_p3.md).
Las imágenes P3 `ready` admiten solicitudes multinivel con `z` y `q`; el visor
solicita PNG q3 del nivel elegido según zoom/densidad de pantalla. Véase
[renderizado_p5.md](renderizado_p5.md) para pruebas y límites del primer incremento.

Mensajes JSON en frames de texto. Los clientes deben enviar `version: 1` y `action`;
por compatibilidad el servidor asume versión 1 si el campo se omite.
Las cadenas identificadoras de mensajes deben ser no vacías, hasta 128 caracteres.
El servidor limita mensajes entrantes a 32 KiB y lotes a 128 entradas, antes de
eliminar duplicados. Campos numéricos de coordenadas deben ser enteros, no strings
ni decimales. Catálogo plano/demo: `z` omitido/null y `q` omitido. Imágenes multinivel:
`z=0..maxZoom` (omitido: 0), `q=0..3` (omitido: 3); identidad `imageId:z:x:y:q`.
Aunque la clase activa se llama `TileWebSocketHandlerV2`, el contrato es **GTP/1,
version:1**; otras versiones se rechazan, no se anuncia GTP/2 sin implementarlo.

## Solicitudes del cliente

### Solicitar una región

```json
{
  "version": 1,
  "action": "fetch_tiles",
  "request_id": "view-1",
  "imageId": "demo_numeros",
  "tiles": [{"x": 0, "y": 0, "z": null}, {"x": 1, "y": 0, "z": null}],
  "replace": true
}
```

Ejemplo multinivel con fragmentación opcional:

```json
{"version":1,"action":"fetch_tiles","request_id":"r17","imageId":"imagen",
 "tiles":[{"z":5,"x":10,"y":7,"q":3}],"replace":true,
 "priority":"viewport","transfer_window_bytes":1500}
```

Los ejemplos requieren metadata donde esas coordenadas sean válidas; tamaños y
textos Base64 son ilustrativos, no payloads PNG ejecutables.

| Campo | Tipo/unidad | Regla |
|---|---|---|
| `request_id` | String | Obligatorio, no vacío, hasta 128 caracteres. |
| `imageId` | String | Obligatorio; imagen conocida en estado `ready`. |
| `tiles` | Array | De 1 a 128 entradas, antes de consolidar duplicados. |
| `x`, `y` | Enteros de 32 bits | Índices no negativos dentro de filas/columnas del nivel. |
| `z` | Entero | Multinivel: 0..maxZoom; omitido/null toma 0. Plano: omitido/null. |
| `q` | Entero | Multinivel: 0..3; omitido/null toma 3. Plano: omitido/null. |
| `replace` | Boolean | Predeterminado false; sustituye solo tras validar todo el lote. |
| `priority` | String | Predeterminado `viewport`; otras cadenas usan orden de inserción. |
| `transfer_window_bytes` | Entero, bytes | 0/omitido: tile entero; 256..65536: fragmentos. |

- `request_id` correlaciona una región; el cliente debe generar un valor nuevo por
  solicitud. El servidor rechaza reutilizar inmediatamente el último valor.
- Hay una sola solicitud activa por sesión. Sin `replace: true`, una nueva solicitud
  mientras otra está activa genera `request_busy`.
- La solicitud se valida completa **antes** de cancelar la anterior. Coordenadas
  inválidas o imagen inexistente nunca alteran trabajo ya aceptado.
- Tiles duplicados se consolidan preservando la primera aparición; no es una
  política de reemplazo de caché. Con `priority:viewport`, el envío se selecciona
  por proximidad al centro promedio de tiles no confirmados/cancelados/fallidos,
  incluyendo los en vuelo; con otra cadena se conserva orden de inserción.
- Sustituir una región emite `request_cancelled`, libera pendientes y datos retenidos
  de la anterior y acepta la nueva. Datos ya enviados pueden llegar tarde: el cliente
  los descarta por `request_id`.
- No existen acciones `metrics` ni `viewport_update`; se reciben estadísticas en
  `transfer_state` y la vista se actualiza mediante fetch/cancelación parcial.
- `priority`, `gesture`, `ack_batch` y `cancel_tiles` están procesados en el
  handler. El visor usa prioridad predeterminada y cancelación parcial; no utiliza
  `gesture` ni `ack_batch`, ni un motor automático de prefetch/calidad adaptativa.

### Confirmar consumo de un tile

```json
{
  "version": 1,
  "action": "ack_tile",
  "request_id": "view-1",
  "transfer_id": "1",
  "tile_id": "demo_numeros:0:0"
}
```

`transfer_id` es un contador enviado como string, único en la sesión y preservado
en retransmisiones; `tile_id` incluye la imagen y coordenadas. Se validan los tres
identificadores. Un ACK duplicado, antiguo o de otro tile produce `ack_ignored`:
no libera memoria ni aumenta la ventana. El ACK se envía tras decodificar y
procesar el tile; el renderizado Canvas puede ocurrir en el siguiente frame.
Si el duplicado ya está decodificado, se confirma sin decodificarlo de nuevo.
No garantiza retención permanente: la vista/TTL pueden cambiar su utilidad.

`latency_ms` del prototipo antiguo no se utiliza. La medición la hace el servidor
con reloj monotónico entre envío y ACK de aplicación. Incluye latencia de red,
planificación y procesamiento del cliente; no equivale al RTT TCP puro. En modo
fragmentado `sentAt` se renueva con cada fragmento: el RTT final va desde el último
fragmento enviado hasta `ack_tile`, no abarca toda la transferencia del tile.
`decode_ms` es opcional y leído por el handler, pero no altera sus cálculos.

### Confirmar varios tiles: extensión del servidor

```json
{"version":1,"action":"ack_batch","request_id":"r17","acks":[
 {"transfer_id":"204","tile_id":"imagen:5:10:7:3"},
 {"transfer_id":"205","tile_id":"imagen:5:11:7:3"}]}
```

`acks` debe ser array (puede ser vacío); no tiene un límite independiente de
entradas adicional al tamaño del mensaje. Cada ACK se valida por transferencia y
tile, y emite su estado/`ack_ignored`. `request_id` incorrecto produce
`invalid_request`. Se procesan secuencialmente, no como transacción atómica:
un campo inválido posterior no revierte los ACK ya aplicados. No es SACK por
rangos/secuencias y no lo envía el visor actual.

### Memoria y cancelación

```json
{"version":1,"action":"memory_pressure","current_usage":90,"memory_limit":100}
```

Ambos valores son enteros no negativos en bytes; `memory_limit` debe ser mayor que
cero. La política utiliza la razón entre ellos. Uso >=80%: ventana del receptor 2;
>=60%: 4; menor: 32. Entrar a >=80% desde una ventana superior también reduce `cwnd`.
El cliente de diagnóstico envía cantidades simuladas y así lo indica en pantalla.
El visor normal informa suma estimada RGBA de bitmaps retenidos y un presupuesto
receptor de `max(1 MiB, 32 MiB - bytes_de_bitmaps)`. `receiver_window_bytes`
omitido restablece el presupuesto predeterminado de 32 MiB; no es consumo.

```json
{"version":1,"action":"cancel_request","request_id":"view-1"}
```

Cancela sólo la solicitud activa coincidente. Una solicitud ya terminada o desconocida
produce `cancel_ignored`. Cerrar la conexión elimina todo el estado de esa sesión.
Reconectar crea una sesión nueva y requiere solicitar nuevamente la región actual.

### Cancelar tiles concretos

```json
{"version":1,"action":"cancel_tiles","request_id":"r17",
 "tiles":[{"tile_id":"imagen:5:10:7:3"}]}
```

Solicitud debe coincidir con la activa; `tiles` es un array que puede estar vacío.
Se cancelan tiles del scoreboard que no están confirmados, fallidos o cancelados.
Se libera cola, payloads y contadores en vuelo; respuesta `cancel_tiles_accepted`.
Una identidad válida que no pertenece al lote o ya terminó no altera contadores.
IDs malformados producen `invalid_request`; como en ACK batch, el recorrido no es
una transacción que revierta cancelaciones anteriores. No reduce `cwnd` por sí sola.

### Sugerencia de calidad según gesto: extensión del servidor

```json
{"version":1,"action":"gesture","type":"pan","velocity_px_s":850,
 "zoom_delta":0,"fps":42}
```

`type` es texto obligatorio; `velocity_px_s` debe ser numérico no negativo.
`zoom_delta` predetermina 0 y `fps` 30. Las reglas, en orden, son:

| Condición | Calidad objetivo |
|---|---:|
| `type=pan` y velocidad >800 o, dentro de pan, FPS<30 | 0 |
| `type=pan` y velocidad >200 | 1 |
| `abs(zoom_delta)>0.5` | 2 |
| Resto | 3 |

Se envía `quality_adjustment` solo si cambia el objetivo anterior (inicia en 3).
Motivo: `fast_pan` para q0, `slow_pan` para q1 y `idle_refine` para q2/q3. Este
último nombre no implica temporizador de reposo ni refinamiento automático.
No cambia el `q` solicitado ni impone una calidad máxima; el visor no integra
esa acción y continúa pidiendo q3 del nivel adecuado.

## Mensajes del servidor

| Acción | Campos principales y significado |
|---|---|
| `ready` | `protocol:"GTP/1"`, `supports:["GTP/1"]`, `max_batch:128`, `max_window:32`, `initial_window:2`, `out_of_view_ttl_ms:5000`, `max_in_flight_bytes:4194304` |
| `request_accepted` | `request_id`, `total` de tiles únicos |
| `tile_data` | Bytes reales del tile codificado, con coordenadas, q, formato y longitud; ejemplo debajo. |
| `tile_fragment` | Mismos identificadores del tile, más offset, longitud parcial y attempt; fragmentación opcional. |
| `transfer_state` | `request_id`, `cwnd`, `receiver_window`, `receiver_window_bytes`, `decoded_in_flight_bytes`, `fragment_in_flight_bytes`, `transfer_window_bytes`, `in_flight`, `pending`, `in_flight_bytes`, `rtt_ms`, `rto_ms`, `target_quality`; tras ACK de tile válido y antes de nuevos envíos. |
| `adjust_strategy` | `receiver_window`, `max_tiles_concurrent`, `reduce_prefetch` (uso >=60%), `decrease_quality` (uso >=80%); directivas, no cambios automáticos de codificación. |
| `quality_adjustment` | `q`, `reason`; sugerencia del handler cuando cambia objetivo de calidad. |
| `ack_ignored` | `request_id`, `transfer_id`; no se reconoce como ACK vigente |
| `request_cancelled` / `cancel_ignored` | `request_id` |
| `cancel_tiles_accepted` | `request_id`; cancelación parcial procesada. |
| `tile_error` | `request_id`, `tile_id`, `code`, `message`; `transfer_id` cuando hay timeout |
| `request_complete` | `request_id`, `total`, `acknowledged`, `failed`, `cancelled`; indica fin, no éxito universal. |
| `error` | `code`, `message`, `request_id` si está disponible |

```json
{
  "version": 1,
  "action": "tile_data",
  "request_id": "view-1",
  "transfer_id": "1",
  "tile_id": "demo_numeros:0:0",
  "imageId": "demo_numeros",
  "x": 0,
  "y": 0,
  "z": null,
  "q": 3,
  "compression": "png",
  "size_bytes": 1234,
  "attempt": 1,
  "data": "BASE64_DEL_ARCHIVO"
}
```

`1234` y el texto Base64 son ilustrativos. `size_bytes` es el tamaño del archivo
comprimido, no del frame JSON; Base64 añade aproximadamente un tercio de tamaño.
Se conserva JSON por trazabilidad inicial; frames binarios quedan para optimización.
`compression` es PNG para q0/q3 y JPEG para q1/q2 multinivel; catálogo plano usa su
formato original. Las variantes q0–q2 se generan bajo demanda; el visor usa q3.
`reduce_prefetch` es una directiva: no implica que exista un motor de prefetch.
El campo `q` también aparece en `tile_data` (3 en catálogo plano). La rama
residual `tile_abandoned` contiene un motivo histórico GTP-RA, pero la función
vigente de recuperación no calcula utilidad: reenvía tiles no confirmados ni
cancelados hasta el límite. No se documenta esa cadena como algoritmo GTP-RA probado.

| Código | Ámbito | Significado |
|---|---|---|
| `invalid_request` | Solicitud | JSON/acción/campos/coordenadas inválidos, imagen no lista o solicitud de batch/cancelación parcial no vigente. |
| `unknown_image` | Solicitud | Imagen no encontrada durante manejo de solicitud. |
| `request_busy` | Solicitud | Otro lote activo sin `replace:true`. |
| `tile_unavailable` | Tile | Lectura fallida, ausente o tamaño/formato rechazado por servicio. |
| `receiver_budget_too_small` | Tile | RGBA estimado del tile supera presupuesto receptor. |
| `ack_timeout` | Tile | Venció espera después del máximo de cuatro intentos. |

`ack_ignored`/`cancel_ignored` son acciones de respuesta, no códigos de error.
JSON inválido produce respuesta controlada; errores de transporte o fallos de
ejecución no recuperables cierran la sesión y liberan recursos. Al exceder 64
conexiones se cierra con `SERVICE_OVERLOAD`, sin aceptar estado adicional.

## Ventana y recuperación selectiva

Estado por sesión: cola con prioridad por proximidad al centro de tiles pendientes,
mapa de transferencias sin ACK, bytes retenidos,
solicitud activa, contadores de éxito/fallo, `cwnd`, `ssthresh`, RTT suavizado y RTO.

- Inicio: `cwnd=2`, `ssthresh=16`, ventana del receptor=32, RTO=1000 ms.
- ACK válido sin retransmisiones: si RTT > `max(200ms, 2*SRTT anterior)`, reducir
  ventana; en otro caso, sumar 1 durante slow start o `1/cwnd` en avoidance.
- `cwnd` máximo 32. El incremento fraccional evita el crecimiento excesivo del
  prototipo antiguo. Es una adaptación a tiles, no conformidad con TCP.
- Reducción: `ssthresh=max(2,cwnd/2)` y `cwnd=ssthresh`.
- `SRTT = primer RTT`, después `0.875*SRTT + 0.125*RTT`.
- `RTTVAR = primer RTT/2`, después `0.75*RTTVAR + 0.25*abs(SRTT anterior-RTT)`.
- RTO base: `clamp(SRTT + 4*RTTVAR, 1000ms, 30000ms)`.
  Se usa aritmética/truncamiento entero; el valor cero inicializa estimadores.
- Tras retransmisión no se mide RTT ni se incrementa ventana con ese ACK (Karn).
- El planificador revisa cada 250 ms. Sólo se retransmiten entradas vencidas sin ACK,
  con mismo `transfer_id` y bytes. Cada timeout duplica su intervalo, máximo 30 s.
- Máximo cuatro envíos por transferencia (original + tres reintentos). Tras expirar
  el cuarto, se libera y emite `tile_error: ack_timeout`; la solicitud termina con
  contadores explícitos. Con RTO inicial de 1 s: envíos a ~0/1/3/7 s y fallo a ~15 s.
- Una ronda con uno o más timeouts reduce ventana una vez. Las retransmisiones
  corresponden a posiciones ya ocupadas, no crean nuevos slots.

Ventana efectiva = `min(floor(cwnd), receiver_window)`. Después de reducirla puede
haber más tiles en vuelo que el nuevo límite: los enviados no se pueden retirar;
se detienen nuevos envíos hasta liberar suficientes posiciones.

Cada sesión retiene como máximo 4 MiB de payload comprimido sin ACK, además de las
estructuras y buffers transitorios. Antes de leer otro tile se reserva margen de
2 MiB (tile PNG de 512×512 poco compresible). Se admiten hasta 64 conexiones. Esta es una
cota de payloads, no de todo el heap ni de buffers internos del contenedor.

El presupuesto de decodificación `receiver_window_bytes` es independiente del
consumo `decoded_in_flight_bytes`. Se calcula RGBA8 con dimensiones reales del
tile/nivel (q0: 64×64), nunca a partir del ratio de compresión. Por defecto es
32 MiB. `memory_pressure` puede establecer un presupuesto positivo; un tile mayor
se marca `receiver_budget_too_small` y la solicitud termina sin quedar bloqueada.
El cliente multinivel comunica bytes de bitmaps estimados, no lectura del heap.

`cancel_tiles` recibe `request_id` y `tiles:[{"tile_id":"..."}]`; elimina tanto
cola como transferencias en vuelo, sin reducir congestión por cancelación.
El resumen incluye `cancelled`; un ACK tardío se ignora.

### Fragmentación opcional de aplicación

En `fetch_tiles`, `transfer_window_bytes: 0` u omitido conserva `tile_data` entero.
Un valor de **256 a 65536 bytes** activa fragmentos y limita la suma de payloads
de fragmentos pendientes de ACK en la sesión. El panel permite 1500 o 16000 bytes.
Este valor **no es la MTU** y no cuenta JSON/Base64/cabeceras de red.

```json
{"version":1,"action":"tile_fragment","request_id":"r1","transfer_id":"7",
 "tile_id":"img:3:2:1:3","imageId":"img","x":2,"y":1,"z":3,"q":3,
 "compression":"png","size_bytes":5000,"offset":0,"fragment_bytes":1500,
 "attempt":1,"data":"BASE64_FRAGMENTO"}
```

El cliente almacena bytes en offsets y confirma la siguiente posición:

```json
{"version":1,"action":"ack_fragment","request_id":"r1","transfer_id":"7",
 "tile_id":"img:3:2:1:3","attempt":1,"offset":1500}
```

El ACK válido libera capacidad; el servidor continúa hasta la longitud total.
Tras reconstruir y decodificar se envía el `ack_tile` habitual. Un ACK de tile
antes de confirmar todos los fragmentos se ignora. Duplicados/falsos/tardíos no
liberan capacidad. Un timeout reinicia el tile completo con `attempt+1` y backoff;
no hay recuperación selectiva por fragmento en este incremento. Cancelar libera
los buffers y evita reenvíos. El cliente acota reconstrucción a 4 MiB en conjunto.
Hay como máximo un fragmento pendiente por transferencia; el crédito libre se
comparte entre transfers. El RTO se controla desde el último fragmento enviado;
si el tile preparado espera crédito sin fragmento pendiente, se espera, no se
interpreta ese fragmento aún no enviado como pérdida.

### Retención y TTL

- `ready.out_of_view_ttl_ms=5000`: el cliente inicia TTL al salir de vista/cambiar
  nivel, protege tiles visibles **y el tile de nivel 0**, solicitado primero como
  respaldo permanente para la imagen actual. Cierra `ImageBitmap` al expulsar.
  Máximo de bitmaps 32 MiB; reserva antes de insertar y expulsión espacial de no
  visibles, sin LRU/FIFO. Cambio de imagen/cancelación libera también el respaldo.
- Servidor: LFU con envejecimiento, límite 50 MiB y TTL por inactividad 30 s.
  Barrido cada segundo; envejecimiento divide frecuencias por dos, mínimo 1,
  cada intervalo TTL. Empates por clave. GET renueva TTL e incrementa frecuencia.
- `/api/cache/stats` incorpora `policy:LFU_AGING_TTL` y `ttlMs`.
- TTL es retención; RTO es espera de confirmación. No son el mismo temporizador.

La cola de vistas del cliente conserva tiles útiles en vuelo y usa `cancel_tiles`
para los que salen de alcance. Durante gestos se envía la última vista cada 120 ms;
al cerrar el lote se solicita su detalle pendiente. El progreso representa tiles
visibles realmente decodificados. Navegación: [visor_p6.md](visor_p6.md).
Experimentos reproducibles y definiciones de métricas: [experimentos_p7.md](experimentos_p7.md).

La E/S de tiles y envío se despacha en hilos virtuales Java 21, con una tarea de
transmisión por sesión. Un `ReentrantLock` serializa el estado y los envíos de cada
cliente sin monitor `synchronized` alrededor de la E/S. El temporizador global
despacha revisiones sin esperar la E/S de una sesión bloqueada.

## Qué recupera este protocolo

WebSocket/TCP ya proporcionan entrega ordenada y recuperación de paquetes. Estos
ACKs y reintentos gestionan **consumo de datos por la aplicación**, falta de ACK,
clientes lentos, cambios de región y límites de memoria. Omitir un ACK en el panel
simula un fallo de aplicación; no simula pérdida física de paquetes TCP. No hay
SACK por rangos, Go-Back-N, reanudación persistente ni transferencia entre sesiones.

## Referencias técnicas

- RFC 9110: semántica HTTP (actualiza la referencia histórica RFC 7231).
- RFC 6455: WebSocket.
- RFC 9293: TCP, referencia solicitada en las indicaciones de evaluación.
- RFC 5681: slow start y congestion avoidance como inspiración.
- RFC 6298: temporizadores, backoff y regla de Karn como referencia.

La documentación distingue las ideas adaptadas de los mecanismos realmente
implementados; citar una RFC no significa implementar todo su estándar.

## Alcance de validaciones y lectura de evidencia

El servidor no implementa cuota de mensajes por segundo ni autenticación propia.
El límite de mensaje y lote no debe describirse como rate limiting. Presupuestos
de payload/bitmaps no representan heap/RSS total; q0, además, usa un histograma
RGB de ~64 MiB durante conversión. Véase el RFC para decisiones y límites.
Las pruebas registradas y el benchmark respaldan los casos descritos, no la
evaluación pendiente con originales de hasta 93 GB ni conformidad completa con TCP.
