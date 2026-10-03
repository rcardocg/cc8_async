# Catálogo local y almacenamiento de tiles

## Formato implementado

`images.directory` apunta al directorio de datos (por defecto `./data/images`,
relativo al directorio desde donde se inicia Java). No se incluye en Git.

```text
data/images/
  catalog.json
  ejemplo/
    0_0.png
    1_0.png
    0_1.png
    1_1.png
```

`catalog.json`:

```json
[
  {
    "imageId": "ejemplo",
    "width": 300,
    "height": 280,
    "tileSize": 256,
    "totalTiles": 4,
    "maxZoom": null,
    "format": "png"
  }
]
```

Los cuatro tiles de este ejemplo tienen dimensiones 256×256, 44×256, 256×24 y
44×24 respectivamente. Deben ser recortes reales del original sin relleno en los
bordes. Para JPEG se usa `format: "jpeg"` y extensión `.jpeg`.

## Validación

- Identificador de 1–64 caracteres ASCII: letras, números, `_` y `-`.
- `demo_numeros` es reservado; los identificadores no pueden repetirse.
- Dimensiones positivas representables como `int`; tamaño de tile de 64–512 px.
- `totalTiles = ceil(width/tileSize) * ceil(height/tileSize)`, calculado con `long`.
- `maxZoom` debe ser `null`: todavía no hay pirámide ni conversión Z/X/Y.
- PNG/JPEG; máximo 512 KiB comprimidos por tile; catálogo máximo 1 MiB.
- Cabecera de imagen, formato y dimensiones verificadas antes de transferir.
- Rutas normalizadas y resueltas para impedir salir del directorio mediante enlaces.

El catálogo se carga una vez al iniciar. Un catálogo inválido detiene el arranque
con una causa explícita. Un archivo de tile ausente o inválido genera `tile_error`
en su solicitud; el resto de tiles puede continuar. No se descarga ningún dato
desde los enlaces externos incluidos en los PDFs durante la ejecución.

## Alcance y siguiente paso

Este formato permite probar transferencia real sin cargar un original gigante en
RAM, pero **requiere tiles preparados previamente**. No hay importador de TIFF,
BigTIFF, JPEG2000 ni de las imágenes del catedrático. Elegir y verificar ese pipeline
es un trabajo pendiente: no debe sustituirse por `ImageIO.read(originalGigante)`.
La validación de cabecera de un tile acota dimensiones; no prueba por sí sola que
todo el archivo sea decodificable. El cliente confirma sólo después de decodificar.

La demo integrada es 4096×4096 con tiles 256×256: genera PNG con coordenadas y números
legibles al vuelo, sirve para verificar el protocolo y está marcada como sintética.
