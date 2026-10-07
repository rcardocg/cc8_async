# Visor — navegación integrada y respaldo permanente

Revisión documental: 2026-10-07. Guía de la interacción actual; la evidencia de
ejecución del incremento P6 está fechada en [verificación](verificacion.md).

Esta guía describe interacción y pruebas del cliente especificado en el
[RFC interno GTP-001](../protocolo.md); el contrato de campos está en
[protocolo.md](protocolo.md). Las extensiones de gesto/calidad del servidor no
implican adaptación automática de q en este visor.

## Flujo de usuario

1. Elegir PNG, revisar cabecera/estimaciones y registrar un identificador.
2. El registro selecciona automáticamente la imagen en el área de exploración.
3. Transferir y preparar; el estado y los errores permanecen visibles.
4. Al llegar a `ready`, abrir el canvas y solicitar primero el nivel 0.
5. Solicitar la región visible del nivel necesario; ajustar, 1:1, pan y zoom al cursor.

Se eliminó el segmento «2. Visor de tiles preparados». La navegación real vive en
el espacio de trabajo de imágenes. Los controles X/Y del catálogo plano/demo
permanecen únicamente dentro de Diagnóstico para regresión del protocolo.
Al iniciar se prefiere una imagen del usuario antes que la demo sintética.

## Respaldo, cola de vistas y memoria

- `imageId:0:0:0:3` se solicita expresamente y se protege durante toda la sesión
  de esa imagen. Es un solo tile reducido; cuenta en el presupuesto de 32 MiB.
- Niveles inferiores se dibujan primero, el nivel solicitado después. Se recorta
  el dibujo a las dimensiones originales para bordes de niveles redondeados.
- Los demás tiles fuera de vista expiran tras 5 s. Presión: expulsión espacial de
  no visibles. Nunca LRU/FIFO; nunca expulsión del respaldo o de tiles visibles.
- Antes de insertar un bitmap se reserva espacio y se valida su dimensión real.
  Un fallo de presupuesto/decodificación no se confirma como tile consumido.
- Los gestos actualizan la vista inmediatamente; solicitudes limitadas cada 120 ms,
  usando la vista más reciente incluso durante movimiento continuo. El renderizado
  se agrupa por `requestAnimationFrame`, sin animar artificialmente la cámara.
- Se conserva trabajo en vuelo que todavía sirve; `cancel_tiles` elimina solo
  tiles obsoletos. Al completar el lote se solicita lo que falta en la vista actual.
- El progreso cuenta tiles visibles decodificados, no bytes recibidos ni tiles de
  vistas anteriores. `aria-busy` refleja cobertura incompleta; errores conservan
  respaldo y ofrecen reintento explícito con Actualizar vista.

## Interacción y presentación

El respaldo mantiene contexto mientras llega el detalle; la cancelación parcial
evita descartar tiles que siguen siendo útiles. El flujo archivo → preparación →
exploración organiza la interfaz según la tarea del usuario. La evolución desde
el primer visor está en la [bitácora de cliente](fases/fase_03_viewport.md).
No se descargan fuentes ni recursos externos durante la ejecución.

Teclas con canvas enfocado: flechas para pan, `+`/`−` para zoom, `0` ajustar y
`1` resolución nativa. No se anima la interacción de teclado. La captura del
puntero conserva arrastre fuera del canvas e ignora dedos/punteros secundarios.
Al redimensionar, una vista ajustada vuelve a ajustar; una vista ampliada conserva
escala y recorta su centro a los límites navegables. Layout móvil sin desbordamiento.

## Verificación y pruebas manuales

`scripts/verify-browser.cjs` incluye registro/activación automática, respaldo
retenido después de TTL, píxeles nativos, teclas, arrastre, progreso completo,
layout de 390 px, `prefers-reduced-motion`, recuperación y dos clientes.

Para verificar a mano:

1. Elegir un PNG compatible, registrar y preparar sin seleccionar otra imagen;
   comprobar que aparece automáticamente cuando está listo.
2. Ajustar, acercar a 1:1 y cambiar de región durante carga: debe quedar una vista
   general de respaldo hasta completar el detalle, sin repintados antiguos.
3. Mover durante más de un segundo sin soltar: se deben solicitar vistas durante
   el gesto, no únicamente al soltar; al terminar, se completa la última región.
4. Esperar más de 5 s en una región: en Diagnóstico se conservan los visibles y el
   respaldo; volver a otra región solicita de nuevo los tiles expirados.
5. Enfocar el canvas y usar flechas, `+`, `−`, `0`, `1`. Arrastrar hasta los bordes.
6. Cambiar ancho de ventana con Ajustar activo; probar móvil y movimiento reducido.
7. Simular omisión de ACK/ventana 1500 bytes; la vista debe completar el mismo detalle.

Para la regresión del incremento P5, recorrer esquinas con tiles de borde parciales,
comparar una región nativa entre ventana 0 y 1500 (evitar únicamente hits de caché),
simular presión de 90% durante recuperación y abrir una segunda pestaña. Debe
conservarse el detalle, no solicitar coordenadas inválidas ni mezclar clientes.
Tras reconectar, se solicita otra vez la imagen seleccionada. La comprobación de
TTL del servidor está en [calidades y caché](calidades_p4.md#verificación).
El [registro P5](historico/renderizado_p5.md) conserva la tabla y evidencia originales.

Siguiente fase: [experimentos_p7.md](experimentos_p7.md).
Pendientes de cierre de evaluación: PNG gigantes reales, legibilidad humana de
números en el entorno del curso y repetición en Windows/dispositivos táctiles.
