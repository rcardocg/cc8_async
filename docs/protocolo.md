# GTP/1 — Protocolo de control de transferencia de tiles

Fecha: 2026-10-05. Este contrato describe el código implementado, no la propuesta
histórica. GTP significa «Gigapixel Tile Protocol» dentro de este proyecto; no se
presenta como estándar registrado.

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

Mensajes JSON en frames de texto. Todos contienen `version: 1` y `action`.
Las cadenas identificadoras de mensajes deben ser no vacías, hasta 128 caracteres.
El servidor limita mensajes entrantes a 32 KiB y lotes a 128 entradas, antes de
eliminar duplicados. Campos numéricos de coordenadas deben ser enteros, no strings
ni decimales. La versión actual admite únicamente X/Y nativos: `z` omitido o `null`.

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

- `request_id` correlaciona una región; el cliente debe generar un valor nuevo por
  solicitud. El servidor rechaza reutilizar inmediatamente el último valor.
- Hay una sola solicitud activa por sesión. Sin `replace: true`, una nueva solicitud
  mientras otra está activa genera `request_busy`.
- La solicitud se valida completa **antes** de cancelar la anterior. Coordenadas
  inválidas o imagen inexistente nunca alteran trabajo ya aceptado.
- Tiles duplicados se consolidan preservando el orden de primera aparición (FIFO).
- Sustituir una región emite `request_cancelled`, libera pendientes y datos retenidos
  de la anterior y acepta la nueva. Datos ya enviados pueden llegar tarde: el cliente
  los descarta por `request_id`.
- No se implementan `priority`, `metrics`, `gesture` ni `viewport_update` de la propuesta.

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
no libera memoria ni aumenta la ventana. El ACK se envía tras decodificar y colocar
el tile; si el duplicado ya está decodificado, se confirma sin renderizarlo de nuevo.

`latency_ms` del prototipo antiguo no se utiliza. La medición la hace el servidor
con reloj monotónico entre envío y ACK de aplicación. Incluye latencia de red,
planificación y procesamiento del cliente; no equivale al RTT TCP puro.

### Memoria y cancelación

```json
{"version":1,"action":"memory_pressure","current_usage":90,"memory_limit":100}
```

Ambos valores son enteros no negativos en bytes; `memory_limit` debe ser mayor que
cero. La política utiliza la razón entre ellos. Uso >=80%: ventana del receptor 2;
>=60%: 4; menor: 32. Entrar a >=80% desde una ventana superior también reduce `cwnd`.
El cliente de diagnóstico envía cantidades simuladas y así lo indica en pantalla.

```json
{"version":1,"action":"cancel_request","request_id":"view-1"}
```

Cancela sólo la solicitud activa coincidente. Una solicitud ya terminada o desconocida
produce `cancel_ignored`. Cerrar la conexión elimina todo el estado de esa sesión.
Reconectar crea una sesión nueva y requiere solicitar nuevamente la región actual.

## Mensajes del servidor

| Acción | Campos principales y significado |
|---|---|
| `ready` | `protocol: "GTP/1"`, `max_batch: 128`, `max_window: 32`, `initial_window: 2` |
| `request_accepted` | `request_id`, `total` de tiles únicos |
| `tile_data` | Ejemplo debajo; imagen comprimida real |
| `transfer_state` | `request_id`, `cwnd`, `receiver_window`, `in_flight`, `pending`, `in_flight_bytes`, `rtt_ms`, `rto_ms`; emitido tras ACK válido y antes de nuevos envíos |
| `adjust_strategy` | `receiver_window`, `max_tiles_concurrent`, `reduce_prefetch`, `decrease_quality: false` |
| `ack_ignored` | `request_id`, `transfer_id`; no se reconoce como ACK vigente |
| `request_cancelled` / `cancel_ignored` | `request_id` |
| `tile_error` | `request_id`, `tile_id`, `code`, `message`; `transfer_id` cuando hay timeout |
| `request_complete` | `request_id`, `total`, `acknowledged`, `failed`; indica fin, no éxito universal |
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
  "compression": "png",
  "size_bytes": 1234,
  "attempt": 1,
  "data": "BASE64_DEL_ARCHIVO"
}
```

`1234` y el texto Base64 son ilustrativos. `size_bytes` es el tamaño del archivo
comprimido, no del frame JSON; Base64 añade aproximadamente un tercio de tamaño.
Se conserva JSON por trazabilidad inicial; frames binarios quedan para optimización.
`compression` es el formato original del tile; no hay recodificación adaptativa.
`reduce_prefetch` es una directiva: no implica que exista un motor de prefetch.

Códigos: `invalid_request`, `unknown_image`, `request_busy`, `tile_unavailable` y
`ack_timeout`. Los primeros tres son errores de solicitud; los últimos dos son
errores de tile. JSON inválido produce respuesta controlada; errores de transporte
cierran la sesión y liberan recursos.

## Ventana y recuperación selectiva

Estado por sesión: cola FIFO, mapa de transferencias sin ACK, bytes retenidos,
solicitud activa, contadores de éxito/fallo, `cwnd`, `ssthresh`, RTT suavizado y RTO.

- Inicio: `cwnd=2`, `ssthresh=16`, ventana del receptor=32, RTO=1000 ms.
- ACK válido sin retransmisiones: si RTT > `max(200ms, 2*SRTT anterior)`, reducir
  ventana; en otro caso, sumar 1 durante slow start o `1/cwnd` en avoidance.
- `cwnd` máximo 32. El incremento fraccional evita el crecimiento excesivo del
  prototipo antiguo. Es una adaptación a tiles, no conformidad con TCP.
- Reducción: `ssthresh=max(2,cwnd/2)` y `cwnd=ssthresh`.
- `SRTT = primer RTT`, después `0.875*SRTT + 0.125*RTT`.
- RTO base para nuevos tiles: `clamp(3*SRTT, 1000ms, 10000ms)`. Es una heurística
  simplificada: no implementa la estimación de varianza completa de RFC 6298.
- Tras retransmisión no se mide RTT ni se incrementa ventana con ese ACK (Karn).
- El planificador revisa cada 250 ms. Sólo se retransmiten entradas vencidas sin ACK,
  con mismo `transfer_id` y bytes. Cada timeout duplica su intervalo, máximo 10 s.
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
512 KiB. Se admiten hasta 64 conexiones para acotar consumo agregado. Esta es una
cota de payloads, no de todo el heap ni de buffers internos del contenedor.

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
