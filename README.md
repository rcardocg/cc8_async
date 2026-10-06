# Servidor asíncrono de imágenes — CC8

Servidor Java 21 / Spring Boot 3.2.4 para explorar regiones de imágenes mediante
HTTP inicial y un protocolo propio de control de tiles sobre WebSocket (**GTP/1**).
Autores registrados en la propuesta original: Ricardo Caballeros y Cristian Sactic.

## Estado actual

Hay una base funcional y verificable: catálogo real, frontend servido desde Java,
tiles PNG/JPEG, estado independiente por cliente, ACKs de aplicación, ventana
limitada, retransmisión selectiva por timeout y cancelación de regiones antiguas.

El visor actual usa coordenadas **X/Y a resolución nativa** y carga hasta 12 tiles
por región. La demo `demo_numeros` genera únicamente el tile solicitado. También
se admiten imágenes previamente divididas en tiles mediante un catálogo local.
**La demo no constituye una prueba con la imagen de 24 GB.**

Los originales de evaluación son **PNG**. P1 permite elegirlos desde el explorador
del cliente, sin escribir rutas ni moverlos al proyecto, e inspeccionar su cabecera
para estimar tiles, RAM y disco. Solo se envían 33 bytes; los pequeños tienen vista
previa local con zoom. También existe CLI sin arrancar el servidor.
P3 incorpora un primer preprocesador PNG secuencial; el visor con pan/zoom multinivel
sigue pendiente de P5/P6.
Pruebas del CLI: [docs/pruebas_png_p1.md](docs/pruebas_png_p1.md).

P2 añade **Registrar imagen** desde el cliente, persistencia en `meta.json`,
metadata multinivel prevista y consulta de estado. Los registros pendientes
aparecen en catálogo, pero no se cargan como tiles. Pruebas y contrato:
[docs/registro_p2.md](docs/registro_p2.md).

P3 añade **Transferir / reanudar y procesar**, subida en bloques de 1 MiB, ingesta
desde originales del servidor/CLI y pirámides reales con memoria acotada. Soporta
PNG no entrelazados de hasta 8 bits; 16 bits y Adam7 se rechazan explícitamente.
Los registros pasan a `ready` solo después de validar y generar la pirámide completa.
Guías para **Linux y Windows**, API y límites: [docs/ingesta_p3.md](docs/ingesta_p3.md).

## Ejecutar

Para iniciar paso a paso y observar solicitudes en vivo, consulta
[GUIA_INICIO_Y_PRUEBAS.md](GUIA_INICIO_Y_PRUEBAS.md).

Requisitos de desarrollo: **JDK 21 y Maven 3.9+** disponibles en `PATH`.

```powershell
.\scripts\build.cmd
.\scripts\gtp.cmd
```

Abrir **http://localhost:8081/**. Todos los recursos del cliente se sirven desde
el servidor Java; no hay dependencias de CDN ni llamadas a servicios externos.
El JAR empaquetado incluye sus dependencias para ejecución sin Internet. La
primera compilación necesita las dependencias Maven descargadas.

Opciones de arranque:

```powershell
java -jar .build/server.jar --server.port=8082 --images.originals-directory="D:/imagenes/originales" --images.directory="D:/imagenes/tiles" --images.demo-enabled=false
```

Para generar artefactos en una carpeta alternativa:

```powershell
mvn "-Dgigapixel.buildDirectory=.build" test package
```

También se puede compilar y arrancar con `bash scripts/build.sh` y
`bash scripts/gtp.sh` en Fedora, o `scripts\build.cmd` y `scripts\gtp.cmd` en
Windows. Generan y ejecutan `.build/server.jar` desde la raíz del proyecto.
`GTP_IMAGES` configura originales, `GTP_WORK` catálogo/tiles y `GTP_PORT` el puerto.
Consulta [docs/imagenes.md](docs/imagenes.md) para las rutas y configuración local.

