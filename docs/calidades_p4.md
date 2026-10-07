# Calidades y caché de tiles — referencia de uso

Actualización documental: 2026-10-07. Código compartido Linux/Windows; evidencia
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

### Por qué el visor utiliza q3

El nivel `z` ajusta la resolución espacial a la vista; `q` selecciona una variante
del tile de ese nivel. El visor pide q3 para conservar su detalle sin añadir pérdida
de codificación. Para detalle nativo hacen falta **ambos**: `z=maxZoom` y `q=3`.
Generar q0–q2 permite experimentar con representaciones más ligeras, pero no
demuestra que exista adaptación automática extremo a extremo.

LFU favorece contenido reutilizado; el envejecimiento reduce el peso de popularidad
antigua y el TTL libera contenido inactivo. La política completa y sus costos se
explican en el [RFC, sección 10](../protocolo.md#10-políticas-de-memoria-y-ttl).
La transición desde la LRU del incremento P4 está registrada en la
[historia de decisiones](historico/README.md#evolución-de-decisiones).

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

Los campos, valores predeterminados y validaciones de `fetch_tiles`/`tile_data`
se mantienen únicamente como contrato detallado en [GTP/1](protocolo.md).
Las coordenadas del ejemplo requieren una imagen preparada con ese nivel y rango.

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

## Verificación

Los [comandos comunes de verificación](verificacion.md#reproducir-las-pruebas)
ejecutan `TileQualityServiceTest`, `TileCacheServiceTest` y la integración P4.
Las suites de calidad cubren q0 indexado, JPEG q1/q2, identidad q3 y transparencia;
las de caché cubren presupuesto, LFU, envejecimiento, TTL y contadores.

El E2E del visor pide q3. Comprobar `availableQualities:[3]` o que responde
`/api/cache/stats` no demuestra adaptación q0→q3 ni mide el costo de generar q0.

Para comprobar TTL del servidor manualmente: solicitar tiles, consultar
`/api/cache/stats`, dejar de solicitarlos durante más del TTL configurado (31 s
para el valor predeterminado) y consultar otra vez. Las entradas inactivas deben
expirar y `policy` debe indicar `LFU_AGING_TTL`. Una lectura acertada del tile en
caché renueva su TTL; consultar estadísticas no equivale a utilizar cada tile.

## Posibles extensiones y mediciones pendientes

- Generar y cachear q0–q2 en disco opcionalmente (para arranque en caliente).
- Prefetch de calidades adyacentes.
- Métricas de ratio de compresión real por calidad.
- Medir variantes/concurrencia con originales del curso; repetir última revisión en Windows.
