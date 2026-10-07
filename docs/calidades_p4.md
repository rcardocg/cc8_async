# P4 — Calidades progresivas y caché de tiles

Actualización documental: 2026-10-06. Código compartido Linux/Windows; evidencia
histórica P4 en Windows y revisión vigente en Fedora: [verificacion.md](verificacion.md).
Esta guía amplía el [RFC interno GTP-001](../protocolo.md); no implica que el visor
utilice automáticamente todas las variantes ni que haya adaptación q0→q3 integrada.

## Alcance funcional

- **Calidades q0–q3** generadas bajo demanda desde el tile nativo (q3 PNG sin pérdida):
  - **q0 Pixel**: 64×64, PNG indexado de hasta 256 colores, escalado vecino más cercano;
    selección de colores más frecuentes y asignación por distancia RGB, sin alpha preservado.
  - **q1 Baja**: JPEG calidad 50.
  - **q2 Alta**: JPEG calidad 85.
  - **q3 Completa**: PNG nativo (identidad).
- **Caché codificada** LFU con envejecimiento + TTL en servidor (actualización P5, 2026-10-06):
  - Límite configurable: `tiles.cache-bytes` (default 50 MiB) / `GTP_TILE_CACHE_BYTES`.
  - TTL de inactividad: `tiles.cache-ttl-ms` (default 30000 ms) / `GTP_TILE_CACHE_TTL_MS`.
  - Clave: `imageId:z:x:y:q` (TileKey.id()).
  - Estadísticas: hits, misses, evictions, entradas, bytes usados, ttlMs y policy.
  - Endpoint: `GET /api/cache/stats`, `GET /api/cache/clear`.
- **Compatibilidad**: catálogo plano y demo siguen sirviendo tiles X/Y/q3; imágenes P3 `ready` exponen calidades.

## Uso desde el cliente (igual en ambos sistemas)

1. Imagen P3 en estado `ready` → metadata incluye `availableQualities: [3]` (q3
   preparado; q0–q2 se derivan bajo demanda). El visor actual pide q3 del nivel elegido.
2. Cliente WebSocket envía `fetch_tiles` con `z` y `q`:
   ```json
   {"version":1,"action":"fetch_tiles","request_id":"r1","imageId":"img1","tiles":[{"z":2,"x":10,"y":5,"q":0}]}
   ```
3. Servidor busca en caché `img1:2:10:5:0`; si no existe, lee tile nativo (q3), genera calidad solicitada, almacena y responde.
4. Respuesta `tile_data` incluye `z`, `q`, `compression` (`png` para q0/q3, `jpeg` para q1/q2).

## Contrato HTTP P4

| Método/ruta | Resultado |
|---|---|
| `GET /api/cache/stats` | 200: `entries, usedBytes, maxBytes, hits, misses, evictions, ttlMs, policy` |
| `GET /api/cache/clear` | 204: vacía la caché |

## WebSocket P4 (extensión de GTP/1 v1)

### fetch_tiles (cliente → servidor)
```json
{"version":1,"action":"fetch_tiles","request_id":"r1","imageId":"img1",
 "tiles":[{"z":2,"x":10,"y":5,"q":0}],"replace":true}
```
- `z`: 0..maxZoom, predeterminado 0 si omitido/null. Catálogo plano rechaza z/q no nulos.
- `q`: 0..3. Default 3.

### tile_data (servidor → cliente)
```json
{"version":1,"action":"tile_data","request_id":"r1","transfer_id":"1",
 "tile_id":"img1:2:10:5:0","imageId":"img1","x":10,"y":5,"z":2,"q":0,
 "compression":"png","size_bytes":1234,"attempt":1,"data":"BASE64..."}
```

## Almacenamiento y caché

```
<GTP_WORK>/<imageId>/
  tiles/
    {z}/{x}_{y}.png          tile nativo q3 (generado por P3)
```
- Caché RAM: mapa `tile_id → bytes codificados/frecuencia/último uso`; no se genera
  un directorio `.cache` ni se escriben variantes derivadas a disco.
- El límite acota payloads cacheados, no todo el heap. LFU expulsa entradas de menor
  frecuencia; los contadores envejecen y el TTL libera entradas inactivas.
- La paleta q0 utiliza un histograma RGB de 2^24 enteros (~64 MiB) por conversión.
  Esa memoria temporal no está incluida en `usedBytes`; su concurrencia requiere
  medición específica. Los ensayos actuales del visor q3 no ejercitan esa ruta.
- q1/q2 componen transparencia sobre blanco; q3 conserva bytes del tile PNG del
  nivel. Ninguna variante sustituye por sí sola seleccionar resolución nativa.

## Variantes admitidas

| q | Formato | Uso típico |
|---|---|---|
| 0 | PNG indexado 64×64 | Navegación rápida, presión memoria |
| 1 | JPEG q50 | Movimiento moderado |
| 2 | JPEG q85 | Reposo conexión normal |
| 3 | PNG nativo | Zona fija observada en reposo |

## Pruebas reproducibles en ambos ambientes

```text
# Tests unitarios + integración
.\scripts\build.cmd test

# Smoke P4: verificar calidades y stats de caché
node scripts/verify-browser.cjs .build/server.jar
# Verificar en metadata que availableQualities incluye 3 y /api/cache/stats responde
```

## Pendientes para cerrar P4

- Generar y cachear q0–q2 en disco opcionalmente (para arranque en caliente).
- Prefetch de calidades adyacentes.
- Métricas de ratio de compresión real por calidad.
- Medir variantes/concurrencia con originales del curso; repetir última revisión en Windows.
