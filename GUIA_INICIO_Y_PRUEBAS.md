# Guía de compilación, uso y diagnóstico

Revisión documental: **2026-10-07**. Guía común para Windows/PowerShell y
Fedora/Bash. Reúne el arranque, la carga de imágenes y el diagnóstico que antes
estaban repartidos entre dos guías. La explicación de los mecanismos está en el
[RFC interno GTP-001](protocolo.md); sus campos exactos, en el
[contrato GTP/1](docs/protocolo.md).

## 1. Preparar el entorno

Abre una terminal en la raíz del proyecto, donde está `pom.xml`.
Necesitas **JDK 21 y Maven 3.9+** para compilar:

```text
java -version
javac -version
mvn -version
```

Maven también debe mostrar Java 21. Si usa otra versión, revisa `JAVA_HOME` y
`PATH`. Si `mvn` no se reconoce, agrega la carpeta `bin` de tu instalación de Maven
al `PATH` y abre otra terminal. Usa rutas de tu equipo; una instalación temporal
utilizada en pruebas anteriores puede haber desaparecido.

Para ejecutar un JAR ya compilado basta Java 21. Node.js y Playwright se usan solo
en verificaciones opcionales. El frontend y las dependencias de ejecución están
incluidos en Java; no hace falta un servidor frontend separado ni base de datos.

### Elegir dónde guardar los datos

Los originales y el directorio de trabajo deben estar separados: no pueden
coincidir ni estar uno dentro del otro. Los valores predeterminados son
`data/originales` y `data/work`. Para usar otras carpetas:

Windows / PowerShell:

```powershell
$env:GTP_IMAGES = Join-Path $env:USERPROFILE 'gtp-datos/originales'
$env:GTP_WORK = Join-Path $env:USERPROFILE 'gtp-datos/work'
$env:GTP_PORT = '8081'
```

Fedora / Bash:

```bash
export GTP_IMAGES="$HOME/gtp-datos/originales"
export GTP_WORK="$HOME/gtp-datos/work"
export GTP_PORT=8081
```

Estas variables duran la sesión de terminal. Al volver a arrancar, usa el mismo
`GTP_WORK` para recuperar tu catálogo. El servidor crea las carpetas que falten.
La [referencia de configuración](docs/imagenes.md) describe permisos, disco,
presupuestos, configuración local y precedencia de argumentos.

**Si eliges el PNG desde el navegador**, puede estar en cualquier carpeta de tu
equipo: no necesitas moverlo a `GTP_IMAGES`. Se enviará una copia por bloques al
work. `GTP_IMAGES` sirve para originales que Java puede leer directamente.

## 2. Compilar y arrancar

Windows / PowerShell:

```powershell
.\scripts\build.cmd
# Continuar únicamente después de BUILD SUCCESS.
.\scripts\gtp.cmd
```

Fedora / Bash:

```bash
bash scripts/build.sh
# Continuar únicamente después de BUILD SUCCESS.
bash scripts/gtp.sh
```

`build` ejecuta las pruebas y genera **`.build/server.jar`**. Un JAR que ya existe
puede ser de una compilación anterior si el build falla. Para aplicar cambios de
código: detener Java, compilar y volver a arrancar.

Deja la terminal abierta. Busca mensajes parecidos a:

```text
Tomcat started on port 8081 (http) with context path ''
Started GigapixelServerApplication ...
```

Abre **http://localhost:8081/** en Edge, Chrome o Chromium. Abrir `index.html`
directamente desde el explorador no sustituye el arranque del servidor.
Los recursos se sirven localmente; la primera compilación necesita disponer de
las dependencias Maven, pero el JAR empaquetado no depende de CDN ni servicios externos.

Para otro puerto, cambia `GTP_PORT` antes de ejecutar el lanzador, o arranca desde
la raíz con `java -jar .build/server.jar --server.port=8082`. Usa ese puerto tanto
en el navegador como en las consultas HTTP; el WebSocket toma el origen de la página.

## 3. Elegir, preparar y navegar tu PNG

