# Mapa de documentación

Revisión: **2026-10-07**. Este índice distingue uso, especificación, evidencia e
historia para que una propuesta antigua no se lea como comportamiento actual.

## Por dónde empezar

1. **Usar el proyecto:** [guía de compilación, uso y diagnóstico](../GUIA_INICIO_Y_PRUEBAS.md).
2. **Entender la implementación:** [RFC interno GTP-001](../protocolo.md).
3. **Implementar o depurar un cliente:** [contrato GTP/1](protocolo.md).
4. **Reproducir resultados:** [verificación](verificacion.md) y [benchmark P7](experimentos_p7.md).
5. **Consultar su evolución:** [historia y bitácoras](historico/README.md).

## Referencias actuales por tema

| Documento | Qué mantiene |
|---|---|
| [RFC interno](../protocolo.md) | Arquitectura, algoritmos, estados generales, motivos, alternativas y límites de la solución. |
| [Contrato GTP/1](protocolo.md) | Mensajes, campos, unidades, valores predeterminados, validaciones y efectos observables en el canal. |
| [Configuración e imágenes](imagenes.md) | Variables, directorios, inspección CLI y catálogo plano. |
| [Registro e ingesta](ingesta_p3.md) | API HTTP detallada, manifests, subida, procesamiento, publicación y reintentos. |
| [Pruebas de inspección](pruebas_png_p1.md) | Casos manuales P1 e inventario de originales, sin confundir cabecera con ingesta. |
| [Calidades y caché](calidades_p4.md) | Particularidades de codificación, uso de q y consulta de la caché. |
| [Visor](visor_p6.md) | Interacción actual, respaldo, cola de vistas y comprobaciones del cliente. |
| [Verificación](verificacion.md) | Comandos, cobertura y acceso a ejecuciones fechadas. |
| [Experimentos P7](experimentos_p7.md) | Método de medición, datasets, resultados y alcance del benchmark. |

Los sufijos P1–P7 indican el incremento que originó una guía; su cabecera aclara
si es una referencia actual o un registro histórico. Las fases 00–05 de las
bitácoras corresponden a áreas de comunicación y no equivalen a esos incrementos.

## Cómo evitar explicaciones discrepantes

- El RFC explica **por qué** y **cómo** funcionan los mecanismos; el contrato
  especifica **qué se intercambia**. Son documentos complementarios del mismo GTP/1.
- Una guía resume el recorrido y enlaza el detalle técnico; no redefine un algoritmo.
- Los resultados numéricos completos pertenecen a evidencia fechada. Un resumen
  no significa que se haya vuelto a ejecutar la prueba.
- Si cambia el comportamiento, actualizar RFC/contrato y la referencia del tema;
  agregar una entrada a la bitácora con motivo, verificación y límites.
- Una discrepancia entre código y documentación se investiga contra implementación
  y pruebas. No se resuelve presentando como vigente una propuesta histórica.

## Historia separada, razones conservadas

Las [bitácoras por fase](fases/README.md) registran problema → cambio → verificación
→ límites. Las propuestas, el plan anterior y las ejecuciones pasadas están
seccionados en [Historia del proyecto](historico/README.md).
Las decisiones que siguen vigentes se explican en la
[sección 12 del RFC](../protocolo.md#12-decisiones-y-alternativas); las diferencias
con etapas anteriores se resumen en el índice histórico.
