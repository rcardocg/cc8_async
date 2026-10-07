# Fase 02 — WebSocket, control y recuperación

**Bitácora histórica por fecha.** Contrato actual: [GTP/1](../protocolo.md).

## 2026-10-02 · Implementación y resolución

**Problemas:** una sola cola/ventana para todos los clientes; cada conexión borraba
trabajo ajeno; cualquier ACK liberaba posiciones; no había timeout ni recuperación;
la supuesta avoidance crecía igual que slow start; latencia enviada por el cliente
era ficticia; faltaban validación y limpieza.

**Resolución:**
- Handler administrado por Spring e inyectado en la configuración WebSocket.
- Estado completo por sesión: cola, solicitud, mapa de transferencias pendientes,
  ventana, umbral, RTT/RTO, memoria y resultados. Cierre/error libera ese estado.
- Contrato versionado GTP/1, ACKs correlacionados por solicitud/transferencia/tile.
- Ventana deslizante: slow start `+1` por ACK y avoidance `+1/cwnd`; máximo 32.
- RTT monotónico medido por el servidor; no se toma `latency_ms` del cliente.
- Timeouts con backoff, reenvío selectivo, cuatro intentos máximos y error terminal.
- Validación atómica de lotes, duplicados consolidados y errores JSON estructurados.
- Hilos virtuales por trabajo, serialización por sesión con `ReentrantLock`; no se
  espera I/O de un cliente desde el temporizador global.

**Verificación:** `TileWebSocketHandlerTest` cubre clientes intercalados, ACKs falsos,
duplicados y tardíos, recuperación/exhaustión, cancelación, ventana y byte budget.
Incluye un lector bloqueado para demostrar que otro cliente y el planificador
continúan. `ServerIntegrationTest` prueba retransmisión sobre un WebSocket real.

**Estado:** base implementada. Es control de consumo de tiles sobre TCP, no una
implementación TCP. No hay ACK por rangos/SACK ni persistencia de sesiones. Detalle
de algoritmos, mensajes y límites: [protocolo.md](../protocolo.md).

## 2026-10-06 · Continuación P5 — registro recopilado el 2026-10-07

**Necesidad:** el incremento multinivel requirió corregir el handler activo y
mantener consistentes cola, cancelación y presupuestos durante la recuperación.

**Cambio registrado:** extracción de cola y avance después de fallos de lectura,
cancelación parcial de tiles en vuelo, RGBA calculado por dimensiones, contadores
independientes y SRTT/RTTVAR/Karn. Fragmentación opcional con ACK de fragmento para
liberar crédito y ACK de tile tras decodificar. Timeout reinicia el tile completo.
Las pruebas instancian el handler V2 registrado en `WebSocketConfig`.

**Evidencia original:** [primer incremento P5/P6](../historico/renderizado_p5.md)
y [verificación del 2026-10-06](../historico/verificaciones.md#2026-10-06--p5p6-primer-renderizado-multinivel-y-ttl).
Esta recopilación no ejecuta nuevamente esos casos.

**Límites:** sigue siendo GTP/1; no hay SACK por rangos, recuperación selectiva de
fragmentos ni reanudación persistente entre sesiones.
