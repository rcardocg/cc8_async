# P5/P6 — primer visor multinivel, fragmentos y TTL

**Registro histórico del incremento del 2026-10-06**, archivado el 2026-10-07.
Ubicación original: `docs/renderizado_p5.md`. Conserva correcciones, pruebas y
límites de ese corte; la [guía actual del visor](../visor_p6.md) incorpora su
continuación. [Índice histórico](README.md).

Fecha: 2026-10-06. Implementación compartida Java/JS, ejecutada en Fedora.

**Continuación P6/P7:** el respaldo de nivel 0 ya está protegido y solicitado
explícitamente; la interfaz y cola de vistas se completaron en
[visor_p6.md](../visor_p6.md). Primer benchmark/evidencia en
[experimentos_p7.md](../experimentos_p7.md). Las notas siguientes registran el
primer incremento P5/P6 previo a esa continuación.

El documento principal vigente es el [RFC interno GTP-001](../../protocolo.md).

## Resultado de esta fase

El original PNG completo se recibe/procesa en Java. Después el navegador solicita
únicamente regiones visibles en la pirámide. Ajustar muestra toda la imagen a un
nivel reducido; acercar selecciona niveles superiores; 1:1 entrega PNG nativo q3.
Canvas permite arrastrar y zoom al cursor. En la continuación P6, el nivel 0 se
solicita primero y se fija como respaldo; otros niveles inferiores disponibles
son temporales y expiran fuera de alcance.

Se corrigió el handler activo: extracción de cola, avance tras fallo de lectura,
cancelación parcial en vuelo, presupuesto de decodificación por dimensiones,
contadores independientes, SRTT/RTTVAR y recuperación de tiles necesarios incluso
con presión de memoria. Las pruebas ahora instancian el handler registrado en
`WebSocketConfig`, no el handler histórico.

La caché del servidor usa **LFU con envejecimiento + TTL**, sin FIFO/LRU.
Cliente: utilidad espacial y TTL de 5 s fuera de vista; cierre explícito de bitmaps;
presupuesto 32 MiB y presión calculada con bytes RGBA de bitmaps.
Servidor: 50 MiB, TTL 30 s, ambos configurables mediante
`GTP_TILE_CACHE_BYTES` y `GTP_TILE_CACHE_TTL_MS`.

## Fragmentación y conservación de detalle

MTU/MSS son de red, independientes del zoom. TCP reconstruye el flujo de bytes.
GTP ofrece opcionalmente una ventana de fragmentos de aplicación, configurable
en Diagnóstico. `tile_fragment` y `ack_fragment` permiten continuar antes de tener
el tile completo; `ack_tile` confirma su consumo después de reconstrucción y
decodificación. Con 5000 bytes y ventana 1500: 1500+1500+1500+500. El formato PNG
no se recodifica para caber: la ventana afecta tiempo, no detalle final.

La ventana cuenta payload binario antes de Base64, no tamaño de paquetes/JSON.
Timeout reinicia el tile completo; retransmisión selectiva de fragmentos es futura.
Se limita un tile a 2 MiB y los payloads retenidos/reconstruidos por sesión a 4 MiB;
los contadores no incluyen todos los buffers transitorios ni todo el heap.

## Evidencia ejecutada

- `bash scripts/build.sh`: **77 pruebas Java, 0 fallos, 0 errores, 0 omitidas**.
- E2E Chromium con Playwright: aprobado. Incluye subida/procesamiento de PNG
  sintético RGB de 3073×1537, tiles de 512 px poco compresibles, selección reducida
  al ajustar, nivel nativo, comparación exacta de un píxel RGB contra la fuente,
  expiración de bitmaps fuera de vista, ventana de 1500 bytes, navegación/reconexión,
  y regresión P1/P2/P3/demo/dos clientes/omisión de ACK/recursos del mismo origen.
- Java verifica además: reconstrucción exacta de 5000 bytes pseudoaleatorios,
  ACK prematuro/duplicado, timeout de fragmento, cancelación y presupuestos de bytes.

Reproducción en Fedora:

