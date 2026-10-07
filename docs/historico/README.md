# Historia del proyecto

Organización documental: **2026-10-07**. Aquí se conservan antecedentes y evidencia
fechada. Para utilizar o implementar la solución actual, consultar el
[mapa documental](../README.md), el [RFC](../../protocolo.md) y el [contrato GTP/1](../protocolo.md).

## 1. Propuestas y planificación

| Documento | Qué conserva |
|---|---|
| [Propuesta original](propuesta_original.md) | Objetivos iniciales, alternativas de indexación, mensajes propuestos y correcciones de la fórmula de caché. |
| [Diseño de protocolo previo](diseno_protocolo_previo.md) | Especificación objetivo anterior al RFC; contiene campos y comportamientos que no son los actuales. |
| [Plan temporal P0–P7](plan-temp.html) | Traspaso de trabajo, corrección de alcance a PNG, etapas y candidatos de investigación. |

Estos documentos no se mantienen como especificaciones paralelas. Las propuestas
de GTP-RA/SACK por rangos, frames binarios, prefetch y adaptación q0→q3 siguen siendo
propuestas donde el RFC así lo indica. La función de utilidad del plan no es un
algoritmo calibrado ni un resultado experimental.

## 2. Registros de modificaciones

| Documento | Periodo / función |
|---|---|
| [Bitácora inicial](bitacora_fase1.md) | Prototipo del 2026-09-23, con aclaración sobre simulación y payloads ficticios. |
| [Bitácoras por fase](../fases/README.md) | Desde 2026-10-02: objetivo/problema, resolución, verificación y límites. Continúan recibiendo entradas fechadas. |
| [Referencia P2 archivada](registro_p2.md) | Revisión del registro persistente, sus pruebas y posteriores aclaraciones de integración. |
| [Primer incremento P5/P6](renderizado_p5.md) | Correcciones del handler activo, renderizado, fragmentación y pruebas del corte del 2026-10-06. |

Los sufijos P0–P7 identifican incrementos de desarrollo. Las fases 00–05 agrupan
áreas de la solución; por ejemplo, P3 se registra en fase 04 por su relación con
los tiles. Son clasificaciones diferentes, no versiones contradictorias.

## 3. Evidencia de ejecuciones

- [Historial de verificaciones](verificaciones.md): resultados separados por
  fecha, ambiente y alcance. Conserva omisiones e incidencias.
- [Experimentos P7](../experimentos_p7.md): metodología y resultados numéricos del
  ensayo del 2026-10-06, además de comandos para reproducirlo.
- [Verificación actual](../verificacion.md): matriz de cobertura y procedimientos.

Las menciones de limitaciones pendientes en una fecha permanecen como registro
de ese momento. Una continuación posterior puede resolverlas sin que se reescriba
la entrada anterior. Los reportes completos P7 se generaron en rutas temporales;
no se afirma que esta reorganización haya recuperado o vuelto a generar esos artefactos.

## Evolución de decisiones

| Etapa anterior | Implementación posterior / actual | Motivo y dónde leerlo |
|---|---|---|
| Metadata y payloads simulados, cola compartida | Catálogo real, bytes de tiles y estado por sesión | Evitar mezcla entre clientes y permitir verificar entrega/recuperación. [Bootstrap](../fases/fase_01_bootstrap.md), [WebSocket](../fases/fase_02_websocket.md) y [tiles](../fases/fase_04_tiles.md). |
| Suposición de originales ZIP/multiformato | Pipeline dedicado a PNG | El usuario confirmó el formato real de los originales. [Corrección P1](../fases/fase_00_entorno.md#2026-10-04--p1-cli-png-y-corrección-de-alcance). |
| Inspección sin original disponible para Java | Registro, subida por bloques y preparación secuencial | El navegador no entrega una ruta accesible al servidor; PNG requiere decodificación secuencial. [Registro e ingesta](../ingesta_p3.md#por-qué-se-separan-inspección-registro-e-ingesta). |
| Caché LRU del incremento P4 | LFU con envejecimiento y TTL en P5 | Favorecer reutilización, reducir popularidad antigua y retirar inactividad, sin LRU/FIFO. [RFC §10](../../protocolo.md#10-políticas-de-memoria-y-ttl). No se afirma superioridad experimental universal. |
| Máximo 512 KiB por tile en la base inicial | Máximo 2 MiB y presupuestos independientes en P5 | Admitir tiles de 512 px poco compresibles manteniendo cotas de payload y RGBA. [Registro P5](renderizado_p5.md) y [RFC §8](../../protocolo.md#8-control-de-flujo-y-recuperación). |
| Grilla plana y reemplazo de regiones | Canvas multinivel, respaldo protegido y cancelación parcial | Mantener contexto y conservar trabajo que aún sirve. [Cliente](../fases/fase_03_viewport.md) y [visor](../visor_p6.md). |
| Recuperación descrita informalmente como “TCP exacto” | Control de consumo de aplicación sobre WebSocket/TCP | Correlacionar ACK, limitar recursos y cancelar trabajo obsoleto; TCP ya recupera paquetes. [RFC §5–9](../../protocolo.md#5-transporte-e-identidad-de-mensajes). |
| Refinamiento automático/WebP/prefetch propuestos | Visor q3 del nivel visible; PNG/JPEG disponibles en servidor | El alcance integrado se describe por lo realmente implementado; esas extensiones requieren desarrollo y medición. [RFC §11](../../protocolo.md#11-renderizado-y-adaptación). |

Las razones de diseño vigentes se desarrollan en la
[sección 12 del RFC](../../protocolo.md#12-decisiones-y-alternativas). Esta tabla
orienta la lectura histórica; no sustituye campos ni algoritmos del contrato.

## Cómo registrar el próximo cambio

Agregar a la fase correspondiente una entrada con **fecha → objetivo → fallo o
necesidad → cambio y motivo → verificación realmente ejecutada → límites**.
Enlazar el commit o reporte si está disponible. Si se corrige una afirmación
antigua, añadir una aclaración fechada en vez de borrar lo que se había registrado.
Una reorganización debe indicar origen y destino de documentos para conservar trazabilidad.