## Probar desde el navegador

Para pruebas P1, usar **Elegir PNG** en la sección superior, revisar las estimaciones
y descargar el informe JSON. La vista previa de archivos pequeños es local.
El botón P3 genera tiles; su visualización multinivel aún requiere P5/P6.
Para probar la transferencia GTP, usar la sección **Visor de tiles preparados**:

1. Seleccionar una imagen y una región, o usar las flechas de navegación.
2. Abrir otra pestaña: cada cliente debe conservar sus propios tiles y ventana.
3. En **Diagnóstico del protocolo**, activar «Omitir un ACK» y cargar otra región.
   El servidor retransmite sólo el tile pendiente; el cliente confirma el duplicado.
4. Simular 90% de presión de memoria: la ventana del receptor baja a dos tiles.
5. Cambiar rápidamente de región, cancelar o reconectar: las respuestas antiguas
   no deben reemplazar la región actual.

El panel muestra RTT **de aplicación**, incluyendo transferencia y decodificación;
no utiliza números aleatorios como medición de red. El selector de memoria está
etiquetado como **simulación**, no como lectura real del heap del navegador.

Prueba opcional E2E con Node.js, Playwright y Edge instalado:

```powershell
# Instalar Playwright en un directorio de herramientas y exponer su node_modules
# mediante NODE_PATH, o usar una instalación existente.
node scripts/verify-browser.cjs .build/server.jar
```

El script arranca su propio servidor en un puerto libre y lo detiene al terminar.
`BROWSER_CHANNEL=chrome` permite usar Chrome en lugar de Edge.
En Fedora, `BROWSER_EXECUTABLE_PATH` permite indicar Chromium instalado.
Smoke P3 sin Playwright: `node scripts/verify-ingestion.cjs .build/server.jar`
(PNG sintético íntegro, heap de 64 MiB, reinicio real de Java).

## Agregar imágenes preprocesadas

Consultar [docs/imagenes.md](docs/imagenes.md). No se abre ni se envía la imagen
completa; el servidor lee un archivo por tile y limita el tamaño de cada uno.
El catálogo se valida al iniciar; para agregar entradas se actualiza y se reinicia.

## Documentación y bitácoras

- [Contrato de protocolo GTP/1](docs/protocolo.md).
- [Índice de implementación y resolución por fase](docs/fases/README.md).
- [Resultados y procedimientos de verificación](docs/verificacion.md).
- [Propuesta original, conservada como referencia](docs/propuesta_original.md).
- `bitacora_fase1.md`: registro histórico de septiembre, con aclaración de su alcance.

## Requisitos de evaluación y trabajo pendiente

Fuentes: `Proyecto_Servidor_Asi_ncrono.pdf` e `image_Indicators.pdf`. La indicación
posterior enfatiza **40% funcionamiento/usabilidad y 60% protocolo**, con recuperación
y control de flujo/congestión demostrables. Tener niveles o tiles por sí solo no
cumple el objetivo. La imagen de 24 GB es una base para comenzar las pruebas reales;
las imágenes indicadas de 17, 28, 55 y 93 GB tienen máximos de 20, 40, 80 y 115 puntos.

Pendiente para la solución completa:

- Validar el preprocesador con originales gigantes del curso y ampliar variantes PNG según su inventario.
- Niveles de resolución y selección de calidad por cliente; el protocolo actual
  rechaza `z` distinto de `null` para no aparentar soporte inexistente.
- Predicción/prefetch, caché LRU/LFU y compresión adaptativa medidos y justificados.
- Telemetría automática de recursos del cliente y ensayos con los archivos reales.
- Validación de legibilidad de números, consumo de RAM, bytes transferidos, latencia
  y múltiples clientes en el entorno de evaluación sin Internet.

Los ACKs aquí confirman consumo del tile por la aplicación. WebSocket ya usa TCP:
GTP/1 no implementa TCP ni sustituye su recuperación de paquetes.
