# Historial de verificaciones

Entradas conservadas desde `docs/verificacion.md` el **2026-10-07**. Cada sección
describe lo ejecutado y pendiente en su fecha; no constituye una nueva corrida
ni un contrato alternativo. Los comandos actuales y la matriz de cobertura están
en [verificación](../verificacion.md); la especificación, en el [RFC](../../protocolo.md).

## Cómo leer este registro

- Las cifras 21/26/40/53/71/77 pertenecen a revisiones y ambientes distintos.
- Una prueba omitida no cuenta como aprobada; se conserva la causa de la omisión.
- Las brechas indicadas en 2026-10-05 se leen junto a las continuaciones del
  2026-10-06, sin modificar retrospectivamente lo que se había ejecutado.
- Las rutas temporales y versiones de herramientas describen los equipos de
  aquella ejecución. Para repetir, usar los procedimientos actuales.

[Índice de historia y decisiones](README.md).

## 2026-10-06 · Continuación P6/P7

- P6: flujo integrado sin segmento «Visor de tiles preparados», respaldo nivel 0
  protegido, cancelación parcial durante gestos, progreso de cobertura, teclado,
  puntero primario, layout móvil y movimiento reducido. Skill `emil-design-eng`.
- Build Java: **77 casos aprobados**, sin fallos/errores/omitidas.
- E2E Chromium ampliado: respaldo después del TTL, registro/selección automática,
  navegación, móvil y preferencias de movimiento, además de regresiones previas.
- P7: **18 casos aprobados** (9 sobre imagen local 4193×4193, original registrado
  de 52 832 474 bytes; 9 sobre PNG sintético 3073×1537). SHA-256 de tiles finales
  idénticos entre ventanas 0/1500/16000; ACK omitido, pan/cancelación, TTL y límite
  de bitmaps verificados. Pico local ~8,32 MiB de bitmaps, no RSS/heap total.
- Capturas desktop/móvil revisadas; resultados y metodología en
  [experimentos_p7.md](../experimentos_p7.md). No equivale a validar 93 GB.

## 2026-10-06 · P5/P6: primer renderizado multinivel y TTL

- Fedora, Java 21.0.12.1 / Maven 3.9.11 / Node 22.23.2 / Chromium headless.
- `bash scripts/build.sh`: 77 pruebas Java, sin fallos/errores/omitidas.
- `verify-browser.cjs`: aprobado con procesamiento de PNG sintético 3073×1537,
  zoom reducido/nativo, píxeles RGB comparados con fuente, TTL y fragmentación
  de 1500 bytes, navegación/reconexión y regresión de dos clientes.
- Handler activo probado; cola y contadores corregidos, cancelación en vuelo,
  recuperación, presupuesto RGBA por dimensiones. Tile máximo 2 MiB.
- Caché actual **LFU con envejecimiento + TTL**, reemplaza la LRU histórica de P4.
- Registro del incremento, evidencia y pendientes: [renderizado_p5.md](renderizado_p5.md).
- No hay evidencia con originales gigantes del curso ni repetición P5 en Windows.

## 2026-10-05 · P4: calidades progresivas y caché de tiles

### Resumen P4

- **TileQualityService**: genera q0 (preview 64×64 PNG indexado), q1 (JPEG 50%), q2 (JPEG 85%), q3 (PNG nativo) bajo demanda desde el tile nativo.
- **TileCacheService**: caché LRU con límite configurable en bytes (default 50 MiB vía `tiles.cache-bytes` / `GTP_TILE_CACHE_BYTES`), estadísticas de hits/misses/evictions.
- **TileService**: usa caché y servicio de calidades para imágenes multinivel (P3); catálogo plano y demo siguen funcionando sin cambios.
- **WebSocket**: acepta `z` y `q` en `fetch_tiles`; responde con `z` y `q` en `tile_data`; catálogo plano rechaza `z`/`q` para mantener compatibilidad.

### Matriz de ambientes P4

