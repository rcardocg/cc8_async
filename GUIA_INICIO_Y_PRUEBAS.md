# Guía de inicio y comprobación del servidor

**Actualización documental:** 2026-10-06
**Objetivo actual:** abrir/preparar un PNG y navegarlo mediante GTP/1, conservando
detalle y verificando confirmación, recuperación y memoria. Especificación de la
solución: [RFC interno GTP-001](protocolo.md); pruebas del visor en
[docs/visor_p6.md](docs/visor_p6.md) y ensayos en [docs/experimentos_p7.md](docs/experimentos_p7.md).

**Ambos ambientes:** rutas/lanzadores en [docs/imagenes.md](docs/imagenes.md),
registro en [docs/registro_p2.md](docs/registro_p2.md) e ingesta real con comandos
equivalentes para Fedora y Windows en [docs/ingesta_p3.md](docs/ingesta_p3.md).
En Fedora: `bash scripts/build.sh` y `bash scripts/gtp.sh`.
En Windows: `.\scripts\build.cmd` y `.\scripts\gtp.cmd`.
Los ejemplos de diagnóstico PowerShell de esta guía siguen siendo válidos.

Este archivo está en la raíz del proyecto, al mismo nivel que `bitacora_fase1.md`.

## 1. Qué necesitas abrir

- **Terminal 1 — PowerShell:** mantiene el servidor Java en ejecución.
- **Navegador — Edge o Chrome:** muestra el visor y los mensajes del protocolo.
- **Terminal 2 — PowerShell (opcional):** permite consultar la API mientras el
  servidor sigue ejecutándose en la primera terminal.

Para la prueba manual basta Java 21 y el JAR compilado. El frontend está incluido
en Java: no hay que arrancar otro servidor frontend, una base de datos ni Docker.
La demo incluida permite observar el intercambio sin preparar imágenes externas.

## 2. Inicio rápido en Windows

### 2.1. Abrir la carpeta correcta

En la Terminal 1:

```powershell
Set-Location -LiteralPath "C:\ruta\al\cc8_async"
java -version
Test-Path -LiteralPath ".build/server.jar"
```

**Esperado:** Java versión **21** y `True` para el archivo JAR. Al preparar esta
guía se verificaron Java 21.0.11 y ese JAR en este equipo.

Si aparece `False`, compila siguiendo la sección 7 antes de continuar.

### 2.2. Encender el servidor

```powershell
java -jar ".build/server.jar" --images.demo-enabled=true
```

Deja esta terminal abierta. Que el comando no devuelva inmediatamente el prompt
es normal: Java está atendiendo conexiones.

Busca mensajes parecidos a estos; los tiempos y el PID pueden cambiar:

```text
Tomcat started on port 8081 (http) with context path ''
Started GigapixelServerApplication in ... seconds
```

Estas líneas confirman que el servidor arrancó y escucha conexiones; todavía no
demuestran que haya recibido una solicitud de tiles.

### 2.3. Abrir el visor

En el navegador entra a:

**http://localhost:8081/**

Abre esa dirección HTTP, en lugar del archivo HTML directamente desde el explorador.
El navegador obtiene HTML, CSS, JavaScript, metadata y tiles desde Java.

Para probar tu original, elegirlo en **Abre tu PNG**, registrar y pulsar
**Transferir y preparar imagen**. Al llegar a `ready`, aparece en **Tu imagen**:
Ajustar muestra la vista general, 1:1 conserva detalle nativo y arrastre/rueda
navegan la región. El progreso cuenta tiles visibles y el nivel 0 queda de respaldo.

Para el diagnóstico plano, seleccionar `demo_numeros` y abrir **Diagnóstico del
protocolo → Compatibilidad de catálogo plano / demo sintética**. Con columna/fila
en `0`, debes ver:

1. Metadata: imagen **4096 × 4096**, tiles de **256 px**, **256 tiles**, formato PNG.
2. Una región de **4 columnas × 3 filas**, con números y coordenadas visibles.
3. El estado:

```text
Terminada: 12/12 confirmados; 0 fallidos. Puede volver a cargar la región.
```

Ese estado proviene de un mensaje `request_complete` del servidor: confirma que
Java procesó los ACKs de los 12 tiles. Cerca de los bordes puede haber menos de 12.

## 3. Ver actividad en la propia página

Abre **Diagnóstico del protocolo**, debajo de la región de imágenes.

Encontrarás entradas como:

```text
Conectado: GTP/1
Solicitados 12 tiles.
Tile demo_numeros:0:0, intento 1; ACK después de decodificar.
```

Pulsa **Actualizar vista** o una flecha de diagnóstico para generar actividad. El panel muestra:

| Dato | Qué significa |
|---|---|
| `cwnd` | Ventana de transmisión de esa sesión |
| `en vuelo` | Tiles enviados cuyo ACK todavía espera el servidor |
| `cola` | Tiles pendientes de enviar |
| `RTT aplicación` | En tile entero: envío a ACK; en fragmentos: último fragmento enviado a ACK de tile, con procesamiento del cliente |
| `RTO` | Intervalo base usado para esperar confirmación antes de retransmitir |

