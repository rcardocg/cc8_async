# Protocolo GTP/1: especificación de protocolos y renderizado progresivo

Ubicación original: `docs/diseno_protocolo_previo.md`. Archivado el 2026-10-07.
Véase la [evolución de decisiones](README.md#evolución-de-decisiones) para distinguir
las propuestas siguientes de los mecanismos finalmente implementados.

> **Referencia histórica, conservada antes de redactar el RFC interno.** Este
> documento mezcla objetivos y revisiones parciales; no es el contrato vigente
> ni la especificación para entregar. El RFC de la solución implementada está en
> [RFC interno GTP-001](../../protocolo.md) y el contrato operativo en
> [GTP/1](../protocolo.md). Se conserva como evidencia del diseño previo.

Ciencias de la Computación VIII. Proyecto Servidor Asíncrono.

> Especificación objetivo anterior. El contrato implementado está en [GTP/1](../protocolo.md).
> Actualización PNG/portabilidad: originales exclusivamente PNG; raíces separadas
> `GTP_IMAGES` y `GTP_WORK` en Linux y Windows. P2 registra metadata y espera el
> original; P3 inicial ya transfiere/procesa PNG no entrelazados hasta 8 bits.
> El primer transporte/visor multinivel está implementado; los ejemplos de esta
> especificación objetivo no sustituyen el contrato vigente en `docs/protocolo.md`.
> Actualización 2026-10-06: no usar LRU ni FIFO como reemplazo de caché.

## 1. Visión general

El servidor Java sirve imágenes de ultra alta resolución sin enviar nunca la imagen completa. La base del diseño es la misma idea de JPIP (ISO/IEC 15444-9): el cliente pide una región por **posición (x, y)**, **nivel de resolución (z)** y **calidad (q)**, y el servidor responde solo con lo necesario. Sobre esa base se añaden dos mecanismos: **control de transmisión** (ventana, ACK, retransmisión, inspirado en TCP) y **gestión de memoria** (cliente y servidor).

La comunicación tiene dos capas:

| Capa | Transporte | Uso |
|---|---|---|
| Bootstrap | HTTP/1.1 (RFC 9110) | HTML, JS, CSS, catálogo y metadata |
| GTP/1 | WebSocket (RFC 6455), mensajes JSON | Solicitud, envío y confirmación de tiles, control de flujo, memoria y calidad |

Todos los recursos se sirven desde el propio servidor Java. No hay solicitudes externas.

## 2. Modelo de datos: pirámide de tiles

Cada imagen se preprocesa una sola vez en una pirámide de niveles. Un nivel z tiene la imagen reducida a la mitad respecto al nivel z+1. El nivel 0 cabe en un solo tile y el nivel máximo (maxZoom) es la resolución original. Cada nivel se divide según el `tileSize` de metadata (256 por defecto; el selector también permite 512).

Un tile se identifica por **(imageId, z, x, y, q)**. En z, el número de columnas es ceil(ancho_z / tileSize) y el de filas ceil(alto_z / tileSize).

### 2.1. Niveles de calidad

La calidad q es independiente del nivel z. Permite enviar primero una versión liviana del tile y refinarla después.

| q | Nombre | Descripción | Uso típico |
|---|---|---|---|
| 0 | Pixel | Tile reducido a 64 × 64, paleta de 16 o 256 colores (PNG indexado, estilo 8/16 bits), escalado en el cliente sin suavizado | Navegación rápida, presión de memoria alta, conexión lenta |
| 1 | Baja | JPEG o WebP calidad 50 | Movimiento moderado |
| 2 | Alta | JPEG o WebP calidad 85 | Reposo en conexión normal |
| 3 | Completa | PNG sin pérdida | Zona fija observada en reposo |

La pirámide mantiene la forma general de la imagen al alejar (zoom out) porque los niveles bajos son reducciones reales. Al acercar (zoom in), el cliente muestra de inmediato el tile del nivel inferior ampliado con aspecto pixelado y el servidor envía después el tile nítido del nivel z+1. El aspecto de 8/16 bits es un estado **transitorio y de ahorro**, no el resultado final: la imagen se refina cuando el usuario se detiene.

## 3. Capa 1: bootstrap HTTP/REST

| Método y ruta | Respuesta |
|---|---|
| GET / | Visor (HTML) |
| GET /viewer.js, /viewer.css | Recursos estáticos locales |
| GET /api/images | Arreglo JSON con los identificadores de imágenes disponibles |
| GET /api/image/{id}/metadata | imageId, width, height, tileSize, totalTiles, maxZoom, format, niveles (columnas y filas por nivel) |
| POST /api/images | Registro de cabecera/metadata (202, pending/awaiting_transfer; no inicia el decoder) |
| GET/PUT/DELETE /api/image/{id}/upload | Transferencia por bloques, consulta de offset y reinicio de la copia |
| POST /api/image/{id}/ingest | Inicia el preprocesamiento de una fuente disponible (202) |
| GET /api/image/{id}/status | Estado del preprocesamiento (pending, processing, ready, failed) |
| GET /api/cache/stats | Estadísticas de caché de tiles (entries, usedBytes, maxBytes, hits, misses, evictions) |
| GET /api/cache/clear | Vacía la caché de tiles del servidor |

Semántica de métodos y códigos de estado según RFC 9110 (que reemplaza a RFC 7230 a 7235). Los tiles **no** se piden por HTTP: viajan por WebSocket.

## 4. Capa 2: GTP/1 sobre WebSocket

Todos los mensajes son objetos JSON con el campo `action`. El canal se abre en `/ws/tiles` con el handshake de RFC 6455 (código 101). Cada conexión mantiene su propio estado: cola, ventana, métricas y calidad.

### 4.1. Resumen de mensajes

| Dirección | action | Propósito |
|---|---|---|
| S → C | ready | El servidor acepta y anuncia la versión GTP/1 |
| C → S | fetch_tiles | Solicita una lista de tiles |
| S → C | request_accepted | Valida la solicitud |
| S → C | tile_data | Envía un tile |
| C → S | ack_tile | Confirma el tile decodificado |
| S → C | transfer_state | Estado de la ventana tras un ACK |
| S → C | request_complete | Cierra la solicitud con el resumen |
| C → S | cancel_request | Descarta tiles que ya no se ven |
| C → S | gesture | GACP: tipo y velocidad de interacción |
| S → C | quality_adjustment | GACP: calidad recomendada |
| C → S | memory_pressure | MPNP: presión de memoria del cliente |
| S → C | adjust_strategy | MPNP: ajuste de la estrategia de envío |

### 4.2. fetch_tiles

```json
{"action":"fetch_tiles","request_id":"r17","image_id":"eso1242a",
 "tiles":[{"z":5,"x":10,"y":7,"q":0},{"z":5,"x":11,"y":7,"q":0}],
 "priority":"viewport"}
```

El servidor valida que z, x, y estén dentro del rango de la imagen y que q sea 0 a 3. Si no, responde con un error por tile y no rompe la conexión.

### 4.3. tile_data y ack_tile

```json
{"action":"tile_data","request_id":"r17","transfer_id":"t204",
 "tile_id":"eso1242a:5:10:7:0","attempt":1,"format":"png","q":0,
 "data":"<base64>"}
```

```json
{"action":"ack_tile","transfer_id":"t204","tile_id":"eso1242a:5:10:7:0","decode_ms":4}
```

El ACK se envía **después de decodificar** el tile en el navegador, por lo que el RTT medido incluye el procesamiento del cliente (RTT de aplicación). Esa es la diferencia con los ACK de TCP: TCP confirma bytes recibidos; GTP confirma tiles utilizables.

### 4.4. cancel_request

```json
{"action":"cancel_request","request_id":"r17","tiles":["eso1242a:5:10:7:0"]}
```

Si el usuario se desplaza o cambia de nivel, los tiles aún en cola dejan de ser relevantes. El servidor los saca de la cola y libera la ventana. Esto evita transferir información que nunca se mostrará.

## 5. Control de transmisión (inspirado en TCP)

WebSocket corre sobre TCP, que ya garantiza entrega ordenada de bytes. GTP añade control **a nivel de aplicación** porque TCP no conoce las necesidades del visor: no sabe qué tiles siguen visibles, cuánta memoria tiene el navegador ni cuándo un tile fue realmente decodificado.

| Mecanismo | Descripción | Referencia |
|---|---|---|
| Ventana de congestión (cwnd) | Máximo de tiles en vuelo para una sesión. Crece mientras los ACK llegan a tiempo y se reduce ante timeout | RFC 5681 |
| Ventana del receptor (rwnd) | Límite impuesto por el cliente según su memoria (ver MPNP) | RFC 9293, sección 3.8.6 |
| Ventana efectiva | min(cwnd, rwnd) | RFC 9293 |
| RTO | Se calcula con SRTT y RTTVAR a partir de los RTT de aplicación, con mínimo de 1 s | RFC 6298 |
| Retransmisión | Si no hay ACK antes del RTO, el servidor reenvía el mismo transfer_id con attempt + 1. Tras N intentos el tile se marca fallido | RFC 6298 |
| Backoff exponencial | El RTO se duplica en cada retransmisión consecutiva | RFC 6298, sección 5 |

El campo `transfer_state` informa al cliente: `cwnd`, `in_flight`, `queued`, `rtt_ms`, `rto_ms`.

## 6. MPNP: notificación de presión de memoria

Cliente:

```json
{"action":"memory_pressure","usage_pct":90,"tiles_loaded":180,"memory_limit_mb":256}
```

Servidor:

```json
{"action":"adjust_strategy","receiver_window":2,"reduce_prefetch":true,"max_quality":1}
```

Reglas de ejemplo:

| Uso de memoria | Ventana del receptor | Prefetch | Calidad máxima |
|---|---|---|---|
| menor a 50% | 32 | sí | 3 |
| 50% a 80% | 8 | reducido | 2 |
| mayor a 80% | 2 | no | 1 |

El navegador no expone una medida exacta de memoria. El cliente estima su uso como (tiles cargados × bytes decodificados por tile) y lo compara con un límite configurado. Esta aproximación debe declararse en el documento como decisión de diseño.

## 7. GACP: gestos y cambio de calidad

Cliente (se envía como máximo cada 100 ms):

```json
{"action":"gesture","type":"pan","velocity_px_s":850,"zoom_delta":0,"fps":42}
```

Servidor:

```json
{"action":"quality_adjustment","q":0,"reason":"fast_pan"}
```

| Estado detectado | Condición | Calidad |
|---|---|---|
| Movimiento rápido | velocidad mayor a umbral alto o FPS bajo | q = 0 (pixel) |
| Movimiento lento | velocidad entre umbrales | q = 1 |
| Reposo | velocidad cercana a 0 durante 300 ms | q = 3 y refinamiento de la zona visible |

Cuando el usuario queda en reposo, el cliente solicita de nuevo los tiles visibles con mayor q. El tile de menor calidad se **reemplaza** y se descarta, no se acumula.

## 8. Orden de envío de tiles

Los tiles se envían en el orden que minimiza el tiempo hasta ver la región útil, no en orden de filas. La cola se ordena con una heurística del vecino más cercano (la idea básica del problema del viajante): partiendo del centro del viewport, se elige siempre el tile pendiente más cercano al último enviado, lo que produce un recorrido en espiral hacia afuera. Después del tile visible vienen los tiles de prefetch en la dirección del movimiento.

Esto es una **heurística de orden**, no una solución al problema del viajante. Su costo es O(n²) para n tiles de la cola, aceptable porque n es pequeño (decenas de tiles). Debe describirse así en la defensa.

## 9. Gestión de memoria

### 9.1. Cliente

- Caché de tiles decodificados con límite máximo en MB y expulsión por **utilidad espacial**.
- Los visibles se protegen. Al salir del viewport o cambiar de nivel comienza un
  `out_of_view_ttl_ms` (5000 ms inicialmente); volver a ser visible cancela su expiración.
- Bajo presión se expulsan primero los no visibles más lejanos; TTL y presupuesto
  son complementarios. Al expulsar se llama a `ImageBitmap.close()`.
- El nivel 0 se solicita primero y permanece protegido como respaldo; otros
  niveles inferiores disponibles son temporales y expiran cuando salen de alcance.

### 9.2. Servidor

- Los tiles se leen del disco bajo demanda y nunca se carga la imagen original completa en RAM.
- Caché de tiles codificados **LFU con envejecimiento + TTL por inactividad** y tope
  de memoria. Lecturas incrementan frecuencia y renuevan TTL sin reordenamiento por
  acceso/inserción. Los empates se resuelven por identificador; no es LRU ni FIFO.
- Un pool de hilos acotado atiende el preprocesamiento y las lecturas para no saturar la CPU.

Los algoritmos se justifican con Belady (1966) para el reemplazo de páginas y Denning (1968) para el conjunto de trabajo.

### 9.3. Presupuestos y fragmentación

El zoom/región determinan qué nivel y tiles hacen falta; no cambian la MTU de red.
Una ventana pequeña limita ritmo y memoria pendiente, no la resolución final.
TCP segmenta/reconstruye el flujo de red. La extensión opcional GTP
`transfer_window_bytes` fragmenta payloads de aplicación y requiere reconstrucción
en el cliente: `tile_fragment`/`ack_fragment`, seguido de `ack_tile` tras decodificar.
5000 bytes con ventana de 1500 se entregan como 1500+1500+1500+500, sin pérdida.
La ventana cuenta bytes de payload, no cabeceras JSON/Base64 ni paquetes TCP.

## 10. Almacenamiento

No se utiliza base de datos. La estructura es un directorio por imagen:

```text
<GTP_IMAGES>/imagen.png             original local del servidor, sin modificar
<GTP_WORK>/{imageId}/
  meta.json                        dimensiones, tileSize, maxZoom, formato, estado
  source.part                      copia de subida, si el original viene del navegador
  tiles/{z}/{x}_{y}.png             tiles de calidad completa
  tiles/{z}/{x}_{y}.jpg             variante de calidad media (objetivo P4)
```

Las variantes de menor calidad (q = 0 y 1) pueden generarse bajo demanda a partir del tile completo y guardarse en la caché del servidor. El catálogo (`/api/images`) se construye leyendo los `meta.json` al iniciar y al detectar nuevas imágenes.

## 11. Seguridad y límites

- Se validan z, x, y, q y se rechaza todo identificador de imagen con caracteres de ruta (por ejemplo `..`) para evitar acceso fuera de `GTP_WORK`; las fuentes del servidor se confinan a `GTP_IMAGES`.
- Se limita el tamaño máximo de un mensaje WebSocket y el número de tiles por `fetch_tiles`.
- Se limita el número de solicitudes por segundo por conexión.

## 12. Referencias

- RFC 9110: HTTP Semantics (reemplaza a RFC 7230 a 7235).
- RFC 6455: The WebSocket Protocol.
- RFC 9293: Transmission Control Protocol.
- RFC 5681: TCP Congestion Control.
- RFC 6298: Computing TCP's Retransmission Timer.
- ISO/IEC 15444-9: JPEG 2000, JPIP (Interactivity tools, APIs and protocols).
- Belady, L. A. (1966). A study of replacement algorithms for a virtual-storage computer. IBM Systems Journal.
- Denning, P. J. (1968). The working set model for program behavior. Communications of the ACM.