| Ambiente | Evidencia disponible |
|---|---|
| Fedora | P0–P3 verificados previamente; P4 pendiente de ejecución. |
| Windows | Temurin 21.0.11, Maven 3.9.9, Node 24.18.0, Edge headless. **71 pruebas, 0 fallos, 0 errores, 1 omitida (POSIX)**. P0–P4 cubiertos. |
| Git Bash sobre Windows | `bash -n scripts/build.sh scripts/gtp.sh` correcto y `gtp.sh cli help` desde otra carpeta. No equivale a probar Linux/Fedora. |

### Cobertura P4

| Suite | Casos | Cobertura principal |
|---|---:|---|
| `TileQualityServiceTest` | 6 | q0 indexed PNG 64×64, q1/q2 JPEG, q3 identity, alpha, tamaños relativos |
| `TileCacheServiceTest` | 10 | LRU, límite de bytes, eviction manual, stats hits/misses/evictions, oversized rejection |
| `P4QualityAndCacheIntegrationTest` | 2 | Ingesta HTTP → ready, calidades disponibles, endpoint `/api/cache/stats` |
| `TileWebSocketHandlerTest` | 13 | Regresión GTP: ACK/timeout/ventanas/cancelación/dos clientes |

- `scripts/build.cmd`: **BUILD SUCCESS** (tests), `.build/server.jar` generado.
- `verify-browser.cjs`: **PASS**. P1/P2/P3 + demo, dos clientes, retransmisión, memoria, cancelación.
- `verify-ingestion.cjs`: **PASS**, JAR real `-Xmx64m`, PNG sintético 8193×4097 → 783 tiles / 7 niveles, SHA-256 verificado, persistencia entre reinicios JVM.
- `node --check` y `git diff --check` correctos.

**Límites:** no mide RSS/pico de disco; no validado con PNG del curso ni 16 bits/Adam7. La visualización multinivel y protocolo GTP ampliado siguen en P5/P6. [Referencia de calidades](../calidades_p4.md).

### Brecha Fedora para P4 (pendiente de cierre)

| Qué falta en Fedora | Cómo verificarlo |
|---|---|
| Ejecutar `bash scripts/build.sh` completo | 71 pruebas: 0 fallos, 0 errores, 1 omitida (POSIX) |
| `verify-ingestion.cjs` con heap 64 MiB | PNG 8193×4097 → 783 tiles/7 niveles, SHA-256, persistencia cross-JVM |
| `verify-browser.cjs` con Chromium/Playwright | P1/P2/P3/P4 + demo, dos clientes, retransmisión, memoria, cancelación |
| Comprobar permisos POSIX en `ImagesLayoutTest` | La prueba omitida en Windows debe pasar en Fedora |
| Verificar paths con espacios y solo-lectura en originales | Tests existentes cubren esto |

**Nota:** La evidencia Fedora previa (P0–P3) está documentada en bitácoras; **no se le atribuye** la ejecución de código P4 nuevo. La repetición en Fedora del código actual es requisito para cerrar P4.

---

## 2026-10-05 · P2 reproducido y primer incremento P3

### Matriz de ambientes P2/P3

| Ambiente | Evidencia disponible |
|---|---|
| Fedora | P0/P1/P2 verificados previamente; cierre P2 documentado con 40 pruebas Java y E2E Chromium. La ejecución del incremento P3 nuevo está pendiente. |
| Windows | Temurin 21.0.11, Maven 3.9.9, Node 24.18.0 y Edge headless. Base P2: 40 casos, 39 aprobados/1 omitido. Con P3: **53 casos, 52 aprobados, 0 fallos, 0 errores, 1 omitido**. |
| Git Bash sobre Windows | `bash -n scripts/build.sh scripts/gtp.sh` correcto y `gtp.sh cli help` desde otra carpeta. No equivale a probar Linux/Fedora. |

La omisión es la prueba de permisos POSIX de `ImagesLayoutTest`; Windows no ofrece
ese atributo. La evidencia Fedora previa se conserva en la bitácora, sin atribuirle
la ejecución de código nuevo. El build usa `.build/`; se conservaron los cambios
preexistentes de binarios/configuración en `target/classes`.

### Comandos reproducibles P2/P3

Windows (PowerShell, Maven y Java en PATH):

