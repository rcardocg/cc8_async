# P7 — primer ensayo medible de renderizado y protocolo

Fecha: 2026-10-06. Herramienta: `scripts/benchmark-viewer.cjs`.

Evidencia de la solución descrita en el [RFC interno GTP-001](../protocolo.md).
Este documento registra metodología/resultados del ensayo inicial; no certifica
la evaluación pendiente de la escalera completa de originales gigantes.

## Qué mide y qué verifica

La herramienta levanta un Java temporal con heap máximo 256 MiB, usa un contexto
Chromium nuevo por caso, vacía solo su propia caché del servidor y ejecuta la misma
escena con ventanas **0 (tile entero), 1500 y 16000 bytes**. Se prueban reposo,
omisión de ACK y diez cambios de región durante movimiento. No usa el servidor ni
la caché de la sesión del usuario. Los datos temporales se eliminan al terminar.

Métricas JSON por caso:

- Tiempo hasta primer dibujo con imagen, primer tile del nivel requerido,
  cobertura final visible y finalización de ACKs de aplicación.
- Bytes de payload, bytes JSON entrantes/salientes, fragmentos, reenvíos y bytes
  recibidos que ya no pertenecen a la solicitud/vista actual.
- Cancelaciones solicitadas y tiles cancelados en los resúmenes.
- Bytes RGBA retenidos y pico de bitmaps, cobertura/nivel y presencia del respaldo.
- Memoria y expulsiones después del TTL en escenas con pan.
- Caché servidor: política, bytes, contadores acumulados y deltas por caso.
- SHA-256 de **todos** los tiles finales visibles decodificados en RGBA. Se exige
  igualdad entre las tres ventanas sobre la misma escena.

Se verifican: cero fallos de tiles/JS, límite de bitmaps, respaldo presente,
retransmisión ante ACK omitido, fragmentos dentro de ventana y, tras TTL, retención
solo de visibles/respaldo. Reporte conserva entorno, metadata y clasificación del
dataset. Si se proporciona `--output`, se generan también capturas desktop/móvil.

No se simulan pérdidas de paquetes TCP. Los bytes JSON incluyen Base64, pero no
cabeceras WebSocket/TCP/IP/TLS. El pico de bitmaps no es el RSS ni todo el heap.
Los tiempos son de aplicación en localhost; no son una medición de Internet.

## Ejecutar

Requisitos de pruebas: Java/Maven, Node, Playwright externo y Chromium/Edge.
No se añaden dependencias de producción. El directorio de salida debe existir.

### PNG sintético determinista

```bash
bash scripts/build.sh
NODE_PATH=/tmp/opencode/node_modules BROWSER_EXECUTABLE_PATH=/usr/bin/chromium-browser node scripts/benchmark-viewer.cjs .build/server.jar --output /tmp/opencode/p7-synthetic.json --repetitions 1 --steps 10
```

Genera PNG RGB de 3073×1537, sube en bloques de 1 MiB y mide tiempo de ingesta.
No es una prueba con originales gigantes ni números reales del curso.

### Imagen local ya procesada

```bash
NODE_PATH=/tmp/opencode/node_modules BROWSER_EXECUTABLE_PATH=/usr/bin/chromium-browser node scripts/benchmark-viewer.cjs .build/server.jar --work data/work --image 000-052-800-100032 --output /tmp/opencode/p7-local.json --repetitions 1 --steps 10
```

Este ID corresponde al dataset local disponible al ejecutar esta revisión;
sustituirlo por un ID `ready` existente. Se copian metadata y tiles a una carpeta
temporal, no el `source.part`. No mide otra vez la ingesta ni altera el dataset
original; para datasets enormes se necesita espacio para esa copia de tiles.
`sourceBytes`/`sourceSha256` provienen del registro de ingesta anterior.

Windows, con Playwright en `NODE_PATH` y Edge instalado:

```powershell
node scripts/benchmark-viewer.cjs .build/server.jar --work data/work --image MI_ID --output reporte-p7.json --repetitions 3 --steps 10
```

