# Fase 04 — Transferencia de tiles reales

## 2026-10-02 · Implementación y resolución

**Problemas:** el payload era `base64_aqui...`, el ID sólo incluía X/Y, y la solicitud
ignoraba imagen, zoom y límites; no había lectura real ni errores de almacenamiento.

**Resolución:**
- `TileKey` identifica imagen/X/Y; se valida contra metadata antes de aceptar trabajo.
- `TileService` lee sólo el tile local solicitado, con límite estricto de 512 KiB.
- Se verifican formato PNG/JPEG y dimensiones de cabecera, incluidos bordes parciales.
- La demo genera PNG real por tile, sin reservar una imagen gigante en memoria.
- Frames incluyen bytes codificados, tamaño comprimido, coordenadas, formato,
  solicitud, transferencia e intento. Retransmisiones reutilizan los mismos bytes.
- Fallos de lectura producen `tile_error` sin detener los demás tiles; un resumen
  final reporta confirmados y fallidos.
- `z` no nulo se rechaza explícitamente: no se descarta silenciosamente una dimensión.

**Verificación:** decodificación de PNG demo y tile de borde real desde disco;
rechazo de dimensiones/tamaño incorrectos; error de tile ausente; entrega/recuperación
por red y renderizado en navegador. Véase [verificacion.md](../verificacion.md).

**Estado:** transferencia real de tiles implementada. Los datos externos deben estar
preprocesados según [imagenes.md](../imagenes.md). Importar las imágenes originales
de 24/17/28/55/93 GB, generar pirámides, prioridades y compresión adaptativa sigue
pendiente. La demo no debe presentarse como evidencia de estas capacidades.
