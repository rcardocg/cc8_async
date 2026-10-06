# P4 — Calidades progresivas y caché de tiles

Actualización: 2026-10-05. Código compartido Linux/Windows; sin dependencias de producción.
Evidencia ejecutada en Windows en [verificacion.md](verificacion.md). Repetición en Fedora pendiente.

## Alcance funcional

- **Calidades q0–q3** generadas bajo demanda desde el tile nativo (q3 PNG sin pérdida):
  - **q0 Pixel**: 64×64, PNG indexado 256 colores, escalado vecino más cercano.
  - **q1 Baja**: JPEG calidad 50.
  - **q2 Alta**: JPEG calidad 85.
  - **q3 Completa**: PNG nativo (identidad).
- **Caché codificada** LRU en servidor:
  - Límite configurable: `tiles.cache-bytes` (default 50 MiB) / `GTP_TILE_CACHE_BYTES`.
  - Clave: `imageId:z:x:y:q` (TileKey.id()).
  - Estadísticas: hits, misses, evictions, entradas, bytes usados.
  - Endpoint: `GET /api/cache/stats`, `GET /api/cache/clear`.
- **Compatibilidad**: catálogo plano y demo siguen sirviendo tiles X/Y/q3; imágenes P3 `ready` exponen calidades.

## Uso desde el cliente (igual en ambos sistemas)

1. Imagen P3 en estado `ready` → metadata incluye `availableQualities: [3]` (solo q3 en este incremento; q0–q2 se generan bajo demanda y se cachean).
2. Cliente WebSocket envía `fetch_tiles` con `z` y `q`:
   ```json
   {"version":1,"action":"fetch_tiles","request_id":"r1","imageId":"img1","tiles":[{"z":2,"x":10,"y":5,"q":0}]}
   ```
3. Servidor busca en caché `img1:2:10:5:0`; si no existe, lee tile nativo (q3), genera calidad solicitada, almacena y responde.
4. Respuesta `tile_data` incluye `z`, `q`, `compression` (`png` para q0/q3, `jpeg` para q1/q2).

## Contrato HTTP P4

| Método/ruta | Resultado |
|---|---|
| `GET /api/cache/stats` | 200: `entries, usedBytes, maxBytes, hits, misses, evictions` |
| `GET /api/cache/clear` | 204: vacía la caché |

## WebSocket P4 (extensión de GTP/1 v1)

### fetch_tiles (cliente → servidor)
```json
{"version":1,"action":"fetch_tiles","request_id":"r1","imageId":"img1",
 "tiles":[{"z":2,"x":10,"y":5,"q":0}],"replace":true}
```
- `z`: 0..maxZoom. Requerido para imágenes P3; catálogo plano rechaza `z`/`q`.
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
  .cache/                    (no persistente; solo RAM)
    {z}/{x}_{y}.{png|jpeg}   calidades derivadas cacheadas
```
- La caché es solo en memoria (RAM). No se escriben calidades derivadas a disco en este incremento.
- Límite por bytes evita OOM; LRU evicta entradas menos usadas.

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
- Repetir en Fedora con originales del curso.