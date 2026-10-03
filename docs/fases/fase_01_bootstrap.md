# Fase 01 — Bootstrap HTTP y metadata

## 2026-10-02 · Implementación y resolución

**Problemas:** `/api/images` devolvía nombres inventados; cualquier ID obtenía la
misma metadata; `MetadataService` estaba vacío y `totalTiles` usaba `int`.

**Resolución:**
- `MetadataService` ahora es un servicio Spring e inicializa un catálogo real.
- `BootstrapController` usa inyección del servicio y devuelve HTTP 404 para IDs
  desconocidos, manteniendo los dos endpoints iniciales.
- `catalog.json` opcional permite registrar imágenes predivididas; la demo integrada
  usa un identificador explícito (`demo_numeros`) y dimensiones verificables.
- Validados identificadores, formatos, dimensiones, tamaño de tile y cantidad total.
  La aritmética y el campo `totalTiles` usan `long` para no desbordar con imágenes grandes.
- Un catálogo inválido falla al arrancar, en vez de anunciar imágenes inconsistentes.

**Verificación:** `MetadataAndTileServiceTest` prueba desconocidos, coordenadas
fuera de rango, catálogos inválidos, bordes parciales y conteos superiores a `int`.
`ServerIntegrationTest` verifica respuestas HTTP y carga del frontend desde Java.

**Estado:** base implementada. Registrar imágenes requiere actualizar el catálogo
y reiniciar; todavía no existe importador de originales gigantes ni actualización
dinámica del catálogo. Formato y procedimiento: [imagenes.md](../imagenes.md).
