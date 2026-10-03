# Verificación de la revisión

Fecha: 2026-10-02. Entorno: Windows, Temurin Java 21.0.11, Maven 3.9.9.

## Comandos reproducibles

```powershell
mvn "-Dgigapixel.buildDirectory=.build" test package
node --check src/main/resources/static/viewer.js
git diff --check
```

`gigapixel.buildDirectory` permite generar resultados sin usar los binarios
versionados previamente en `target/`. En este entorno Maven se invocó mediante la
ruta completa a `apache-maven-3.9.9/bin/mvn.cmd` dentro del directorio temporal de
herramientas, ya que no estaba en PATH.

## Pruebas automatizadas

| Suite | Casos | Cobertura |
|---|---:|---|
| `MetadataAndTileServiceTest` | 6 | Catálogo real, límites, 404, grandes conteos, lectura y dimensiones de tiles |
| `TileWebSocketHandlerTest` | 13 | Aislamiento, ACKs, ventanas, backoff, fallo terminal, memoria, cancelación y trabajo asíncrono |
| `ServerIntegrationTest` | 2 | HTTP/recursos y WebSocket real con decodificación PNG y ACK omitido |

Primera ejecución: **21 pruebas, 0 fallos, 0 errores**. La primera invocación anterior
a esa ejecución detectó una colisión de nombre de propiedad Maven, corregida en
fase 00. La verificación final de empaquetado y navegador se registra abajo.

### Resultado final ejecutado

- Maven `test package`: **BUILD SUCCESS**, 21 pruebas, 0 fallos, 0 errores, 0 omitidas.
- JAR ejecutable generado: `.build/server-0.0.1-SNAPSHOT.jar`.
- `node --check` sobre `viewer.js` y `scripts/verify-browser.cjs`: correcto.
- `git diff --check`: sin errores de whitespace; Git avisó de conversión LF/CRLF
  según la configuración ya existente del entorno.
- Playwright 1.58.2 con Edge headless: **PASS** en todos los escenarios descritos abajo.
  La aplicación del JAR arrancó en un puerto libre y se detuvo al finalizar la prueba.
- Los artefactos de pruebas JUnit están en `.build/surefire-reports/` (no versionados).

Durante la revisión se reemplazaron los monitores `synchronized` alrededor de la
E/S por `ReentrantLock` para evitar fijar carriers de hilos virtuales en Java 21;
la ejecución final de las suites corresponde a esa versión corregida.

## Prueba de navegador

`scripts/verify-browser.cjs` usa Playwright con Edge instalado, arranca el JAR en
un puerto libre y verifica:

1. Tiles PNG decodificados, 12 imágenes por región.
2. Dos clientes sin mezcla de estado.
3. Retransmisión tras omitir un ACK de aplicación.
4. Ventana receptora al simular presión de memoria y recuperarla.
5. Dos cambios rápidos de región sin repintado tardío.
6. Cancelación, limpieza y reconexión.
7. Cero errores JavaScript y recursos HTTP del mismo origen.

Dependencias opcionales: Node.js, paquete `playwright` y Edge (o Chrome mediante
`BROWSER_CHANNEL=chrome`). Playwright puede instalarse fuera del repositorio y
exponerse por `NODE_PATH`; no es una dependencia de ejecución del servidor.

## Pendiente de evaluación real

No están incluidos los datasets gigantes ni un pipeline para convertir sus
originales. Estas pruebas verifican el protocolo y la base del visor con tiles
pequeños; no certifican memoria/latencia/legibilidad con archivos de 24–93 GB.
La prueba de navegador exige recursos locales, pero no sustituye desconectar la
red durante la evaluación final. Usar DevTools para revisar frames, transferencias,
caché, calidad numérica y consumo de recursos con los datasets oficiales.