```powershell
.\scripts\build.cmd
node scripts/verify-ingestion.cjs .build/server.jar
node scripts/verify-browser.cjs .build/server.jar
node --check src/main/resources/static/viewer.js
git diff --check
```

Fedora (repetir con el mismo código):

```bash
bash scripts/build.sh
node scripts/verify-ingestion.cjs .build/server.jar
NODE_PATH="/ruta/herramientas/node_modules" BROWSER_EXECUTABLE_PATH="/usr/bin/chromium" node scripts/verify-browser.cjs .build/server.jar
```

En este Windows Maven no estaba en PATH: se añadió **solo a la sesión de prueba**
la instalación existente en el directorio temporal de herramientas. Playwright
también se tomó de herramientas externas mediante `NODE_PATH`. No se cambiaron
configuraciones globales, rutas de Fedora, dependencias de producción ni el IDE.
Usar las rutas reales de herramientas/navegador de cada equipo.

### Cobertura y resultado

| Suite | Casos | Cobertura adicional/principal |
|---|---:|---|
| `GtpCliTest` | 8 | P1 y dry-run sin escrituras, límites y variantes |
| `SequentialPngDecoderTest` | 6 | Cinco filtros, IDAT fragmentados, grayscale/paleta/alpha, SHA, corrupción/finalización, presupuesto, interrupción, píxeles de todos los niveles, promedio con alpha |
| `PngIngestionIntegrationTest` | 1 | HTTP real: límites, offsets, duplicados, fuente incompleta, proceso 202→ready |
| `ServerIntegrationTest` | 4 | HTTP P1/P2 y WebSocket/recuperación anteriores |
| `ImageRegistryTest` | 4 | Registro P2, persistencia, conflictos, estados y coordenadas |
| `ImagesLayoutTest` | 4 | Rutas, separación, permisos; una omisión POSIX en Windows |
| `MetadataAndTileServiceTest` | 7 | Catálogo plano y tiles anteriores |
| `PngIngestionServiceTest` | 6 | Subida/reinicio, corrupción/reintento, originales intactos, locks/rutas, cancelación, CLI real |
| `TileWebSocketHandlerTest` | 13 | Control GTP existente, ACK/timeout/ventanas/cancelación |

- `scripts/build.cmd`: **BUILD SUCCESS**, `.build/server.jar` generado.
- `scripts/gtp.cmd cli help`: ejecutado desde otra carpeta, con espacios en la ruta
  del repositorio; despacha CLI sin Spring.
- `verify-browser.cjs`: **PASS**. P1 (solo 33 bytes, preview, informe), P2
  (pending/duplicado/catálogo), P3 (subida y pirámide ready), demo, dos clientes,
  retransmisión, memoria simulada, cambios rápidos, cancelación y reconexión.
  Ninguna petición externa y ningún error JavaScript.
- Se detectó un timeout del E2E al esperar mediante frames de animación en una
  pestaña de fondo de Edge. La comprobación de finalización usa ahora sondeo cada
  100 ms, independiente del pintado, y emite diagnóstico de frames si falla. El
  recorrido completo pasó después del ajuste.
- `verify-ingestion.cjs`: **PASS**, desde directorio temporal con espacios,
  JAR real y `-Xmx64m`. PNG RGB sintético íntegro de **8193 × 4097**,
  3 629 486 bytes comprimidos; RGBA completo equivaldría a **134 266 884 bytes**.
  Generó **783 tiles / 7 niveles**. SHA-256 esperado y obtenido:
  `5a5491c5f3d4d3788b3a446c9594ec9ef78d9ee18a588bda2be447eccb2013b1`.
  Dos JVM de servidor distintas verificaron persistencia de pending, ready y
  offset de subida de **1 MiB**, además del conflicto por ID duplicado.
- `node --check` de cliente y ambos scripts correcto. Los informes JUnit quedan
  en `.build/surefire-reports/`; los smokes limpian sus temporales.

