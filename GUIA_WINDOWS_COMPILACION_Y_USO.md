# Windows — Guía de compilación, ejecución y carga de imágenes

**Actualización:** 2026-10-05.

Esta guía usa **PowerShell**, Java 21 y los lanzadores `.cmd` del proyecto.
Está ubicada en la raíz, junto a `GUIA_INICIO_Y_PRUEBAS.md`.

## 1. En qué punto de funcionamiento estamos

Actualmente puedes **inspeccionar un PNG, registrarlo, transferirlo al servidor y
generar su pirámide de tiles**. También puedes probar el protocolo GTP con la demo
integrada y con el catálogo plano existente.

| Parte | Qué funciona actualmente |
|---|---|
| P0 — Entorno | Rutas configurables, originales y trabajo separados, compilación y arranque con `.cmd`. |
| P1 — Inspección | Seleccionar un PNG, leer su cabecera de 33 bytes, mostrar dimensiones y estimaciones, descargar informe y ver una vista previa si es pequeño. |
| P2 — Registro | Guardar ID y metadata, consultar estado, detectar duplicados y conservar registros al reiniciar. |
| P3 — Procesamiento inicial | Transferir por bloques, procesar PNG compatibles secuencialmente, generar tiles/niveles, informar progreso, cancelar y reintentar. |
| P4 — Calidades y caché | Generar q0–q3 bajo demanda, caché LRU en servidor, endpoint `/api/cache/stats`. WebSocket soporta `z` y `q`. |
| P5 — Protocolo multinivel y GTP-RA | **En desarrollo**: WebSocket GTP/2 con z/q, SACK, scoreboard, RTT/RTTVAR/Karn, cancelación parcial, calidad adaptativa. |
| Protocolo existente | Demo y catálogo plano: solicitar regiones, confirmar tiles, retransmisión, ventana, cancelación. |
| P6 — Visor pan/zoom | Pendiente: navegación normal sobre pirámide P3/P5. |

**Importante:** cuando tu imagen llegue a `ready`, sus tiles estarán preparados en
disco. **Todavía no podrás navegar esa pirámide desde el visor GTP actual**: el botón
**Cargar región** seguirá deshabilitado para imágenes multinivel. Es el límite actual
de integración con P5/P6, no una señal de que la ingesta haya fallado.

La vista previa local de un PNG pequeño y la demo son recorridos distintos:

- **Vista previa local:** muestra el archivo seleccionado usando el navegador.
- **Demo GTP:** muestra tiles enviados por Java a través de WebSocket.
- **Tu imagen procesada por P3:** genera archivos de tiles y estado persistido; su
  visualización multinivel en el cliente está pendiente.

### PNG que puedes procesar ahora

- PNG **sin entrelazado Adam7**.
- Grayscale e indexados de 1, 2, 4 u 8 bits.
- RGB, grayscale con alpha y RGBA de 8 bits.
- PNG de 16 bits, Adam7 y APNG no se procesan en este incremento.

P1 puede inspeccionar cabeceras de variantes que P3 todavía no procesa. Una
**cabecera válida** no garantiza que el archivo completo esté íntegro ni sea compatible
con el decoder actual. Para la primera prueba usa un PNG pequeño, RGB/RGBA de 8 bits,
sin entrelazado.

## 2. Qué necesitas abrir y tener instalado

1. **Una terminal PowerShell** en la raíz del proyecto para compilar y mantener Java abierto.
2. **Edge o Chrome** para usar la interfaz.
3. Opcionalmente, **otra terminal PowerShell** para consultar el estado por HTTP.

Requisitos:

| Herramienta | Para qué se usa |
|---|---|
| JDK 21 | Compilar y ejecutar Java. |
| Maven 3.9+ | Resolver dependencias, compilar, ejecutar pruebas y empaquetar el JAR. |
| Node.js | Solo para los scripts opcionales de verificación. No es necesario para usar el visor. |
| Playwright | Solo para la prueba automatizada de navegador. No es necesario para abrir la interfaz manualmente. |

