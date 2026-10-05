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

## 2026-10-05 · P2: registro persistente y metadata multinivel

**Objetivo:** identificar el PNG elegido desde el cliente y consultar sus niveles
previstos/estado sin confundir inspección con ingesta completa.

**Fallo observado:** los originales inspeccionados solo existían en la selección
temporal del navegador; no había registro, estado ni persistencia por imagen.

**Cambio:** `POST /api/images` recibe cabecera de 33 bytes e identificador, responde
202 y guarda `<work>/<id>/meta.json` mediante temporal y movimiento atómico.
`ImageRegistry` recalcula/valida manifests al cargar y refrescar. El catálogo
incorpora registros sin reiniciar. Metadata agrega niveles, estado y listas
separadas de niveles/calidades disponibles. `GET /api/image/{id}/status` expone
progreso/origen/causa; estado inicial pending y awaiting_transfer. TileKey incorpora
z/q manteniendo constructor e identidad planos. Se validan coordenadas por nivel,
pero se rechaza transferir registros no listos. Cliente añade registro, refresco
de catálogo y consulta de estado; cargar una imagen pendiente queda deshabilitado.

**Verificación:** 40 pruebas Java aprobadas, 0 fallos/errores/omisiones, incluidas
recarga desde disco, duplicados/reservados, manifests corruptos, límites y bordes.
E2E Chromium/Fedora aprobado: registro y estado pending, duplicado sin sobrescribir,
bloqueo de carga y retorno a la demo con GTP funcionando.

**Límites:** original aún en el navegador; no hay upload completo ni decoder.
Los registros permanecen pending hasta conectar una fuente e ingesta en P3.
Persistencia de failed preparada; processing/ready de imágenes nuevas se validarán
con salida real en P3. GTP v1 sigue plano; transporte z/q corresponde a P5.
Pruebas manuales y contrato: [registro_p2.md](../registro_p2.md).
