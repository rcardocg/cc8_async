# Bitácoras de implementación y resolución

Índice revisado el **2026-10-07**. Cada entrada conserva lo que cambió y lo que
se había verificado **en su fecha**. Para el comportamiento actual, leer el
[RFC interno GTP-001](../../protocolo.md) y el [contrato](../protocolo.md).

## Registros por área

| Fase | Bitácora | Qué registra |
|---|---|---|
| 00 | [Entorno y trazabilidad](fase_00_entorno.md) | Java/Maven, rutas, alcance PNG, ambientes y reorganización documental |
| 01 | [Bootstrap HTTP y metadata](fase_01_bootstrap.md) | Catálogo real, registro y persistencia |
| 02 | [WebSocket y recuperación](fase_02_websocket.md) | Aislamiento por cliente, ACK, ventanas, recuperación y correcciones P5 |
| 03 | [Región visible y cliente](fase_03_viewport.md) | De la grilla plana al canvas con respaldo y cancelación parcial |
| 04 | [Transferencia de tiles](fase_04_tiles.md) | Payload real, ingesta PNG, pirámides y límites de transferencia |
| 05 | [Recursos y monitoreo](fase_05_recursos.md) | Presupuestos, evolución de caché, TTL y alcance de las métricas |

La numeración 00–05 agrupa áreas de comunicación de la propuesta original; P0–P7
identifica incrementos de desarrollo. Las entradas recopiladas después de un
incremento indican la fecha de recopilación y enlazan su evidencia original.

## Antecedentes y evidencia

- [Historia seccionada y evolución de decisiones](../historico/README.md).
- [Bitácora inicial del 2026-09-23](../historico/bitacora_fase1.md), con aclaración
  sobre simulación y la afirmación incorrecta de “TCP exacto”.
- [Propuesta original](../historico/propuesta_original.md).
- [Resultados fechados](../historico/verificaciones.md) y [pruebas actuales](../verificacion.md).

Para próximos cambios, agregar entradas con **objetivo → fallo/necesidad → cambio
y motivo → verificación → límites pendientes**. Conservar entradas anteriores;
las correcciones se agregan con fecha. No registrar propuestas como funciones
completadas ni una revisión documental como ejecución de pruebas del sistema.