No necesitas arrancar un frontend separado, una base de datos ni Docker.
El JAR contiene los recursos del cliente y las dependencias de ejecución.
La primera compilación necesita tener disponibles las dependencias Maven; una vez
empaquetado, el servidor se ejecuta sin CDN ni servicios externos.

## 3. Abrir el proyecto y comprobar Java/Maven

En este equipo, la carpeta es:

```powershell
Set-Location -LiteralPath "C:\Users\crist\Desktop\proyecto cc8_2\cc8_async"
```

Si moviste el proyecto, cambia esa ruta. En VS Code también puedes abrir una terminal
integrada desde la carpeta del proyecto.

Ejecuta:

```powershell
Test-Path -LiteralPath '.\pom.xml'
java -version
javac -version
mvn -version
```

**Qué deberías ver:**

- `True` para `pom.xml`.
- Java y `javac` versión **21**.
- Maven versión **3.9 o superior**, utilizando Java 21.

### Si PowerShell dice que `mvn` no se reconoce

Maven debe estar en `PATH`. Durante las validaciones de este equipo se usó una
instalación existente en la carpeta temporal de herramientas. Puedes comprobar si
todavía está disponible y habilitarla **solo para esta terminal**:

```powershell
$mavenBin = Join-Path $env:LOCALAPPDATA 'Temp\opencode\apache-maven-3.9.9\bin'
if (Test-Path -LiteralPath (Join-Path $mavenBin 'mvn.cmd')) {
    $env:Path = "$mavenBin;$env:Path"
    mvn -version
} else {
    Write-Host 'Esa instalación temporal ya no existe. Configura la carpeta bin de tu instalación de Maven 3.9+ en PATH.'
}
```

Para una instalación propia, usa su ruta real. Por ejemplo:

```powershell
# Ejemplo: reemplazar por la carpeta donde realmente instalaste Maven.
$env:Path = "C:\herramientas\apache-maven-3.9.9\bin;$env:Path"
mvn -version
```

Si Maven muestra Java 17 u otra versión, configura `JAVA_HOME` con la carpeta del
JDK 21 instalado y coloca su `bin` en `PATH` antes de compilar.

## 4. Elegir dónde guardar originales y archivos de trabajo

Para comenzar, puedes usar estas dos carpetas dentro de tu perfil de Windows:

```powershell
$env:GTP_IMAGES = Join-Path $env:USERPROFILE 'gtp-datos\originales'
$env:GTP_WORK = Join-Path $env:USERPROFILE 'gtp-datos\work'
$env:GTP_PORT = '8081'
$env:GTP_INGEST_MEMORY_MIB = '128'
```

| Variable | Significado |
|---|---|
| `GTP_IMAGES` | Originales que Java puede leer directamente cuando usas la entrada local del servidor/CLI. |
| `GTP_WORK` | Registros, copias subidas, temporales y tiles generados. Debe tener espacio y permiso de escritura. |
| `GTP_PORT` | Puerto del servidor; por defecto 8081. |
| `GTP_INGEST_MEMORY_MIB` | Presupuesto de buffers/margen para la ingesta HTTP. No equivale a un límite de toda la memoria del proceso. |

El servidor crea las carpetas si faltan. Originales y work deben estar **separados**:
no pueden ser la misma carpeta ni estar uno dentro del otro.

**Si cargas desde el selector del navegador**, el PNG puede estar en cualquier
carpeta de tu equipo. No necesitas copiarlo a `GTP_IMAGES`: P3 enviará una copia por
bloques hacia `GTP_WORK`, conservando el original seleccionado.

Para imágenes grandes, puedes sustituir ambas rutas por carpetas de un disco local
con espacio suficiente, por ejemplo `D:/gpx/originales` y `D:/gpx/work`. El espacio
necesario incluye la copia comprimida subida, los tiles y los temporales; no basta
con disponer únicamente del tamaño del archivo PNG.

Estas variables duran la sesión de PowerShell. **Vuelve a configurarlas al abrir
otra terminal para arrancar el servidor**, o podrías apuntar a otro catálogo.
Sin configuración, los lanzadores usan `data/originales` y `data/work` bajo el proyecto.