En localhost todo puede ocurrir muy rápido. Las métricas mostradas son la última
instantánea enviada, no un monitor continuo de actividad: `transfer_state` se
emite tras un ACK y antes del siguiente envío. Cuando la región termina y no haces
nada más, no es necesario que aparezcan nuevos mensajes. El servidor sigue activo.

## 4. Confirmar exactamente qué recibe y responde Java

### 4.1. Ver solicitudes HTTP

1. Pulsa **F12** en Edge/Chrome.
2. Abre **Network / Red** y deja activada la grabación.
3. Recarga la página con F12 abierto.
4. En **All / Todo** o **Fetch/XHR**, busca las solicitudes siguientes:

| Solicitud | Respuesta esperada |
|---|---|
| `/` | Página del visor; normalmente HTTP 200 |
| `/viewer.js` y `/viewer.css` | Recursos locales; pueden aparecer desde caché |
| `/api/images` | HTTP 200 y un arreglo que contiene `demo_numeros` |
| `/api/image/demo_numeros/metadata` | HTTP 200 y metadata de la demo |

Selecciona una solicitud y abre **Response / Respuesta** para leer el JSON.
Puedes activar **Disable cache / Deshabilitar caché** mientras DevTools está abierto
si deseas observar nuevas solicitudes de los recursos al recargar.

### 4.2. Ver la conversación WebSocket en vivo

1. En **Network / Red**, selecciona el filtro **WS**.
2. Busca la conexión `tiles`, cuya URL termina en **`/ws/tiles`**.
3. Selecciónala y abre **Messages / Mensajes** (en algunas versiones, **Frames**).
4. Pulsa **Actualizar vista** en el visor sin cerrar DevTools.

En la conexión HTTP local normal verás **101 Switching Protocols** al establecer
el WebSocket. Después observa los mensajes, identificándolos por `action`:

| Dirección | Acción | Evidencia |
|---|---|---|
| Java → navegador | `ready` | Java aceptó la conexión y anunció GTP/1 |
| Navegador → Java | `fetch_tiles` | El navegador solicitó coordenadas concretas |
| Java → navegador | `request_accepted` | Java recibió y validó la solicitud |
| Java → navegador | `tile_data` | Java envió los bytes de un tile |
| Navegador → Java | `ack_tile` | El navegador confirmó el tile decodificado |
| Java → navegador | `transfer_state` | Java procesó un ACK válido y actualizó su estado |
| Java → navegador | `request_complete` | Java terminó la solicitud y resumió éxitos/fallos |

Los envíos de tiles y ACKs pueden intercalarse. Usa `request_id` para relacionar una
región, y `transfer_id`/`tile_id` para relacionar un tile con su ACK.

**La comprobación más clara de recepción es ver `request_accepted` como respuesta a
`fetch_tiles`, y después `request_complete` con `acknowledged: 12` y `failed: 0`.**
Ver sólo un mensaje saliente del navegador no basta para confirmar su procesamiento.

Los tiles viajan dentro de mensajes WebSocket, en el campo Base64 `data`: no debes
esperar una solicitud HTTP `.png` separada por cada tile en la pestaña Network.

## 5. Consultar la API desde otra terminal

Con el servidor todavía activo en la Terminal 1, ejecuta en la Terminal 2:

```powershell
Invoke-RestMethod -Uri "http://localhost:8081/api/images"
```

Esperado: `demo_numeros` y cualquier otra imagen registrada en tu catálogo.

```powershell
Invoke-RestMethod -Uri "http://localhost:8081/api/image/demo_numeros/metadata" | Format-List
```

Esperado:

```text
imageId    : demo_numeros
width      : 4096
height     : 4096
tileSize   : 256
totalTiles : 256
maxZoom    :
format     : png
```

Para comprobar el código HTTP explícitamente:

```powershell
(Invoke-WebRequest -Uri "http://localhost:8081/api/images" -UseBasicParsing).StatusCode
```

Debe devolver **200**. Esto confirma que Java recibe y responde HTTP; el intercambio
de imágenes/ACKs se observa por separado en el WebSocket de la sección 4.

### Logs adicionales en la terminal del servidor

El nivel de log normal no imprime cada tile ni cada ACK. Una terminal silenciosa
después del arranque no significa que el servidor haya dejado de trabajar.

Si quieres más información de HTTP y del ciclo de conexión WebSocket, detén Java
con **Ctrl+C** y reinícialo así:

```powershell
java -jar ".build/server.jar" --images.demo-enabled=true --logging.level.org.springframework.web.servlet.DispatcherServlet=DEBUG --logging.level.org.springframework.web.socket=DEBUG
```

Repite una consulta HTTP o recarga el visor. Los logs de Spring ayudan a observar
solicitudes y conexiones; la lista exacta de mensajes GTP/1 se consulta en DevTools.

## 6. Pruebas cortas para ver que responde en tiempo real

### A. Solicitar otra región

Pulsa **→**. Deben cambiar las coordenadas/números y aparecer otra secuencia de
solicitud, tiles, ACKs y finalización. Java está atendiendo una nueva región.

