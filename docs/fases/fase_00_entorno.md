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