## 5. Compilar y ejecutar las pruebas

Desde la raíz:

```powershell
.\scripts\build.cmd
```

Espera a que termine. El lanzador compila, ejecuta las pruebas y genera:

```text
.build/server.jar
```

**Resultado esperado:**

```text
Tests run: 53, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

Ese fue el resultado de la revisión actual en Windows: **52 pruebas aprobadas y una
omitida por requerir permisos POSIX**. El número puede crecer con futuras tareas.

Comprueba el artefacto:

```powershell
Test-Path -LiteralPath '.\.build\server.jar'
```

Debe mostrar `True`. Si el build falla, revisa primero su error: un JAR existente
puede pertenecer a una compilación anterior.

Para esta guía usa siempre `.build/server.jar` y los lanzadores. Así trabajas con
el artefacto estable y evitas confundirlo con los binarios históricos de `target`.

## 6. Arrancar el servidor

En la misma terminal donde configuraste las variables:

```powershell
.\scripts\gtp.cmd
```

**Qué deberías ver en la terminal:**

```text
Tomcat started on port 8081 (http) with context path ''
Started GigapixelServerApplication ...
```

La terminal permanece ocupada mientras Java atiende solicitudes. Déjala abierta.

En el navegador, entra a:

**http://localhost:8081/**

Abre esa URL; el archivo `index.html` directamente desde el explorador no sustituye
el arranque del servidor.

### Si el puerto 8081 está ocupado

Cuando el intento de arranque haya terminado, ejecuta:

```powershell
$env:GTP_PORT = '8082'
.\scripts\gtp.cmd
```

Entonces usa `http://localhost:8082/`. Las consultas HTTP de las secciones siguientes
también deben usar ese puerto.

## 7. Primera comprobación: la demo del protocolo

Con la configuración predeterminada, la sección **Visor de tiles preparados** debe
mostrar `demo_numeros`.

Deberías ver:

1. Metadata de **4096 × 4096**, tiles de **256 px**.
2. Una región con **12 tiles**: cuatro columnas y tres filas.
3. Números y coordenadas en los tiles sintéticos.
4. El mensaje:

```text
Terminada: 12/12 confirmados; 0 fallidos. Puede volver a cargar la región.
```

Si hay otra imagen seleccionada, elige `demo_numeros` y usa **Cargar región**.

En **Diagnóstico del protocolo** puedes observar `fetch_tiles`, `tile_data`,
`ack_tile` y `request_complete`. Activar **Omitir un ACK** y volver a cargar prueba
la retransmisión de aplicación. Las flechas cambian la región de la demo.

Este recorrido confirma que funcionan Java, los recursos web y WebSocket. La demo
es sintética y no representa el PNG que vas a seleccionar arriba.

## 8. Cargar tu PNG: recorrido completo actual

### Paso A — Elegir e inspeccionar: P1

1. Ve a **Abrir una imagen de tu equipo**.
2. Pulsa **Elegir PNG** y selecciona un archivo pequeño compatible.
3. Conserva **256 px** como tamaño de tile para la primera prueba.
4. Espera el resultado de inspección.

**Qué deberías ver:**

- `Cabecera válida: nombre.png. Se enviaron 33 bytes al servidor.`
- Nombre, tamaño comprimido, dimensiones, profundidad/color y entrelazado.
- Estimación de niveles, tiles, memoria y disco.
- El botón **Descargar informe JSON** habilitado.

Si el PNG tiene como máximo **4 megapíxeles y 16 MiB**, aparecerá una **vista previa
local**, con controles Ajustar, 1:1, + y −. También puedes arrastrarla/desplazarla.

Si supera cualquiera de esos límites, se indicará que la vista previa se omite por
tamaño. Eso es esperado: P1 evita decodificar la imagen grande completa en el navegador.

En este punto todavía **no se ha registrado ni transferido el PNG completo**.

### Paso B — Registrar el ID: P2

