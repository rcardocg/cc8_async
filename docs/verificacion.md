# Verificación — procedimientos y cobertura

Revisión documental: **2026-10-07**. Este archivo mantiene cómo verificar la
solución y dónde consultar evidencia. Los resultados de ejecuciones anteriores
se conservan íntegros por fecha en el [historial de verificaciones](historico/verificaciones.md).
La reorganización documental no representa una nueva ejecución Java/E2E.

## Matriz de trazabilidad

| Mecanismo | Código principal | Evidencia y alcance |
|---|---|---|
| Registro, offset, ingesta y publicación | `ImageRegistry`, `PngIngestionService`, decoder y builder | Suites Java de registro/ingesta/decoder e integraciones. El smoke verifica persistencia entre JVM; no acredita originales de 93 GB. |
| Ventana, ACK, timeout, cancelación y fragmentos | `TileWebSocketHandlerV2`, registrado en `WebSocketConfig` | `TileWebSocketHandlerTest`, integración WebSocket, E2E y benchmark. Control de aplicación, no TCP ni SACK por rangos. |
| LFU, envejecimiento y TTL | `TileCacheService` | `TileCacheServiceTest`: política, presupuesto y contadores. Límite de arrays cacheados, no RSS total. |
| Variantes q0–q3 | `TileQualityService` | `TileQualityServiceTest` e integración P4. El visor y benchmark piden q3; no prueban adaptación automática. |
| Nivel, respaldo, progreso y memoria cliente | `viewer.js`, `pyramid-view.js` | E2E: píxeles nativos, TTL, flujo integrado, teclado/móvil, reconexión y dos clientes. |
| Conservación entre ventanas y desperdicio durante gestos | `benchmark-viewer.cjs` | SHA-256 de todos los tiles finales, ACK omitido, cancelación y bitmaps. Metodología y resultados completos en [P7](experimentos_p7.md). |

El [RFC](../protocolo.md#14-verificación-y-resultados) relaciona estos mecanismos
con la solución. Las pruebas de estado usan el handler activo V2; el nombre de
esa clase no cambia la versión del canal, que sigue siendo GTP/1.

## Reproducir las pruebas

Desde la raíz del proyecto, con JDK 21 y Maven 3.9+ en PATH:

Windows / PowerShell:

```powershell
.\scripts\build.cmd
```

Fedora / Bash:

```bash
bash scripts/build.sh
```

Esperar **BUILD SUCCESS** antes de utilizar el JAR. Los lanzadores ejecutan pruebas
y empaquetan `.build/server.jar`; JUnit deja informes en `.build/surefire-reports/`.
Una prueba de permisos POSIX puede omitirse en Windows por falta de ese atributo.
Los conteos de casos dependen de la revisión; registrar aprobados, fallos, errores
y omisiones por separado.

### Smoke de ingesta

Con Node.js, sin Playwright:

```text
node scripts/verify-ingestion.cjs .build/server.jar
```

Genera un PNG sintético completo por filas y ejecuta el JAR con heap de 64 MiB.
Verifica SHA-256, niveles/bordes, estados y offset de subida entre procesos Java.
Limpia temporales al terminar. El heap configurado no es una medición del RSS.

### E2E de navegador

Requiere Playwright como herramienta de desarrollo y Edge/Chrome/Chromium instalado.
Usar rutas reales de herramientas, sin depender de una carpeta temporal anterior.

PowerShell:

```powershell
$env:NODE_PATH = 'C:/herramientas/node_modules'
$env:BROWSER_CHANNEL = 'msedge'
node scripts/verify-browser.cjs .build/server.jar
```

Bash:

```bash
NODE_PATH="/ruta/herramientas/node_modules" BROWSER_EXECUTABLE_PATH="/usr/bin/chromium" node scripts/verify-browser.cjs .build/server.jar
```

`BROWSER_CHANNEL=chrome` permite Chrome; `BROWSER_EXECUTABLE_PATH` especifica otro
ejecutable. El script arranca un servidor en un puerto libre y lo detiene al
finalizar. Playwright no es una dependencia de ejecución de la aplicación.

Comprueba el recorrido de inspección/registro/ingesta/visor, píxeles, respaldo,
TTL, fragmentación, teclado/móvil, recuperación y aislamiento entre clientes.
El sondeo de finalización no depende de que una pestaña de fondo dibuje frames;
la incidencia que motivó ese cambio está en la entrada P2/P3 del historial.

### Comprobaciones estáticas y benchmark

```text
node --check src/main/resources/static/viewer.js
node --check src/main/resources/static/pyramid-view.js
git diff --check
```

Para comparar ventanas 0/1500/16000, omisión de ACK, cancelación, bitmaps y hashes,
seguir [experimentos P7](experimentos_p7.md#ejecutar). Esa guía define métricas,
aislamiento, repeticiones y espacio necesario; el E2E no sustituye el benchmark.
Las comprobaciones manuales están en la [guía de diagnóstico](../GUIA_INICIO_Y_PRUEBAS.md#4-diagnóstico-del-protocolo)
y la [guía del visor](visor_p6.md#verificación-y-pruebas-manuales).

## Últimas ejecuciones documentadas

| Fecha / ambiente | Resultado registrado | Detalle |
|---|---|---|
| 2026-10-06 · Fedora, P5/P6/P7 | 77 casos Java aprobados, sin omisiones; E2E Chromium aprobado; 18 ensayos P7 aprobados | [Continuación P6/P7](historico/verificaciones.md#2026-10-06--continuación-p6p7) y [primer incremento P5/P6](historico/verificaciones.md#2026-10-06--p5p6-primer-renderizado-multinivel-y-ttl) |
| 2026-10-05 · Windows, P4 | 71 casos: 70 aprobados, 1 omitido por POSIX; E2E Edge y smoke de ingesta aprobados | [Ejecución P4](historico/verificaciones.md#2026-10-05--p4-calidades-progresivas-y-caché-de-tiles) |
| 2026-10-05 · Windows, P2/P3 | P2: 39 aprobados y 1 omitido; P3: 52 aprobados y 1 omitido; E2E/smoke aprobados | [Ejecución P2/P3](historico/verificaciones.md#2026-10-05--p2-reproducido-y-primer-incremento-p3) |
| 2026-10-02 · Windows, base GTP | 21 casos aprobados y E2E Edge aprobado | [Revisión inicial](historico/verificaciones.md#histórico--revisión-2026-10-02) |

La evidencia P0/P1 y el cierre P2 en Fedora también se conservan en las bitácoras
de [entorno](fases/fase_00_entorno.md) y [bootstrap](fases/fase_01_bootstrap.md).
Git Bash sobre Windows verifica los lanzadores en ese ambiente; no equivale a Fedora.

## Lo que falta demostrar

Repetir la última revisión en Windows; procesar originales gigantes compatibles;
medir RSS, disco y comportamiento con varios clientes; comprobar legibilidad de
números y funcionamiento sin Internet en el entorno de evaluación. Las pruebas
sintéticas, la demo y la inspección de un archivo disperso no acreditan ingesta
de una imagen real de 93 GB. Los pendientes técnicos se mantienen en el
[RFC, sección 15](../protocolo.md#15-pendientes-y-referencias).