```bash
bash scripts/build.sh
NODE_PATH=/tmp/opencode/node_modules BROWSER_EXECUTABLE_PATH=/usr/bin/chromium-browser node scripts/verify-browser.cjs .build/server.jar
bash scripts/gtp.sh
```

Playwright es herramienta de pruebas externa al proyecto; en este equipo está en
`/tmp/opencode/node_modules`. Para otros equipos usar su instalación y navegador.
Windows: `scripts\build.cmd`, `scripts\gtp.cmd`; E2E con Edge según README.
El script E2E levanta/detiene un servidor temporal y limpia sus datos.

## Pruebas manuales para esta fase

Abrir `http://localhost:8081/`, recargar recursos del navegador y usar un PNG
compatible (sin Adam7, hasta 8 bits), preferiblemente con números y dimensiones
superiores al visor. Elegir, registrar un ID nuevo, transferir/procesar hasta
`ready`; el registro queda seleccionado automáticamente en **Tu imagen**.

| Prueba | Acción | Resultado esperado |
|---|---|---|
| Vista general | Pulsar **Ajustar imagen** | Imagen completa; nivel inferior al nativo si sus dimensiones lo requieren. |
| Detalle | Pulsar **1:1**, acercar y recorrer números | Nivel `maxZoom`, PNG q3 sin pérdida; no se descarga todo el original. |
| Pan/zoom | Arrastrar y girar rueda sobre un punto | Navegación y zoom centrado en cursor; solo tiles de la nueva región. |
| Bordes | Recorrer esquinas y extremos | Tiles parciales correctos; sin solicitudes fuera del rango. |
| Ventana pequeña | Diagnóstico → 1500 bytes; cancelar/cargar para evitar solo hits de caché | La transferencia completa termina; en DevTools WS se ven `tile_fragment`/`ack_fragment`. |
| Igualdad | Comparar región nativa con ventana 0 y 1500 | Mismo detalle final; diferente número de mensajes/tiempo. |
| Recuperación | Activar Omitir un ACK y cargar una región nueva | Reintento `attempt=2`; termina sin tiles perdidos. |
| Cambio rápido | Mover/zoom varias veces durante transferencia | Vista anterior no reemplaza la nueva; trabajo antiguo se cancela. |
| TTL cliente | Cambiar región o nivel y esperar más de 5 s | Contador baja a visibles más respaldo nivel 0; ambos permanecen. |
| TTL servidor | Consultar `/api/cache/stats`, dejar de solicitar tiles 31 s y consultar otra vez | Expiran entradas inactivas; `policy` es `LFU_AGING_TTL`. |
| Presión | Simular 90% y cargar otra región | Ventana receptora baja; tiles necesarios siguen recuperándose. |
| Dos clientes | Abrir segunda pestaña, navegar/cancelar solo en una | La otra mantiene su región y transferencia. |
| Reconexión | Pulsar Reconectar | Se vuelve a solicitar/renderizar la imagen seleccionada. |

## Plan de ataque siguiente

1. Inventariar los PNG reales de 32 KB a 93 GB con P1: dimensiones, profundidad,
   color/Adam7; elegir originales compatibles y medir ingesta/RAM/disco.
2. Validar legibilidad a 1:1, bordes y navegación prolongada sobre esos originales;
   repetir esta fase en Windows. Las pruebas sintéticas no acreditan 93 GB reales.
3. Respaldo fijado, presentación/progreso y cola durante gestos ya implementados
   en P6; ampliar su validación sobre originales reales y dispositivos táctiles.
4. Medir tamaños de ventana y estrategias de orden/recuperación; evaluar frames
   binarios para reducir Base64 y recuperación por fragmento si aporta resultados.
5. Ampliar PNG de 16 bits/Adam7 según inventario; el decoder actual los rechaza.

El primer incremento usa q3 del nivel elegido; no implementa todavía refinamiento
q0→q3 adaptativo ni prefetch. La memoria mostrada corresponde a bitmaps estimados,
no una lectura exacta del heap del navegador ni una promesa de consumo total.