1. En **Identificador del registro**, escribe por ejemplo `prueba_windows_01`.
2. Usa letras, números, guion o guion bajo, sin espacios ni extensiones obligatorias.
3. Pulsa **Registrar imagen · P2**.

**Qué deberías ver:**

```text
Registrada prueba_windows_01: pending. ...
```

El ID aparece en el catálogo. Si lo seleccionas y pulsas **Consultar estado**:

- Estado `pending`.
- Cero tiles procesados.
- Mensaje indicando que falta el original/procesamiento.
- **Cargar región** deshabilitado.

En disco existe `<GTP_WORK>/prueba_windows_01/meta.json`. Los niveles de la metadata
son todavía **previstos**, no archivos generados.

Si registras otra vez el mismo ID, recibirás un conflicto por duplicado. Para seguir
con ese registro, conserva el ID y pasa a P3; no necesitas registrarlo de nuevo.

### Paso C — Transferir el original y procesarlo: P3

Con el mismo PNG seleccionado y el mismo ID escrito, pulsa:

**Transferir / reanudar y procesar · P3**

El recorrido esperado es:

```text
pending
   ↓ subida de la copia del PNG en bloques de hasta 1 MiB
processing
   ↓ lectura secuencial, validación y generación de tiles/niveles
ready
```

**Durante la subida:**

- El panel P3 muestra bytes transferidos respecto al tamaño total.
- El archivo original de tu equipo permanece en su ubicación.
- La copia recibida crece en `<GTP_WORK>/<id>/source.part`.
- El registro puede seguir indicando `pending`: aún no comenzó el decoder.

**Durante el procesamiento:**

- El panel muestra `processing` y un contador de tiles.
- Java decodifica el PNG por filas y usa una banda temporal en disco.
- El progreso se actualiza por bloques de trabajo; puede avanzar a saltos.
- Los archivos todavía son provisionales. Los niveles/calidades no se publican
  hasta terminar correctamente la pirámide.

En una imagen pequeña, estas etapas pueden pasar tan rápido que veas directamente
el resultado final.

### Paso D — Confirmar que terminó correctamente

**Qué deberías ver al finalizar:**

- Estado `ready` en el panel P3.
- Tiles procesados iguales al total previsto.
- Mensaje de pirámide completa y validación de integridad.
- En metadata, `completedLevels` con todos los niveles y `availableQualities: [3]`.

Ejemplo ilustrativo: un PNG de **300 × 280** con tiles de **256** produce:

- Nivel `z=1`: resolución nativa, cuatro tiles con bordes parciales.
- Nivel `z=0`: imagen reducida de 150 × 140, un tile.
- Total: **5 tiles**, `completedLevels: [0, 1]`.

Tu cantidad real depende de las dimensiones y del tamaño de tile seleccionado.

**Lo esperado ahora no es que aparezca tu imagen completa en el visor de abajo.**
El mensaje de transporte multinivel pendiente de P5 y el botón **Cargar región**
deshabilitado son coherentes con este avance. Puedes seguir usando `demo_numeros`
para probar GTP y comprobar los tiles de tu imagen en disco.

## 9. Ver el estado por HTTP y comprobar los archivos

Abre una segunda terminal PowerShell. Estas consultas funcionan aunque esa terminal
no tenga configuradas las variables de carpetas:

```powershell
$base = 'http://localhost:8081'
$id = 'prueba_windows_01'

Invoke-RestMethod "$base/api/images"
Invoke-RestMethod "$base/api/image/$id/status" | Format-List
Invoke-RestMethod "$base/api/image/$id/metadata" | ConvertTo-Json -Depth 10
Invoke-RestMethod "$base/api/image/$id/upload" | Format-List
```

Al terminar, `/status` debe informar `state: ready`, `sourceState: verified`,
conteos iguales y sin causa de error. `/upload` muestra los bytes recibidos de la copia.

Para revisar la salida en el Explorador de Windows, abre la ruta que configuraste
en `GTP_WORK`. Si usaste el ejemplo de esta guía, puedes pegar:

