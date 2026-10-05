# P2 — Registro de originales y metadata multinivel

## Qué se puede hacer

Desde el cliente, elegir un PNG, inspeccionarlo, escribir un identificador y pulsar
**Registrar imagen · P2**. Se vuelve a validar su cabecera en el servidor y se guarda
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
- `processing` y `ready` para registros nuevos se incorporarán al decoder P3.
  P2 rechaza manifests que pretendan declarar esos estados sin procesamiento.

El catálogo plano anterior sigue en `catalog.json`, con `maxZoom=null` y estado
`ready`; sus tiles se validan al leerlos. Sus entradas se cargan al arrancar. Los
registros nuevos en `meta.json` sí se incorporan al catálogo mediante refresco.

## Identidad y coordenadas

`TileKey(imageId,z,x,y,q)` ya existe. Identidad multinivel:
`imageId:z:x:y:q`; `q` debe estar en 0..3. Coordenadas se comprueban contra las
filas/columnas del nivel, incluyendo bordes parciales. Validar una coordenada
prevista no demuestra que el tile esté disponible.

El constructor plano `TileKey(imageId,x,y)` mantiene `z=null`, `q=3` y la identidad
anterior `imageId:x:y`. GTP/1 v1 sigue rechazando z explícito; el cambio de transporte
se hará en P5. Un registro pending/failed no puede transferir tiles. El cliente
deshabilita **Cargar región** para esos registros.

## Cómo probar P2

1. Compilar y arrancar con los lanzadores habituales. Abrir `http://localhost:8081/`.
2. Elegir un PNG pequeño y esperar **Cabecera válida**.
3. Escribir `prueba_p2` y pulsar **Registrar imagen · P2**.
4. Debe indicar `pending`; aparecerá `<work>/prueba_p2/meta.json`, sin tiles.
5. Elegir `prueba_p2` en el selector inferior: metadata muestra dimensiones y
   **Consultar estado** muestra cero tiles y espera del original. Cargar está deshabilitado.
6. Repetir registro con el mismo identificador: debe rechazarlo sin cambiar el manifest.
7. Reiniciar servidor y refrescar página: el identificador debe seguir en catálogo.
8. Elegir `demo_numeros`: transferencia GTP y navegación de la demo siguen funcionando.

También consultar en el navegador `/api/image/prueba_p2/metadata` y
`/api/image/prueba_p2/status` para revisar JSON. No hace falta registrar un PNG
de 93 GB para probar persistencia: se guarda la misma cantidad pequeña de cabecera.

Verificación automática: 40 pruebas Java y E2E Chromium en Fedora. Incluye recarga
desde disco, duplicados/reservados, manifiestos corruptos, límites z/q/bordes,
rechazo HTTP de cuerpos grandes y retorno a la demo después de elegir un pending.

## Punto de inicio para P3

La fuente original no está disponible en el servidor. P3 debe conectar el registro
con lectura/transferencia por bloques o streaming: archivo por identificador,
posición validada, límites de buffers y disco y comprobación completa de integridad.
Una cabecera/nombre/tamaño iguales no demuestran que sea el mismo contenido.

Tras reiniciar, el usuario deberá volver a elegir el original si todavía está solo
en el navegador; P2 no conserva permisos de lectura del explorador. Cuando exista
una fuente completa validada, P3 iniciará `processing`, publicará niveles completos
y finalmente `ready`. Después se integran calidad/transporte/visor multinivel.