Empieza con un PNG pequeño RGB/RGBA de 8 bits, sin entrelazado. La tabla completa
de variantes admitidas está en [registro e ingesta](docs/ingesta_p3.md#variantes-admitidas).
Una cabecera válida no garantiza que el archivo completo sea íntegro o compatible.

1. En **Abre tu PNG**, pulsa **Elegir archivo PNG**. Se envían solo 33 bytes para
   inspeccionar firma/IHDR/CRC. Revisa dimensiones, color, estimaciones y tamaño
   de tile (256 px para la primera prueba); puedes descargar el informe JSON.
2. Si tiene como máximo 4 megapíxeles y 16 MiB, aparece una vista previa **local**.
   Esta preview no demuestra transferencia GTP ni procesamiento en Java.
3. Escribe un ID, por ejemplo `prueba_01`, y pulsa **Registrar imagen**. Debe quedar
   `pending`, con cero tiles procesados. El ID acepta letras, números, `_` y `-`;
   se selecciona automáticamente en **Tu imagen**.
4. Con el mismo archivo e ID, pulsa **Transferir y preparar imagen**. Primero verás
   bytes subidos; después `processing` y avance de generación de tiles.
5. Al finalizar debe aparecer `ready`, con conteos completos. En metadata,
   `completedLevels` incluye todos los niveles y `availableQualities` contiene 3.
6. Navega con **Ajustar**, **1:1**, rueda y arrastre. El nivel 0 conserva una vista
   general de respaldo mientras llega el detalle. Con el canvas enfocado, usa
   flechas, `+`/`−`, `0` para ajustar y `1` para resolución nativa.

La inspección y el registro no transfieren el original completo. La subida sí
envía una copia hacia Java; la navegación posterior recibe solo tiles por GTP.
`pending` durante la subida es normal: el decoder todavía no ha comenzado.

Por ejemplo, un PNG de 300×280 con tiles de 256 genera cuatro tiles nativos en
`z=1` y uno reducido en `z=0`: cinco en total. La salida de una imagen subida es:

```text
<GTP_WORK>/prueba_01/
  meta.json
  source.part
  tiles/
    complete.sha256
    0/0_0.png
    1/0_0.png
    ...
```

`source.part` se conserva después del éxito; su nombre no indica por sí solo una
subida incompleta. El estado persistido es la referencia. Los niveles previstos
en metadata tampoco equivalen a tiles terminados: deben estar publicados como `ready`.

### Detener, continuar y reintentar

| Situación | Qué hacer y qué se conserva |
|---|---|
| Detener subida | Pulsar **Detener**. Los bloques recibidos permanecen. Elegir el mismo archivo e ID para continuar desde el offset del servidor. |
| Cancelar procesamiento | **Detener** solicita interrumpir el decoder. Esperar `failed` y reintentar explícitamente. El procesamiento empieza desde el principio del PNG. |
| Copia subida incorrecta | **Reiniciar transferencia** descarta la copia de un registro pendiente/fallido no activo. Para otro original, usar otro ID. |
| Recargar el navegador | El procesamiento activo sigue en Java; consultar estado. Para continuar una subida, volver a seleccionar el original. |
| Reiniciar Java | Usar el mismo work. Los registros persisten; un procesamiento interrumpido se marca fallido y requiere reintento. |

No vuelvas a registrar el mismo ID para continuar: el duplicado se rechaza.
Nombre, tamaño y cabecera iguales no prueban que dos archivos tengan el mismo contenido.
La API, los errores y los comandos de ingesta desde originales del servidor están
en [registro e ingesta](docs/ingesta_p3.md). Para inventario y preflight por CLI,
consulta [configuración e inspección](docs/imagenes.md#cli-para-inventario-y-preflight).

## 4. Diagnóstico del protocolo

La demo permite probar GTP sin preparar un original. Selecciona `demo_numeros`
y abre **Diagnóstico del protocolo → Compatibilidad de catálogo plano / demo sintética**.
La demo está habilitada por defecto; si la deshabilitaste, reinicia el JAR con
`--images.demo-enabled=true`.

En columna/fila 0 se muestran 12 tiles (4×3) de una imagen sintética 4096×4096,
con tiles de 256 px. Al terminar:

```text
Terminada: 12/12 confirmados; 0 fallidos. Puede volver a cargar la región.
```

Cerca de los bordes puede haber menos tiles. Ese resultado procede de
`request_complete`: Java recibió los ACK de aplicación. La demo no representa
el PNG seleccionado ni una prueba de originales gigantes.

### Ver HTTP y WebSocket en DevTools

1. Pulsa **F12**, abre **Network / Red** y recarga con la grabación activa.
2. En **Fetch/XHR**, revisa `/api/images` y `/api/image/{id}/metadata`.
   En **Response** puedes leer el JSON. `/`, JS y CSS son recursos del mismo origen.
3. En **WS**, selecciona `tiles`, cuya URL termina en `/ws/tiles`, y abre
   **Messages / Mensajes**. En HTTP local, el handshake normalmente muestra 101.
4. Pulsa **Actualizar vista** y relaciona los mensajes por sus identificadores:

| Dirección | Acción | Qué demuestra |
|---|---|---|
| Java → cliente | `ready` | Conexión aceptada y GTP/1 anunciado |
| Cliente → Java | `fetch_tiles` | Región solicitada |
| Java → cliente | `request_accepted` | Solicitud recibida y validada |
| Java → cliente | `tile_data` o `tile_fragment` | Payload enviado |
| Cliente → Java | `ack_fragment`, si corresponde | Fragmento almacenado y crédito liberable |
| Cliente → Java | `ack_tile` | Tile reconstruido/decodificado y procesado |
| Java → cliente | `transfer_state` | Estado actualizado tras un ACK de tile válido |
| Java → cliente | `request_complete` | Lote terminado, con confirmados, fallidos y cancelados |

`request_id` identifica el lote; `transfer_id` y `tile_id` relacionan payload y ACK.
Ver solo una solicitud saliente no demuestra que Java la haya procesado. Los tiles
viajan en Base64 dentro del WebSocket: no aparece una petición HTTP `.png` por tile.

### Interpretar el panel

| Dato | Significado |
|---|---|
| `cwnd` | Ventana de aplicación adaptada a ACK/latencia |
| Ventana del receptor | Máximo de tiles pendientes según la política receptora |
| En vuelo / cola | Tiles enviados sin ACK / pendientes de enviar |
| RTT aplicación | En tile entero: envío a ACK; en fragmentos: último fragmento enviado a ACK de tile |
| RTO | Intervalo base de espera antes de retransmitir |

`transfer_state` se emite tras un ACK y antes de nuevos envíos; la pantalla muestra
la última instantánea, no un monitor continuo. El RTT incluye procesamiento del
cliente. La presión manual está etiquetada como **simulación**; el visor normal
estima bytes RGBA de bitmaps, no todo el heap del navegador.

### Pruebas manuales cortas

| Prueba | Acción y resultado esperado |
|---|---|
| Otra región | Usar una flecha de diagnóstico: cambian coordenadas y aparece otro intercambio completo. |
| Recuperación | Activar **Omitir un ACK**, cargar una región y esperar. Se repite el mismo `transfer_id` con `attempt:2`; debe terminar sin fallos. Desactivar al acabar. |
| Flujo por memoria | Enviar presión simulada de 90%: `adjust_strategy` anuncia ventana receptora 2; con 20%, vuelve a 32. `cwnd` puede limitarla más. |
| Dos clientes | Abrir otra pestaña y cambiar una región: cada conexión conserva su propio estado. |
| Fragmentación | Con imagen procesada, elegir 1500 bytes y cargar una región no cacheada. Observar fragmentos y ACK; el detalle final se conserva. |
| Cancelación/reconexión | Cambiar rápidamente de vista, cancelar o reconectar: la región antigua no debe reemplazar la actual. |
| TTL/respaldo | En imagen procesada, cambiar de región y esperar más de 5 s: permanecen los visibles y el respaldo nivel 0. |

Omitir un ACK prueba recuperación de aplicación, no pérdida de paquetes TCP.
Una ventana de 1500 bytes es crédito de payload GTP, no MTU de red. Para pruebas
de navegación/bordes/teclado, consulta [visor](docs/visor_p6.md); para mediciones
comparables y hashes, [experimentos P7](docs/experimentos_p7.md).

## 5. Consultar HTTP y logs

Mientras Java sigue en la primera terminal, abre otra.

PowerShell:

```powershell
$base = 'http://localhost:8081'
$id = 'prueba_01'
Invoke-RestMethod "$base/api/images"
Invoke-RestMethod "$base/api/image/$id/status" | Format-List
Invoke-RestMethod "$base/api/image/$id/metadata" | ConvertTo-Json -Depth 10
Invoke-RestMethod "$base/api/image/$id/upload" | Format-List
(Invoke-WebRequest "$base/api/images" -UseBasicParsing).StatusCode
```

Bash:

```bash
curl -i http://localhost:8081/api/images
curl http://localhost:8081/api/image/prueba_01/status
curl http://localhost:8081/api/image/prueba_01/metadata
curl http://localhost:8081/api/image/prueba_01/upload
```

Sustituye el ID por uno existente. Después de una ingesta exitosa, `/status`
debe indicar `ready`, `sourceState:verified`, conteos completos y sin causa de error.

El log normal no imprime cada tile/ACK. Para observar HTTP y conexiones, detén
Java con **Ctrl+C** y reinicia desde la raíz:

```text
java -jar .build/server.jar --logging.level.org.springframework.web.servlet.DispatcherServlet=DEBUG --logging.level.org.springframework.web.socket=DEBUG
```

Los mensajes GTP exactos se consultan en DevTools. El silencio después del arranque
o al terminar una región no significa que Java haya dejado de atender.

## 6. Problemas frecuentes

| Síntoma | Qué revisar |
|---|---|
| Java/Maven no disponibles o versión de clases incompatible | JDK 21, `JAVA_HOME`, `PATH` y la JVM que muestra Maven |
| Falta el JAR o se ve una versión anterior | Compilar con éxito, reiniciar `.build/server.jar` y recargar sin caché |
| Puerto ocupado / conexión rechazada | Instancia Java activa, puerto anunciado y URL; usar otro puerto si corresponde |
| No aparecen frames | Abrir Network antes de recargar y seleccionar WS → tiles → Messages |
| Catálogo desaparecido | Comprobar que `GTP_WORK` sea la misma ruta de la sesión anterior |
| Cabecera válida pero sin preview | Límites 4 Mpx/16 MiB; la cabecera no valida el cuerpo PNG |
| Registro duplicado o `pending` | Continuar el mismo ID con Transferir y preparar; registrar otro solo para otra imagen |
| Error Adam7/16 bits/APNG | Variante no admitida por el decoder actual |
| `failed` | Leer causa en `/status`: corrupción, disco, permisos, presupuesto o cancelación |
| Otra ingesta activa | Hay un único procesamiento por work; esperar o cancelar al propietario |
| `ready` pero sin canvas | Revisar conexión, selección, JAR actualizado y recursos sin caché |

## 7. Verificaciones automatizadas y cierre

El build ya ejecuta pruebas Java. El smoke de ingesta requiere Node; el E2E de
navegador requiere además Playwright y un navegador instalado:

```text
node scripts/verify-ingestion.cjs .build/server.jar
node scripts/verify-browser.cjs .build/server.jar
```

Configuración de herramientas, cobertura y resultados fechados:
[verificación](docs/verificacion.md). Esos scripts levantan sus propios procesos
temporales. Los conteos históricos no son una expectativa fija para futuros builds.

Una comprobación manual completa incluye preparar un PNG hasta `ready`, navegarlo,
observar ACK y recuperación, reiniciar con el mismo work y recuperar el registro.
Para apagar, pulsa **Ctrl+C** en la terminal de Java. Al volver a arrancar, recarga
el visor o pulsa **Reconectar**.