### B. Observar recuperación de un ACK omitido

1. Abre **Diagnóstico del protocolo**.
2. Marca **Omitir un ACK (una sola vez por solicitud)**.
3. Pulsa **Actualizar vista**.
4. Busca `ACK omitido deliberadamente` en el panel.
5. Espera la retransmisión: el mismo `transfer_id` aparece con `attempt: 2`.
6. La solicitud debe terminar sin tiles fallidos. Desmarca la opción al terminar.

El RTO inicial es de aproximadamente un segundo, aunque puede ajustarse con las
mediciones de esa sesión. Esta prueba omite un ACK de aplicación deliberadamente;
no necesita cortar Internet ni simula pérdida de paquetes TCP.

### C. Comprobar recepción de un mensaje de memoria

1. Selecciona **90%** en presión de memoria simulada.
2. Pulsa **Enviar presión simulada**.
3. En DevTools comprueba `memory_pressure` saliente y `adjust_strategy` entrante.
4. El panel debe indicar **Ventana del receptor: 2**.
5. Envía después **20%**: debe indicar **Ventana del receptor: 32**.

El límite efectivo puede ser menor por `cwnd`. Estos porcentajes son controles de
diagnóstico, no mediciones automáticas de la memoria real del navegador.

### D. Comprobar dos clientes

Abre **http://localhost:8081/** en otra pestaña. Cambia la región en una de ellas:
la otra debe mantener su imagen. Cada conexión tiene su propia cola y ventana.

## 7. Compilar si el JAR falta o cambiaste el código

El JAR es una copia compilada: editar archivos fuente no cambia un servidor ya
iniciado. Para aplicar cambios, detén Java, compila y vuelve a ejecutar el JAR.

Si Maven está en PATH, desde la raíz del proyecto:

```powershell
mvn -version
mvn "-Dgigapixel.buildDirectory=.build" package
```

`package` ejecuta también las pruebas. Espera **BUILD SUCCESS** antes de iniciar Java.

En este equipo se preparó Maven 3.9.9 en una carpeta temporal porque `mvn` no estaba
en PATH. Puedes utilizarlo mientras esa carpeta exista:

```powershell
Test-Path -LiteralPath "C:\Users\crist\AppData\Local\Temp\opencode\apache-maven-3.9.9\bin\mvn.cmd"
& "C:\Users\crist\AppData\Local\Temp\opencode\apache-maven-3.9.9\bin\mvn.cmd" "-Dgigapixel.buildDirectory=.build" package
```

Si la comprobación de esa ruta devuelve `False`, instala Maven 3.9+ y usa el comando
normal. La primera compilación puede descargar dependencias; ejecutar el JAR ya
empaquetado y la demo local no necesita Internet.

## 8. Problemas frecuentes

| Síntoma | Qué revisar |
|---|---|
| `java` no se reconoce | Instalar/configurar JDK 21 y abrir una terminal nueva |
| `Unable to access jarfile` | Confirmar carpeta actual y existencia del JAR; compilar si falta |
| Error de versión de clases | Revisar que `java -version` muestre Java 21 |
| Puerto 8081 ocupado | Revisar si ya hay una instancia; detenerla desde su terminal o usar otro puerto |
| `ERR_CONNECTION_REFUSED` | Confirmar que Java sigue activo y que el navegador usa el puerto anunciado al arrancar |
| La página abre pero no aparecen imágenes | Revisar estado del visor, metadata y frames `/ws/tiles`; verificar que `demo_numeros` esté seleccionada |
| No aparecen frames en DevTools | Abrir Network antes de recargar; seleccionar WS → `tiles` → Messages |
| Estado `Desconectado` | Confirmar que Java esté activo y pulsar **Reconectar** |
| Cambié código pero veo lo anterior | Volver a empaquetar, reiniciar el JAR y recargar la página sin caché |
| No aparecen logs nuevos estando inactivo | Es normal; pulsa **Actualizar vista** o consulta `/api/images` para generar tráfico |

Para iniciar en otro puerto:

```powershell
java -jar ".build/server.jar" --server.port=8082 --images.demo-enabled=true
```

En ese caso usa **http://localhost:8082/** y cambia también el puerto en las consultas
de PowerShell. El visor adapta su conexión WebSocket al puerto de la página.

## 9. Apagar y criterio de éxito de esta revisión

En la Terminal 1 pulsa **Ctrl+C** y espera que vuelva el prompt. Puedes cerrar las
pestañas o dejarlas abiertas y pulsar **Reconectar** cuando vuelvas a iniciar Java.

La comprobación actual está conseguida si observas:

- Java inicia y anuncia el puerto.
- `/api/images` responde HTTP 200.
- `request_accepted` demuestra recepción de la solicitud de tiles.
- Los tiles se ven y `request_complete` confirma los ACKs sin fallos.
- Al pedir otra región aparecen nuevos mensajes y cambia la imagen visible.

La demo valida este flujo actual. Las pruebas con archivos gigantes y los demás
hitos se retomarán después de esta revisión manual.
