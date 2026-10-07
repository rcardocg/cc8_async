# Fase 04 — Transferencia de tiles reales

**Bitácora histórica por fecha.** Referencias actuales: [ingesta](../ingesta_p3.md)
y [contrato GTP/1](../protocolo.md). El límite de 512 KiB pertenece a la base inicial.

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

## 2026-10-05 · P3 inicial: fuente y pirámide PNG real

**Objetivo:** conectar los registros P2 con bytes del original y generar tiles/niveles
con memoria acotada, manteniendo el transporte plano operativo.

**Fallo observado:** P2 solo conservaba cabecera; Java no podía abrir una ruta del
selector. No existía decoder de IDAT ni publicación de salida preparada.

**Cambio:** subida persistente de hasta 1 MiB/bloque, fuente confinada a GTP_IMAGES,
CLI real y trabajo asíncrono HTTP. Decoder secuencial con CRC, cinco filtros, Deflate,
IEND y SHA-256; fila RGBA8 y banda en disco; reducción 2×2 desde tiles con alpha
premultiplicado. Manifiesto P3 v2 y publicación atómica de pirámide completa; estados,
cancelación, reinicio fallido y reintento. Exclusión con semáforo local y lock de archivo,
evitando abrir/cerrar descriptores rivales de un lock propio en POSIX. Sin dependencias nuevas.

**Verificación Windows:** suites nuevas de decoder/servicio/HTTP; regresiones P0/P1/P2
y GTP incluidas en 53 casos (52 aprobados/1 POSIX omitido). E2E Edge completó P3 y
continuó la demo con recuperación y dos clientes. Smoke JAR real `-Xmx64m` procesó
PNG sintético 8193×4097, 783 tiles/7 niveles y SHA correcto; reinicio real de Java
preservó pending, ready y offset de subida. Ver [verificacion.md](../verificacion.md).

**Límites y continuidad en ambos ambientes:** [ingesta_p3.md](../ingesta_p3.md) incluye
los comandos Fedora/Windows. P3 nuevo pendiente de ejecución en Fedora, datasets del
curso, RSS/disco y fidelidad visual. Rechazo explícito de Adam7/16 bits. La subida se
reanuda por offset; el decoder se reintenta desde cero. P4/P5/P6 siguen pendientes;
generar una pirámide no habilita aún su navegación GTP multinivel.

## 2026-10-06 · Continuación P5 — registro recopilado el 2026-10-07

**Cambio registrado:** pirámides `ready` integradas con transporte z/q y canvas;
máximo de tile codificado ampliado a 2 MiB para tiles de 512 px poco compresibles.
Payloads retenidos por sesión y reconstrucción cliente permanecen acotados a
4 MiB en cada ámbito; el presupuesto RGBA se calcula por dimensiones reales.

**Motivo:** permitir detalle nativo sin hacer depender la resolución de la ventana
de transferencia. La fragmentación divide bytes del tile, no recodifica sus píxeles.

**Evidencia original:** [P5/P6](../historico/renderizado_p5.md#evidencia-ejecutada)
y [P7](../experimentos_p7.md). Sus resultados sintéticos/locales no acreditan la
escalera completa de originales gigantes.
