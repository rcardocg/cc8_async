# Fase 05 — Recursos y monitoreo

**Bitácora histórica por fecha.** Políticas actuales: [RFC §10](../../protocolo.md#10-políticas-de-memoria-y-ttl).

## 2026-10-02 · Implementación y resolución

**Problemas:** cola ilimitada, ausencia de límite de bytes y estado compartido;
`memory_pressure` reducía la ventana sin examinar sus campos; no había salida útil
de métricas ni política de liberación en el cliente.

**Resolución:**
- Lotes hasta 128 entradas, hasta 32 posiciones por ventana y 4 MiB comprimidos
  retenidos por sesión; máximo 64 conexiones. Máximo 512 KiB por tile.
- `memory_pressure` valida uso/límite y aplica ventanas receptoras 32/4/2 a niveles
  <60%, >=60% y >=80%. Recuperar recursos permite subir otra vez el límite receptor.
- `adjust_strategy` comunica la política efectiva; `decrease_quality` permanece
  falso porque todavía no hay recodificación de imágenes.
- `transfer_state` informa ventana, pendientes, bytes en vuelo, RTT suavizado y RTO.
- Cliente conserva sólo la región actual, revoca URLs temporales y acota el log.
- Diagnóstico separa simulación manual de memoria de mediciones reales de aplicación.

**Verificación:** pruebas de límites por bytes, memoria alta/recuperada, entradas
inválidas y dos clientes; prueba E2E confirma respuestas de presión simulada.

**Corrección documental:** la fórmula LRU/LFU de la propuesta tenía signos que
favorecían expulsar tiles frecuentes y mezclaba unidades sin normalizar. Se anotó
el defecto en el histórico; no se implementó esa fórmula incorrecta.

**Estado:** control básico implementado. No existe caché compartida LRU/LFU, cálculo
de hit rate, estimación de ancho de banda/FPS ni telemetría automática de memoria.
Los 4 MiB acotan payloads, no todo el heap; WebSocket/JSON/Base64 agregan buffers.
Se requieren mediciones con los datasets reales antes de ajustar límites y optimizar.

## 2026-10-05 / 2026-10-06 · P4–P7 — registro recopilado el 2026-10-07

**Evolución registrada:** P4 incorporó variantes q0–q3 y caché LRU acotada por
bytes. P5 la sustituyó por LFU con envejecimiento y TTL. Se conservan ambos hechos
en sus [ejecuciones fechadas](../historico/verificaciones.md).

**Motivo de la política actual:** frecuencia para reutilización, envejecimiento
para reducir popularidad antigua y TTL para retirar inactividad. No usa LRU/FIFO
ni la fórmula híbrida errónea de la propuesta; no se afirma superioridad general
sin comparación experimental.

Cliente: bitmaps estimados como RGBA, protección de visibles/respaldo, expulsión
espacial y TTL fuera de vista. Presupuestos codificados y decodificados separados
evitan equiparar compresión con consumo de RAM.

**Evidencia original:** suites de caché/calidades, E2E y [benchmark P7](../experimentos_p7.md).
Los ensayos del visor q3 no ejercitan el costo temporal del histograma q0 (~64 MiB).
Contadores de caché/bitmaps no representan todo el heap/RSS ni telemetría exacta
del navegador. Adaptación automática y mediciones con originales siguen pendientes.
