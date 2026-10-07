# Fase 00 — Entorno y trazabilidad

## 2026-10-02 · Implementación y resolución

**Problemas:** Maven apuntaba a Java 17 aunque el enunciado requiere 20 o 21;
faltaban pruebas; artefactos compilados estaban versionados y mezclados con fuentes;
`mvn` no estaba en PATH; la documentación confundía propuesta con implementación.

**Resolución:**
- Configuración Maven actualizada a Java 21 y agregado `spring-boot-starter-test`.
- Añadido `.gitignore` para nuevas salidas `target/`, `bin/`, `.build/`, `.class`,
  logs y datos de imágenes. Los archivos ya rastreados por Git no se desversionan
  automáticamente por este cambio.
- Agregada propiedad `gigapixel.buildDirectory` para verificar en `.build/`.
- Conservada la propuesta inicial como histórico; README, contrato y bitácoras
  describen el estado actual y las instrucciones de ambos PDFs.
- Maven 3.9.9 utilizado desde el directorio temporal de herramientas; JDK instalado:
  Temurin 21.0.11. No se cambió la configuración global de Java/Maven.

**Incidencia durante la verificación:** la primera propiedad de salida se llamó
`build.outputDirectory`, que colisionó con una expresión del modelo Maven y generó
un ciclo. Se renombró a `gigapixel.buildDirectory`; la compilación y pruebas pasaron.

**Evidencia:** suites JUnit, integración HTTP/WebSocket y prueba de navegador
documentadas en [verificacion.md](../verificacion.md).

**Pendiente:** instalar Maven en PATH para el flujo habitual del equipo. Los binarios
ya versionados pueden retirarse del seguimiento en una limpieza dedicada; existían
cambios staged antes de la revisión. La compilación del IDE puede regenerarlos.

## 2026-10-04 · P0: rutas y arranque multiplataforma

**Objetivo:** separar originales de tiles de trabajo y reproducir el arranque en
Fedora y Windows sin rutas personales dentro de la configuración compartida.

**Fallo observado:** las rutas relativas dependían del directorio de ejecución;
el directorio de datos no se creaba al arrancar; la raíz se resolvía de nuevo por
tile; `.vscode/settings.json` incluía rutas absolutas de Java de Fedora.

**Cambio:**
- `GTP_IMAGES`, `GTP_WORK` y `GTP_PORT` configuran originales, trabajo y puerto.
- `ImagesLayout` crea/resuelve las raíces, exige separación y lectura, comprueba
  escritura del work sin escribir en originales existentes de solo lectura y
  expone espacio utilizable.
- El catálogo plano sigue cargándose desde el work. La raíz canónica se conserva;
  los tiles siguen comprobándose contra enlaces que salgan de ella.
- Lanzadores Bash/cmd ejecutan desde la raíz, propagan argumentos y códigos de
  salida; el build genera `.build/server.jar` y ejecuta las pruebas.
- Se ignoran `/data/`, `/work/` y la configuración local; se eliminan rutas
  personales del IDE. La configuración de Java queda en preferencias del usuario.
- Instrucciones y ejemplo local en `docs/imagenes.md` y `config/`.

**Verificación (Fedora):**
- `bash scripts/build.sh`: BUILD SUCCESS; 26 pruebas, 0 fallos, errores u omisiones.
  Incluye permisos de solo lectura, rutas con espacios, raíces superpuestas y
  rechazo de un tile enlazado fuera del work.
- Lanzador invocado desde `/tmp/opencode` con `GTP_IMAGES`/`GTP_WORK` con espacios
  y `GTP_PORT=0`: crea directorios y `/api/images` devuelve `demo_numeros`.
- Work sin escritura: arranque termina con código 1 e identifica ruta y causa.
- `bash -n`, `git diff --check` y `git check-ignore` correctos. El estado Git no
  cambia por las pruebas de arranque; se compiló en `.build/`.

**Límites pendientes:** los `.cmd` aún requieren ejecución real en Windows. P0 no
incluye CLI, ingesta, estimación/bloqueo por espacio insuficiente ni migración
automática de `data/images`. Para un catálogo existente, configurar `GTP_WORK`
con su ubicación o trasladarlo manualmente. Los originales no se procesan todavía.

## 2026-10-04 · P1: CLI PNG y corrección de alcance

**Objetivo:** inventariar originales y estimar pirámide, memoria y disco sin
cargar imágenes gigantes ni iniciar Spring.

**Fallo observado:** el plan asumía contenedores ZIP; el usuario corrigió que
todos los originales son PNG y deben navegarse como un visor normal. La idea
de extracción y lectores multi-formato no correspondía al dataset real.

