# Fase 03 — Región visible y cliente

**Bitácora histórica por fecha.** Interacción actual: [guía del visor](../visor_p6.md).

## 2026-10-02 · Implementación y resolución

**Problemas:** sólo había una página fuera de los recursos Java, apuntando a un puerto
fijo, sin visor; ACKs con latencia aleatoria y logs DOM ilimitados; sin cancelación
ni aislamiento de respuestas de regiones antiguas.

**Resolución:**
- Cliente en `src/main/resources/static/`, servido desde `/` con CSS/JS locales.
- Descubre imágenes y dimensiones mediante HTTP; WebSocket usa el origen/puerto de
  la página y selecciona `ws`/`wss` según corresponda.
- Región de hasta 4×3 tiles a resolución nativa; navegación por filas/columnas.
- Cada solicitud tiene un nuevo ID; `replace` cancela pendientes del viewport anterior.
- Se ignoran respuestas viejas incluso si la decodificación termina después de
  cambiar de región o reconectar. Los bitmaps anteriores se retiran del DOM.
- ACK después de `image.decode()`, tratamiento de duplicados, cancelación y reconexión.
- URLs de blobs revocadas, máximo 150 líneas de log y mensajes insertados con
  `textContent`. `TestPatito/test-websocket.html` dirige al cliente alojado en Java.

**Verificación:** sintaxis JavaScript, HTTP de recursos locales y prueba E2E
`scripts/verify-browser.cjs`: decodificación, dos clientes, sustitución rápida,
cancelación, reconexión y ausencia de solicitudes externas.

**Estado:** base de navegación implementada. El nombre «fase 3» se conserva de la
propuesta, pero todavía no hay `viewport_update`, predicción de movimiento, prefetch,
zoom multinivel ni gestos. La cuadrícula actual muestra una región pequeña solicitada
explícitamente; no prueba el comportamiento requerido a escala de decenas de GB.

## 2026-10-06 · Continuación P6 — registro recopilado el 2026-10-07

**Objetivo:** integrar apertura, preparación y exploración manteniendo contexto
visual y evitando descartar trabajo útil durante gestos.

| Situación previa | Cambio registrado | Motivo |
|---|---|---|
| Secciones de laboratorio numeradas y grilla pública | Flujo archivo → preparación → exploración; demo plana en Diagnóstico | La interfaz sigue la tarea del usuario. |
| Vista podía quedar sin respaldo después del TTL | Solicitud explícita y protección de nivel 0; progreso de cobertura | Mantener contexto mientras llega detalle. |
| Cancelación total en cambios de región | Cancelación parcial y conservación de tiles útiles | Reducir trabajo desperdiciado durante navegación. |
| Interacción limitada a controles iniciales | Teclado, foco, captura de puntero primario, móvil y movimiento reducido | Navegación predecible y accesible. |

La nota original de P6 registra uso de la skill `emil-design-eng` en
`~/.claude/skills/`, sin nuevas dependencias de producción ni recursos externos.
Ese dato describe herramientas de aquella implementación, no un requisito de uso.

**Evidencia original:** E2E Chromium y capturas desktop/móvil registrados en
[verificación P6/P7](../historico/verificaciones.md#2026-10-06--continuación-p6p7).
Se conservan pruebas de respaldo/TTL, píxeles nativos, registro/selección,
teclado, progreso y layout de 390 px.

**Límites:** el visor pide q3 del nivel elegido. Predicción, prefetch y refinamiento
automático q0→q3 no están integrados; originales gigantes, legibilidad humana y
repetición de la última revisión en Windows permanecen pendientes.
