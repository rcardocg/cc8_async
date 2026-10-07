# Bitácoras de implementación y resolución

Revisión: **2026-10-06**. Documento principal: [RFC interno GTP-001](../../protocolo.md).
Se conserva la numeración de cinco fases de comunicación
de la propuesta; fase 00 registra la preparación transversal. Cada archivo indica
problema, resolución, evidencia y pendientes. «Base implementada» no significa que
la evaluación final con imágenes gigantes esté completada.

| Fase | Registro | Estado |
|---|---|---|
| 00 | [Entorno y trazabilidad](fase_00_entorno.md) | Java 21 y verificación reproducible |
| 01 | [Bootstrap HTTP y metadata](fase_01_bootstrap.md) | Catálogo y validación implementados |
| 02 | [WebSocket y recuperación](fase_02_websocket.md) | Protocolo por sesión implementado |
| 03 | [Región visible y cliente](fase_03_viewport.md) | Canvas multinivel, pan/zoom, respaldo y cancelación parcial; predicción pendiente |
| 04 | [Transferencia de tiles](fase_04_tiles.md) | Pirámide PNG y GTP/1 multinivel/fragmentación implementados; datasets gigantes pendientes |
| 05 | [Recursos y monitoreo](fase_05_recursos.md) | LFU envejecida+TTL, presupuestos/estimación RGBA y benchmark; adaptación automática pendiente |

Los registros fechados de cada archivo son históricos; sus límites se interpretan
en esa fecha, no como estado vigente. La numeración P0–P7 del plan de desarrollo es
distinta de estas fases de comunicación. Las continuaciones actuales están en
[P3](../ingesta_p3.md), [P4](../calidades_p4.md), [P5](../renderizado_p5.md),
[P6](../visor_p6.md) y [P7](../experimentos_p7.md).

La bitácora antigua mezclaba las fases 1 y 2 en `bitacora_fase1.md` y llamaba a la
simulación «TCP exacto». Se conserva como registro histórico, con una corrección
visible. La propuesta anterior se conserva en `docs/propuesta_original.md`.

Para próximas implementaciones, agregar entradas fechadas a la fase correspondiente
con: **objetivo → fallo observado → cambio → verificación → límites pendientes**.
No registrar como completadas características descritas únicamente en propuestas.