**Límites de la evidencia:** no mide RSS ni pico de disco; no se ejecutó con los
PNG del curso ni con 16 bits/Adam7. El archivo sintético de este smoke sí tiene
IDAT/IEND decodificables, a diferencia del disperso de 93 GB usado para P1.
La visualización multinivel y el protocolo ampliado siguen en P5/P6; no se afirma
validación física sin red ni legibilidad final. [Procedimiento P3](../ingesta_p3.md).

---

## Histórico · revisión 2026-10-02

Fecha: 2026-10-02. Entorno: Windows, Temurin Java 21.0.11, Maven 3.9.9.

### Comandos reproducibles de la revisión 2026-10-02

```powershell
mvn "-Dgigapixel.buildDirectory=.build" test package
node --check src/main/resources/static/viewer.js
git diff --check
```

`gigapixel.buildDirectory` permite generar resultados sin usar los binarios
versionados previamente en `target/`. En este entorno Maven se invocó mediante la
ruta completa a `apache-maven-3.9.9/bin/mvn.cmd` dentro del directorio temporal de
herramientas, ya que no estaba en PATH.

### Pruebas automatizadas

| Suite | Casos | Cobertura |
|---|---:|---|
| `MetadataAndTileServiceTest` | 6 | Catálogo real, límites, 404, grandes conteos, lectura y dimensiones de tiles |
| `TileWebSocketHandlerTest` | 13 | Aislamiento, ACKs, ventanas, backoff, fallo terminal, memoria, cancelación y trabajo asíncrono |
| `ServerIntegrationTest` | 2 | HTTP/recursos y WebSocket real con decodificación PNG y ACK omitido |

Primera ejecución: **21 pruebas, 0 fallos, 0 errores**. La primera invocación anterior
a esa ejecución detectó una colisión de nombre de propiedad Maven, corregida en
fase 00. La verificación final de empaquetado y navegador se registra abajo.

#### Resultado final ejecutado

- Maven `test package`: **BUILD SUCCESS**, 21 pruebas, 0 fallos, 0 errores, 0 omitidas.
- JAR ejecutable generado: `.build/server-0.0.1-SNAPSHOT.jar`.
- `node --check` sobre `viewer.js` y `scripts/verify-browser.cjs`: correcto.
- `git diff --check`: sin errores de whitespace; Git avisó de conversión LF/CRLF
  según la configuración ya existente del entorno.
- Playwright 1.58.2 con Edge headless: **PASS** en todos los escenarios descritos abajo.
  La aplicación del JAR arrancó en un puerto libre y se detuvo al finalizar la prueba.
- Los artefactos de pruebas JUnit están en `.build/surefire-reports/` (no versionados).

Durante la revisión se reemplazaron los monitores `synchronized` alrededor de la
E/S por `ReentrantLock` para evitar fijar carriers de hilos virtuales en Java 21;
la ejecución final de las suites corresponde a esa versión corregida.

### Prueba de navegador

`scripts/verify-browser.cjs` usa Playwright con Edge instalado, arranca el JAR en
un puerto libre y verifica:

1. Tiles PNG decodificados, 12 imágenes por región.
2. Dos clientes sin mezcla de estado.
3. Retransmisión tras omitir un ACK de aplicación.
4. Ventana receptora al simular presión de memoria y recuperarla.
5. Dos cambios rápidos de región sin repintado tardío.
6. Cancelación, limpieza y reconexión.
7. Cero errores JavaScript y recursos HTTP del mismo origen.

Dependencias opcionales: Node.js, paquete `playwright` y Edge (o Chrome mediante
`BROWSER_CHANNEL=chrome`). Playwright puede instalarse fuera del repositorio y
exponerse por `NODE_PATH`; no es una dependencia de ejecución del servidor.

### Pendiente de evaluación real en ese corte

No están incluidos los datasets gigantes ni un pipeline para convertir sus
originales. Estas pruebas verifican el protocolo y la base del visor con tiles
pequeños; no certifican memoria/latencia/legibilidad con archivos de 24–93 GB.
La prueba de navegador exige recursos locales, pero no sustituye desconectar la
red durante la evaluación final. Usar DevTools para revisar frames, transferencias,
caché, calidad numérica y consumo de recursos con los datasets oficiales.
