# P2 — Registro de originales y metadata multinivel

**Referencia archivada el 2026-10-07.** Ubicación original: `docs/registro_p2.md`.
Se conserva la revisión P2 y sus notas posteriores para trazabilidad. El contrato
actual de registro está integrado en [registro e ingesta](../ingesta_p3.md#registro-y-metadata-p2).
[Índice histórico](README.md).

**Revisión documental 2026-10-06:** el registro forma parte de la solución descrita
en el [RFC interno GTP-001](../../protocolo.md); la navegación P5/P6 ya está integrada.
Las cifras de pruebas P2 siguientes corresponden a su ejecución histórica.

## Qué se puede hacer

Desde el cliente, elegir un PNG, inspeccionarlo, escribir un identificador y pulsar
**Registrar imagen**. Se vuelve a validar su cabecera en el servidor y se guarda
un registro persistente. El identificador aparece en el catálogo sin reiniciar.

Registro no significa ingesta: el original permanece en la máquina del navegador.
Java recibe solo 33 bytes. El estado inicial es `pending`, con
`sourceState=awaiting_transfer`. No se inicia un decoder ni un trabajo en segundo plano.

## API

| Método y ruta | Resultado |
|---|---|
| `POST /api/images?imageId=ID&name=archivo.png&sizeBytes=N&tileSize=256` | 202, metadata y `Location: /api/image/ID/status` |
| `GET /api/images` | Identificadores preparados y registrados; refresca manifests |
| `GET /api/image/ID/metadata` | Dimensiones, estado y niveles previstos/completados |
| `GET /api/image/ID/status` | Estado, tiles procesados/totales, nivel actual, origen y causa |

El POST lleva `Content-Type: application/octet-stream` y un cuerpo de exactamente
33 bytes (firma/IHDR/CRC). No acepta el PNG completo ni una ruta para abrir desde
el servidor. Nombre y tamaño son declarados por el cliente.

- 400: parámetros o cabecera inválidos.
- 409: identificador duplicado/reservado o directorio ya existente.
- 413: cuerpo mayor de 33 bytes.
- 500: fallo al persistir/refrescar registro.
- 404: consulta de identificador desconocido.

Identificadores: 1–64 caracteres ASCII, letras, números, `_` y `-`.
`demo_numeros` está reservado incluso cuando la demo está deshabilitada.

## Almacenamiento y estados

```text
<GTP_WORK>/<imageId>/meta.json
```

Manifest con `schemaVersion=1`, metadata, origen `browser_header`, nombre, tamaño
declarado y cabecera Base64. Se escribe mediante temporal y rename atómico en el
mismo directorio. No se reemplazan directorios/identificadores preexistentes al
registrar. No se escriben originales ni carpetas de tiles.

Los manifests se validan al arrancar y al refrescar catálogo/estado: dimensiones
y niveles se recalculan desde la cabecera, y no se acepta inventar tiles completos.
Un manifest corrupto detiene el arranque o hace fallar el refresco explícitamente.
El refresco no carga imágenes en RAM.

Metadata nueva:
- `state`: `pending` al registrar.
- `maxZoom`, `levels`: niveles **previstos**, calculados por `PyramidMath`.
- `totalTiles`: suma de todos los niveles; no es cantidad de archivos existentes.
- `completedLevels=[]` y `availableQualities=[]`: todavía no hay salida generada.

Estado nuevo:
- `processedTiles=0`, `currentLevel=null`, `sourceState=awaiting_transfer`.
- `message` explica qué falta; `error` queda vacío salvo un fallo real.
- El servicio dispone de persistencia de `failed` con causa para integración P3;
  no hay endpoint público que permita simular trabajo completado.
- `processing` y `ready` los incorpora P3 con `schemaVersion=2` y comprobante de
  publicación. Los manifests P2 (`schemaVersion=1`) siguen rechazando esos estados.

El catálogo plano anterior sigue en `catalog.json`, con `maxZoom=null` y estado
`ready`; sus tiles se validan al leerlos. Sus entradas se cargan al arrancar. Los
registros nuevos en `meta.json` sí se incorporan al catálogo mediante refresco.

## Identidad y coordenadas

`TileKey(imageId,z,x,y,q)` ya existe. Identidad multinivel:
`imageId:z:x:y:q`; `q` debe estar en 0..3. Coordenadas se comprueban contra las
filas/columnas del nivel, incluyendo bordes parciales. Validar una coordenada
prevista no demuestra que el tile esté disponible.

El constructor plano `TileKey(imageId,x,y)` mantiene `z=null`, `q=3` y la identidad
anterior `imageId:x:y`. GTP/1 rechaza z/q no nulos solo en catálogo plano; registros
multinivel `ready` admiten z/q. Un registro pending/failed no puede transferir tiles.
El cliente deshabilita **Actualizar vista** para esos registros.

## Cómo probar P2

1. Compilar y arrancar con los lanzadores habituales. Abrir `http://localhost:8081/`.
2. Elegir un PNG pequeño y esperar **Cabecera válida**.
3. Escribir `prueba_p2` y pulsar **Registrar imagen**.
4. Debe indicar `pending`; aparecerá `<work>/prueba_p2/meta.json`, sin tiles.
5. `prueba_p2` queda seleccionado automáticamente en **Tu imagen**: metadata muestra dimensiones y
   **Consultar estado** muestra cero tiles y espera del original. **Actualizar vista** está deshabilitado.
6. Repetir registro con el mismo identificador: debe rechazarlo sin cambiar el manifest.
7. Reiniciar servidor y refrescar página: el identificador debe seguir en catálogo.
8. Elegir `demo_numeros`: transferencia GTP y navegación de la demo siguen funcionando.

También consultar en el navegador `/api/image/prueba_p2/metadata` y
`/api/image/prueba_p2/status` para revisar JSON. No hace falta registrar un PNG
de 93 GB para probar persistencia: se guarda la misma cantidad pequeña de cabecera.

Verificación automática: 40 pruebas Java y E2E Chromium en Fedora. Incluye recarga
desde disco, duplicados/reservados, manifiestos corruptos, límites z/q/bordes,
rechazo HTTP de cuerpos grandes y retorno a la demo después de elegir un pending.

## Conexión con P3

Tras registrar en P2, la fuente todavía no está disponible en el servidor. El primer
incremento P3 conecta el registro mediante subida por bloques o lectura desde
`GTP_IMAGES`: posición validada, límites de buffers/disco y comprobación completa
del PNG admitido. Una cabecera/nombre/tamaño iguales no demuestran identidad de contenido.

Tras reiniciar, el usuario deberá volver a elegir el original si todavía está solo
en el navegador; P2 no conserva permisos de lectura del explorador. P3 mantiene los
bloques recibidos en work; requiere volver a seleccionar el mismo archivo para continuar
la subida. Procesa una fuente completa, valida su integridad y publica la pirámide
terminada como `ready`. Ver [ingesta_p3.md](../ingesta_p3.md). Calidad/transporte y
visor multinivel están implementados según [protocolo.md](../protocolo.md) y
[visor_p6.md](../visor_p6.md); el visor pide q3, no adaptación automática de calidad.
