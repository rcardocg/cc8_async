# Bitácoras de implementación y resolución

Revisión: **2026-10-02**. Se conserva la numeración de cinco fases de comunicación
de la propuesta; fase 00 registra la preparación transversal. Cada archivo indica
problema, resolución, evidencia y pendientes. «Base implementada» no significa que
la evaluación final con imágenes gigantes esté completada.

| Fase | Registro | Estado |
|---|---|---|
| 00 | [Entorno y trazabilidad](fase_00_entorno.md) | Java 21 y verificación reproducible |
| 01 | [Bootstrap HTTP y metadata](fase_01_bootstrap.md) | Catálogo y validación implementados |
| 02 | [WebSocket y recuperación](fase_02_websocket.md) | Protocolo por sesión implementado |
| 03 | [Región visible y cliente](fase_03_viewport.md) | Navegación X/Y y cancelación; predicción pendiente |
| 04 | [Transferencia de tiles](fase_04_tiles.md) | PNG/JPEG reales; importación gigante y niveles pendientes |
| 05 | [Recursos y monitoreo](fase_05_recursos.md) | Límites/telemetría básicos; caché y adaptación pendientes |

La bitácora antigua mezclaba las fases 1 y 2 en `bitacora_fase1.md` y llamaba a la
simulación «TCP exacto». Se conserva como registro histórico, con una corrección
visible. La propuesta anterior se conserva en `docs/propuesta_original.md`.

Para próximas implementaciones, agregar entradas fechadas a la fase correspondiente
con: **objetivo → fallo observado → cambio → verificación → límites pendientes**.
No registrar como completadas características descritas únicamente en propuestas.