**Cambio:** eliminado el `ZipProbe` creado durante P1 y reemplazado el documento
temporal con un plan PNG. `main` despacha `GtpCli` antes de Spring. `PngInspector`
lee solamente 33 bytes y valida firma/IHDR/CRC. `image/PyramidMath` calcula niveles
con enteros/long y overflow explícito. `ladder` ordena sin recursión y conserva
errores. `ingest --dry-run` consulta espacio sin crear directorios ni escribir
tiles; diferencia disco insuficiente, filas excesivas y Adam7. Sin `--dry-run`
se rechaza la ingesta, que todavía no existe.

**Verificación (Fedora):**
- `bash scripts/build.sh`: BUILD SUCCESS, 34 pruebas, 0 fallos/errores/omisiones.
- JAR real y lanzador invocados desde otra carpeta: inspect de PNG válido
  300×280, ladder y dry-run con rutas con espacios y heap 64 MiB; sin Spring.
- Archivo disperso sintético de tamaño lógico 93 GB con IHDR válido: inspección
  con `-Xmx64m`, RSS 106648 KiB (~104 MiB), sin leer el cuerpo. No representa una
  imagen real decodificable ni una prueba de ingesta de 93 GB.
- Error de archivo ausente: salida 1 y JSON en stderr. Work inexistente sigue
  sin crearse tras dry-run. `git diff --check` correcto.

**Límites:** lectura completa IDAT/IEND, decoder secuencial, tiles y pan/zoom
multinivel pendientes. Estimaciones RGBA8 y dos filas no garantizan memoria ni
disco reales, especialmente con 16 bits/Adam7. Pruebas reales del curso y Windows
pendientes; instrucciones en `docs/pruebas_png_p1.md`.

## 2026-10-05 · P1: entrada desde el cliente

**Objetivo:** elegir PNG desde el explorador del sistema sin rutas quemadas ni
trasladar originales al repositorio, para probar P1 desde la interfaz.

**Fallo observado:** solo se podían inspeccionar originales escribiendo rutas en
CLI; el selector del cliente mostraba únicamente el catálogo de tiles preparados.

**Cambio:** cliente rediseñado en selector/informe P1 y visor de tiles. El navegador
lee `file.slice(0,33)` y envía solo la cabecera a `POST /api/png/inspect`, que comparte
validador y pirámide con CLI. El endpoint limita lectura a 34 bytes y rechaza
cuerpos mayores de 33. No accede a una ruta enviada ni guarda el original.
Informe descargable, recálculo de tile-size, respuestas antiguas descartadas y
liberación de URLs. Preview local con zoom y desplazamiento limitado a 4 Mpx y
16 MiB, independiente de GTP. El visor de tiles sigue disponible.

**Verificación:** `bash scripts/build.sh`: 35 pruebas, 0 fallos/errores/omisiones.
Prueba HTTP real verifica cabecera, rechazo de cuerpos grandes y catálogo/work
sin cambios. E2E con Playwright y Chromium de Fedora aprobado: selector PNG,
33 bytes por inspección, preview/zoom, informe descargado, preview grande omitido,
errores y pruebas GTP existentes de dos clientes/recuperación/memoria/cancelación.
Herramientas de navegador instaladas solo en `/tmp/opencode`, fuera del proyecto.

**Límites de este corte P1:** abrir un original no entrega su ruta ni contenido a
Java; el tamaño HTTP es declarado por el navegador. En esta fecha, la integración
de bloques, ingesta y visor multinivel seguía pendiente; P3–P6 la añadieron después.

## 2026-10-05 · Reproducción Windows y continuidad Fedora

**Objetivo:** mantener ambos ambientes y reproducir P2 antes de conectar P3.

**Fallo observado:** el traspaso describía P2 como no compilado, pero el repositorio
ya contenía su cierre Fedora, contrato y pruebas. Maven no estaba en PATH de Windows;
no hay Fedora/WSL ejecutable en este equipo.

**Cambio:** reutilizadas herramientas existentes fuera del repositorio, PATH/NODE_PATH
solo por sesión; código Java y scripts de pruebas compartidos, con instrucciones
Bash/cmd/PowerShell. La configuración P3 usa `GTP_INGEST_MEMORY_MIB` y raíces portables.
Se conservan los binarios modificados previamente en `target/classes`; builds en `.build`.
`protocolo.md` y `plan-temp.html` ya están rastreados en esta copia y se actualizan.

**Verificación Windows:** P2 base: 40 casos, 39 aprobados y 1 omitido por POSIX; E2E Edge
aprobado. Con P3: 53 casos, 52 aprobados y la misma omisión. Ejecutados `build.cmd` y
`gtp.cmd`; este último desde otra carpeta con ruta de proyecto con espacios. Git Bash:
análisis de sintaxis y `gtp.sh cli help` correctos, sin atribuirlo a Fedora.

**Límites:** repetir las nuevas pruebas en Fedora y completar matriz de permisos/recursos
con originales reales. Evidencia previa Fedora preservada. Procedimientos/resultados
en [verificacion.md](../verificacion.md) e [ingesta_p3.md](../ingesta_p3.md).