```text
%USERPROFILE%\gtp-datos\work
```

Estructura final de una imagen subida desde el navegador:

```text
work/
  prueba_windows_01/
    meta.json
    source.part
    tiles/
      complete.sha256
      0/
        0_0.png
      1/
        0_0.png
        1_0.png
        ...
```

Los niveles adicionales dependen de la imagen. `z=0` es la vista reducida más
pequeña y `z=maxZoom` es la resolución nativa. Puedes abrir individualmente un tile
PNG para comprobar su contenido; los tiles de los bordes pueden medir menos de 256 px.

La copia `source.part` se conserva después de completar la ingesta. Su nombre no
significa por sí solo que el procesamiento haya quedado incompleto: consulta el estado.

## 10. Detener, continuar o reintentar

### Detener una subida

Pulsa **Detener transferencia / procesamiento**. Se conservan los bloques recibidos.
Para continuar, selecciona el **mismo archivo**, escribe el **mismo ID** y pulsa P3.
El servidor informa el offset y el cliente continúa desde allí.

### Cancelar el procesamiento

El mismo botón solicita interrumpir el decoder. El registro termina en `failed`
con una causa. Puedes reintentar P3 cuando ya haya terminado la cancelación.

**La subida sí se reanuda por offset; el procesamiento se reinicia desde el comienzo
del PNG.** Todavía no hay recuperación del estado interno de Deflate a mitad de imagen.

### Corregir una copia subida incorrecta

Para un registro pendiente/fallido, **Reiniciar transferencia del ID** descarta la
copia recibida y permite subirla de nuevo. Si vas a cambiar de original, lo más claro
es crear otro ID. Una cabecera y un tamaño iguales no demuestran igualdad de contenido.

### Cerrar y volver a abrir el servidor

1. En la terminal de Java, pulsa **Ctrl+C**.
2. Para volver, configura las mismas variables si abriste otra terminal.
3. Ejecuta `.\scripts\gtp.cmd` y abre el mismo puerto.
4. Usa **Actualizar catálogo** y **Consultar estado**.

Los registros `pending` y `ready` permanecen en work. Si Java se interrumpió durante
un procesamiento, al reiniciar se registra el fallo para reintentarlo explícitamente.
Si solo recargas la página mientras Java sigue abierto, un procesamiento ya iniciado
continúa en el servidor; puedes consultar su estado en el catálogo.

## 11. Procesar desde PowerShell sin subir la imagen por el navegador

Esta entrada es útil si el original ya está disponible en la máquina donde corre
Java. El archivo debe estar bajo la raíz configurada en `GTP_IMAGES`.

Ejemplo, suponiendo que existe `pequena.png` dentro de esa carpeta y que configuraste
las variables en esta terminal:

```powershell
# Solo inspeccionar cabecera.
java -Xmx128m -jar .build/server.jar cli inspect "$env:GTP_IMAGES/pequena.png" --pretty

# Revisar estimaciones sin escribir tiles ni registrar la imagen.
java -Xmx128m -jar .build/server.jar cli ingest "$env:GTP_IMAGES/pequena.png" --dry-run --image-id prueba_cli_01 --max-memory-mib 64 --pretty

# Procesamiento real. Ejecutar después de revisar que el preflight sea adecuado.
java -Xmx128m -jar .build/server.jar cli ingest "$env:GTP_IMAGES/pequena.png" --image-id prueba_cli_01 --max-memory-mib 64 --pretty
```

El último comando registra el ID si no existe y genera los tiles. Al terminar
correctamente devuelve JSON con `state: ready`; el CLI no arranca Spring.

Puedes arrancar el servidor después, o actualizar su catálogo si ya está ejecutándose
con el mismo work. Solo se admite una ingesta activa por work; no inicies otra desde
el navegador al mismo tiempo. Los originales locales no se modifican y esta entrada
no necesita crear una copia `source.part`.

## 12. Problemas frecuentes y qué revisar

