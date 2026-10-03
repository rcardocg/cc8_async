# Fase 03 — Región visible y cliente

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
