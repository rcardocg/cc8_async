# Servidor asíncrono de imágenes — CC8

Servidor Java 21 / Spring Boot 3.2.4 para preparar imágenes PNG y explorar sus
regiones mediante un protocolo propio sobre WebSocket: **GTP/1**.
Autores registrados en la propuesta original: Ricardo Caballeros y Cristian Sactic.

## Qué hace

El usuario elige un PNG, registra su cabecera y transfiere el original por bloques.
Java lo procesa secuencialmente y genera una pirámide de tiles en disco. El visor
solicita únicamente el nivel y la región necesarios: vista general al ajustar y
detalle nativo en 1:1, con pan, zoom y un respaldo reducido que permanece disponible.

GTP/1 añade estado independiente por cliente, ACK después de decodificar, ventanas
y presupuestos de memoria, reintentos por timeout, fragmentación opcional y
cancelación de trabajo obsoleto. **Los ACK son de aplicación:** WebSocket ya usa
TCP y GTP no sustituye su recuperación de paquetes.

Se admiten PNG no entrelazados de hasta 8 bits; 16 bits, Adam7 y APNG se rechazan.
El visor pide q3 del nivel seleccionado. El servidor puede generar q0–q2, pero la
adaptación automática de calidad y el prefetch siguen pendientes. La caché del
servidor utiliza LFU con envejecimiento y TTL.

## Ejecutar

Para compilar: **JDK 21 y Maven 3.9+** en `PATH`.

Windows / PowerShell:

```powershell
.\scripts\build.cmd
.\scripts\gtp.cmd
```

Fedora / Bash:

```bash
bash scripts/build.sh
bash scripts/gtp.sh
```

Abrir **http://localhost:8081/**. Los lanzadores generan y ejecutan
`.build/server.jar`. Java sirve también el frontend; el JAR incluye dependencias
y recursos locales. La primera compilación necesita las dependencias Maven.

- [Guía de uso y diagnóstico](GUIA_INICIO_Y_PRUEBAS.md): preparación, carga de PNG,
  navegación, DevTools, recuperación y problemas frecuentes en ambos sistemas.
- [Configuración y almacenamiento](docs/imagenes.md): `GTP_IMAGES`, `GTP_WORK`,
  puerto, presupuestos y catálogo de tiles preprocesados.

## Documentación técnica

| Necesidad | Documento principal |
|---|---|
| Entender la solución, sus algoritmos y por qué se implementó así | [RFC interno GTP-001](protocolo.md) |
| Consultar mensajes, campos y reglas de intercambio | [Contrato GTP/1](docs/protocolo.md) |
| Registrar, subir y procesar un original; consultar estados y errores HTTP | [Registro e ingesta](docs/ingesta_p3.md) |
| Reproducir pruebas y conocer su alcance | [Verificación](docs/verificacion.md) y [experimentos P7](docs/experimentos_p7.md) |
| Encontrar todas las guías y su función | [Mapa de documentación](docs/README.md) |
| Consultar cambios, propuestas y resultados anteriores | [Historia del proyecto](docs/historico/README.md) |

GTP-001 es un RFC **interno**, no una publicación IETF. El RFC explica decisiones;
el contrato detalla el intercambio; las bitácoras conservan la evolución fechada.

## Alcance de la evidencia y pendientes

La última ejecución documentada registra 77 casos Java aprobados y E2E Chromium
en Fedora. P7 conserva 18 ensayos iniciales con una imagen local de 4193×4193 y un
PNG sintético de 3073×1537. Los detalles y resultados anteriores por plataforma
están en [verificación](docs/verificacion.md); estas cifras no representan una
nueva ejecución de pruebas al reorganizar la documentación.

Falta evaluar originales gigantes, legibilidad de números, RSS/disco y múltiples
clientes, y repetir la última revisión en Windows y en el entorno sin Internet.
La demo `demo_numeros` permite comprobar el protocolo, pero no acredita el
procesamiento del original de 24 GB ni de los archivos de evaluación de hasta 93 GB.

Fuentes de evaluación: [enunciado](Proyecto_Servidor_Asi_ncrono.pdf) y
[referencia visual](image_Indicators.pdf). La indicación posterior comunicada
prioriza 40% funcionamiento/usabilidad y 60% protocolo; los tamaños de 17, 28, 55 y
93 GB corresponden a máximos de 20, 40, 80 y 115 puntos. Tener tiles y zoom por sí
solo no demuestra control de flujo ni recuperación.

Los pendientes técnicos se centralizan en el [RFC, sección 15](protocolo.md#15-pendientes-y-referencias).