| Lo que ocurre | Qué significa / qué hacer |
|---|---|
| `mvn` no se reconoce | Configurar Maven en PATH; ver sección 3. |
| Java/Maven usan una versión incorrecta | Revisar `java -version`, `javac -version`, `mvn -version` y `JAVA_HOME`. |
| Falta `.build/server.jar` | Ejecutar `scripts/build.cmd` y comprobar `BUILD SUCCESS`. |
| El navegador no conecta | Confirmar que Java siga abierto y que la URL use el puerto anunciado por Tomcat. |
| La página parece anterior a los cambios | Detener Java, recompilar, arrancar el JAR nuevo y recargar con Ctrl+F5. |
| El catálogo anterior desapareció | Comprobar que `GTP_WORK` sea la misma ruta del arranque anterior. |
| `Cabecera válida`, pero sin preview | Revisar límites de 4 Mpx/16 MiB; también puede haber un cuerpo PNG corrupto pese a un IHDR válido. |
| Registro duplicado | Usar otro ID para otra imagen; para continuar el mismo registro, pasar directamente a P3. |
| `pending` después de registrar | Es correcto. Falta pulsar P3 y completar la entrada del original. |
| Error por 16 bits o Adam7 | Esa variante todavía no está soportada por P3; puede inspeccionarse/registrarse, pero no procesarse. |
| `failed` durante ingesta | Consultar la causa en el panel o `/status`: integridad, E/S, disco o cancelación. |
| Otra ingesta/escritura activa | Esperar o cancelar el trabajo propietario; hay un único procesamiento por work. |
| Espacio insuficiente | Elegir un disco con espacio para copia, pirámide y banda temporal; conservar el original. |
| `ready`, pero Cargar región deshabilitado | Esperado para pirámides P3: el transporte multinivel y el visor completo corresponden a P5/P6. |

## 13. Verificaciones automatizadas opcionales

La compilación normal ya ejecuta las pruebas Java. Con Node instalado puedes probar
una ingesta real sobre un PNG sintético completo, sin preparar un archivo manualmente:

```powershell
node scripts/verify-ingestion.cjs .build/server.jar
```

El script crea temporales, procesa con heap de 64 MiB y comprueba persistencia entre
reinicios. Debería terminar con `status: PASS`. No reemplaza la prueba con los PNG
gigantes reales del curso ni mide la memoria total del proceso.

Para la prueba de navegador, con Playwright disponible como herramienta externa:

```powershell
# Reemplazar por la carpeta node_modules que realmente contiene Playwright.
$env:NODE_PATH = 'C:/herramientas/node_modules'
$env:BROWSER_CHANNEL = 'msedge'
node scripts/verify-browser.cjs .build/server.jar
```

El script arranca su propio servidor y comprueba P1/P2/P3 más el recorrido GTP de
la demo. Debería imprimir `PASS` y cerrar sus procesos de prueba.

## 14. Qué considerar una prueba manual exitosa hoy

1. Compilas con `BUILD SUCCESS` y arrancas `.build/server.jar`.
2. La demo muestra 12 tiles confirmados sin fallos.
3. Seleccionas un PNG compatible y obtienes informe P1.
4. Lo registras como `pending` con un ID propio.
5. Pulsas P3, se transfiere/procesa y llega a `ready`.
6. Los conteos coinciden y encuentras los PNG por nivel en work.
7. Reinicias con el mismo work y el registro conserva su estado.
8. Puedes volver a la demo y seguir probando el protocolo.

Ese es el punto funcional actual. El siguiente tramo conecta las pirámides con
calidades progresivas, transporte multinivel y navegación normal de pan/zoom, además
de validar originales reales y repetir las pruebas nuevas en Fedora.

### Referencias del proyecto

- [Guía general de inicio y diagnóstico](GUIA_INICIO_Y_PRUEBAS.md).
- [Ingesta P3: contrato, almacenamiento y límites](docs/ingesta_p3.md).
- [Resultados de verificación por ambiente](docs/verificacion.md).
- [Contrato GTP implementado](docs/protocolo.md).
- [Plan temporal P0–P7](plan-temp.html).