`BROWSER_CHANNEL=chrome` o `BROWSER_EXECUTABLE_PATH` permiten cambiar navegador.
Se recomiendan al menos tres repeticiones y más recorridos antes de sacar
conclusiones estadísticas. La herramienta falla si una ventana cambia los píxeles.

## Evidencia inicial ejecutada: 18 casos aprobados

Fedora, Java 21.0.12.1, Node 22.23.2, Chromium 154.0.8037.92.
Una repetición por caso, viewport 1440×1000, DPR 1, escena nativa q3.

### Dataset local preparado

4193×4193, 6 niveles, tiles de 256 px, original registrado de **52 832 474 bytes**.
No es el original de 93 GB. Igualdad de hashes entre ventanas en los tres escenarios.

| Escena | Ventana de payload | Primer dibujo (ms) | ACKs completos (ms) | Payload total (bytes) | Payload reenviado (bytes) |
|---|---:|---:|---:|---:|---:|
| Reposo | Tile entero | 25,5 | 160,6 | 423 192 | 0 |
| Reposo | 1500 | 62,1 | 3945,0 | 423 192 | 0 |
| Reposo | 16000 | 29,1 | 595,1 | 423 192 | 0 |
| ACK omitido | Tile entero | 27,6 | 2312,5 | 476 485 | 53 293 |
| ACK omitido | 1500 | 60,4 | 4695,8 | 476 485 | 53 293 |
| ACK omitido | 16000 | 27,7 | 2346,7 | 476 485 | 53 293 |
| Pan | Tile entero | 14,5 | 712,7 | 1 906 339 | 0 |
| Pan | 1500 | 64,9 | 3112,7 | 474 095 | 0 |
| Pan | 16000 | 29,2 | 961,7 | 902 118 | 0 |

Pico máximo de bitmaps: **8 720 448 bytes (~8,32 MiB)**, presupuesto 32 MiB.
Tras pan/TTL se mantuvieron únicamente tiles útiles y respaldo en las tres ventanas.
Se registraron cancelaciones parciales (21/56 tiles para entero/1500 en esa corrida)
y bytes que llegaron fuera de alcance; no se supone que la cancelación elimina
instantáneamente los bytes ya enviados.

### PNG sintético poco compresible

3073×1537, tiles de 256 px. En reposo las tres ventanas entregaron exactamente
**4 547 079 bytes** de payload y los mismos hashes finales:

| Ventana | Primer dibujo (ms) | ACKs completos (ms) |
|---|---:|---:|
| Tile entero | 21,2 | 557,2 |
| 1500 bytes | 72,5 | 39 666,2 |
| 16000 bytes | 52,8 | 4286,3 |

Los nueve casos sintéticos aprobaron, incluyendo ACK omitido y pan/TTL.
La ventana pequeña introduce muchos ciclos de ACK y overhead de JSON; la prueba
demuestra que se conserva detalle, no que 1500 bytes sea una configuración óptima.
Una corrida no permite afirmar superioridad general de una política.

Reportes completos de esta sesión: `/tmp/opencode/p7-local.json` y
`/tmp/opencode/p7-synthetic.json` (temporales, regenerables con los comandos).
Las capturas usan el mismo nombre base con `.desktop.png` y `.mobile.png`.

## Para continuar P7

1. Inventariar los originales reales y ejecutar ingesta con medición RSS/disco.
2. Repetir navegación y legibilidad sobre la escalera de tamaños hasta 93 GB.
3. Repetir en Windows, múltiples clientes y red del entorno de evaluación.
4. Comparar prioridades/estrategias de recuperación y frames binarios con escenas
   equivalentes, registrando distribuciones y trabajo útil. No se implementa una
   comparación SR/GTP-RA calibrada en esta herramienta inicial.
5. Optimizar la preparación del ensayo para datasets enormes sin copiar todos los
   tiles, manteniendo aislamiento y evitando confundir caché caliente/fría.
