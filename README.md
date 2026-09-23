# Propuesta de Protocolos
## Servidor de Imágenes Ultra Alta Resolución

**Estudiantes:** Ricardo Caballeros / Cristian Sactic
**Proyecto:** Servidor Asíncrono Java - Gestión de Imágenes Gigapíxel  
**Estado:** Propuesta en revisión

---

## Descripción General

El proyecto implementará un servidor Java que sirve imágenes de ultra alta resolución mediante un **sistema de tiles** (como Google Maps). La solución utiliza múltiples protocolos coordinados para gestionar caché, predicción de movimiento y sincronización entre cliente-servidor.

### Nota sobre Indexación de Tiles

**⚠️ Sujeto a cambios:** El esquema de indexación de tiles está en evaluación. Se está considerando entre:
- **Z/X/Y** (Google Maps estándar) - Propuesta inicial
- **Quadkey** (Microsoft Bing Maps) - Alternativa más compacta
- **X/Y sin zoom** - Resolución fija, matriz plana

La decisión final dependerá de la aprobación del catedrático y compatibilidad con esquemas ya en uso por otros estudiantes (VAS360, Flare, VATP360, VAD360, TWRD).

---

## Protocolos a Implementar

### 1. Bootstrap HTTP/REST

**Propósito:** Transferencia inicial de metadata de la imagen

**Métodos:**
- `GET /api/images` - Listar imágenes disponibles
- `GET /api/image/{id}/metadata` - Obtener metadata de imagen específica

**Respuesta del Servidor:**
```json
{
  "imageId": "string",
  "width": "int",
  "height": "int",
  "tileSize": 256,
  "totalTiles": "int",
  "maxZoom": "int | null (si sin zoom)",
  "format": "jpeg"
}
```

**RFC:** 7231 (HTTP/1.1 Semantics)  
**Justificación:** Estándar establecido, soporte nativo en navegadores y Java, manejo seguro de conexiones

---

### 2. Protocolo de Solicitud de Tiles sobre WebSocket

**Propósito:** Solicitud y transferencia eficiente de tiles individuales

**Tipo:** JSON bidireccional sobre WebSocket

**Solicitud del Cliente:**
```json
{
  "action": "fetch_tiles",
  "imageId": "string",
  "tiles": [
    {"x": int, "y": int, "z": int | null},
    {"x": int, "y": int, "z": int | null}
  ],
  "priority": "high|normal|low",
  "metrics": {
    "bandwidth_mbps": float,
    "latency_ms": int,
    "memory_available_mb": int
  }
}
```

**Respuesta del Servidor:**
```json
{
  "tile_id": "string",
  "x": int,
  "y": int,
  "z": "int | null",
  "data": "base64_encoded_image",
  "compression": "jpeg|webp|png",
  "quality": int,
  "size_bytes": int
}
```

**RFC:** 6455 (WebSocket Protocol)  
**Justificación:** Reduce overhead respecto a múltiples conexiones HTTP, comunicación bidireccional, ideal para transferencias continuas

---

### 3. Protocolo de Control de Caché del Servidor (SCCP)

**Propósito:** Coordinar gestión de memoria en servidor entre tiles cargados

**Tipo:** JSON sobre WebSocket

**Mensaje - Estado del Caché:**
```json
{
  "action": "cache_state",
  "cacheMemoryUsed_mb": int,
  "cacheCapacity_mb": int,
  "tilesInRam": int,
  "cacheHitRate": float,
  "cacheMissRate": float,
  "hotTiles": [
    {"x": int, "y": int, "z": "int | null", "accessCount": int}
  ]
}
```

**Algoritmo:** LRU + LFU (Least Recently Used + Least Frequently Used)

**Fórmula de Evicción:**
```
score(tile) = (lastAccessTime - now()) * 0.6 + (accessCount) * -0.4
Si score < threshold → EVICT
```

**Justificación:** Optimizar uso de RAM del servidor, mantener tiles más solicitados en memoria, mejorar hit rate

---

### 4. Protocolo de Gestos y Cambio de Calidad (GACP)

**Propósito:** Detectar tipo de interacción del usuario y ajustar calidad en tiempo real según el tipo de navegación

**Tipo:** JSON sobre WebSocket

**Solicitud del Cliente:**
```json
{
  "action": "gesture",
  "type": "pan|zoom|idle",
  "velocity": float,
  "current_fps": int
}
```

**Respuesta del Servidor:**
```json
{
  "quality_adjustment": "high|medium|low",
  "tile_size_recommended": int,
  "compression_level": int
}
```

**Algoritmo:** Detección de patrones de movimiento + adaptive quality based on FPS

**Lógica de Decisión:**
```
if type == "pan" and velocity > threshold_high:
  quality_adjustment = "low"        // Pan rápido: baja calidad
  compression_level = 60
else if type == "pan" and velocity <= threshold_low:
  quality_adjustment = "high"       // Pan lento: alta calidad
  compression_level = 85
else if type == "idle":
  quality_adjustment = "high"       // Parado: máxima calidad
  compression_level = 90
else if type == "zoom":
  quality_adjustment = "medium"     // Zoom: calidad media
  compression_level = 75
```

**Justificación:** Mantener experiencia fluida incluso con conexiones lentas, reducir latencia durante navegación activa, mejorar percepción de responsividad

---

### 5. Protocolo de Notificación de Presión de Memoria (MPNP)

**Propósito:** Comunicar al servidor cuando el navegador está bajo presión de memoria y ajustar estrategia de envío

**Tipo:** JSON sobre WebSocket

**Solicitud del Cliente:**
```json
{
  "action": "memory_pressure",
  "current_usage": long,
  "available": long,
  "tiles_loaded": int,
  "memory_limit": long
}
```

**Respuesta del Servidor:**
```json
{
  "action": "adjust_strategy",
  "reduce_prefetch": boolean,
  "decrease_quality": boolean,
  "max_tiles_concurrent": int
}
```

**Algoritmo:** Threshold-based memory monitoring con ajuste dinámico

**Lógica de Decisión:**
```
memory_usage_percent = (current_usage / memory_limit) * 100

if memory_usage_percent > 80:
  reduce_prefetch = true
  decrease_quality = true
  max_tiles_concurrent = 2
else if memory_usage_percent > 60:
  reduce_prefetch = true
  decrease_quality = false
  max_tiles_concurrent = 4
else:
  reduce_prefetch = false
  decrease_quality = false
  max_tiles_concurrent = 8
```

**Justificación:** Evitar crash del navegador, sincronizar limitaciones de memoria entre cliente-servidor, ajuste proactivo de estrategia de transferencia según presión de recursos disponibles

---

### 6. Protocolo de Compresión Adaptativa

**Propósito:** Ajustar tamaño de tiles según recursos disponibles del cliente

**Tipo:** Headers HTTP + Payload JSON

**Variables de Decisión:**
- Ancho de banda (Mbps)
- Latencia (ms)
- Memoria disponible (MB)
- FPS actual (frames por segundo)

**Decisiones Automáticas:**
```
if bandwidth < 2 Mbps:
  compression = JPEG, quality = 60
else if bandwidth < 5 Mbps:
  compression = JPEG, quality = 75
else if bandwidth < 10 Mbps:
  compression = WebP, quality = 85
else:
  compression = WebP, quality = 90
```

**Codecs Soportados:**
- JPEG (fast, good compression) - 60-90%
- WebP (better compression) - 75-95%
- PNG (lossless) - máxima calidad

**Justificación:** Optimizar experiencia en conexiones lentas o dispositivos limitados, mantener responsividad

---

## Arquitectura de Comunicación

### Fase 1: Inicialización (HTTP REST)
```
Cliente → GET /api/image/{id}/metadata
Servidor → JSON: dimensiones, tile_size, maxZoom (si aplica)
```

### Fase 2: Conexión WebSocket
```
Cliente → Establece conexión WebSocket
Servidor → Listo para recibir solicitudes
```

### Fase 3: Actualización de Viewport
```
Cliente → viewport_update {pixelX, pixelY, movimiento, velocidad}
Servidor → Predice próximos tiles, inicia prefetch en background
```

### Fase 4: Transferencia de Tiles
```
Cliente → fetch_tiles [lista de tiles a cargar]
Servidor → Envía tiles comprimidos con prioridad
```

### Fase 5: Monitoreo Continuo
```
Cliente ↔ Servidor → Intercambio continuo de:
  - Métricas (bandwidth, memory, fps)
  - Estados de caché
  - Predicciones
  - Directivas de compresión
```

---

## Justificación Técnica

### Protocolos Base

- **HTTP/REST:** RFC 7231, soporte nativo en navegadores y Java, ideal para bootstrap
- **WebSocket:** RFC 6455, bidireccional, overhead bajo, perfecto para streaming de tiles

### Protocolos Personalizados

- **SCCP:** Control de caché del servidor, optimización de RAM mediante LRU+LFU (referencia: Belady 1966)
- **VPCP:** Predicción inteligente de movimiento mediante extrapolación lineal y análisis de patrones
- **MPNP:** Gestión proactiva de presión de memoria entre cliente-servidor
- **Compresión Adaptativa:** Decisiones basadas en métricas reales del cliente

### Algoritmos

- **LRU + LFU:** Estándar de cache replacement policies (Belady, L. A., 1966)
- **Linear Regression:** Time series forecasting para predicción de movimiento
- **Working Set:** Memory management (Denning, 1968)
- **Quad-Tree Indexing:** Spatial data structures para localización eficiente

---

## Referencias

### RFC Standards
- RFC 7230/7231: HTTP/1.1 Syntax and Semantics
- RFC 6455: WebSocket Protocol
- RFC 7233: HTTP Range Requests

### Academic References
- Belady, L. A. (1966). "A Study of Replacement Algorithms for Virtual Storage"
- Denning, P. J. (1968). "The Working Set Model for Program Behavior"
- Time Series Forecasting: Linear Regression and Prediction Models

### Estándares de Tiling
- Google Maps Tile System (Sujeto a cambios)
- Quadkey Indexing (Under evaluation)
- OGC Tile Map Service Standard

---

## Notas Adicionales

### Diferencias según Esquema de Indexación

**Con Zoom (Z/X/Y o Quadkey):**
- Múltiples niveles de resolución
- Pirámide de tiles
- Conversiones más complejas
- Mejor para exploración detallada

**Sin Zoom (X/Y):**
- Resolución fija única
- Matriz plana de tiles
- Implementación más simple
- Mejor para imágenes con nivel de detalle uniforme

### Estado de la Propuesta

✅ **Aprobado:** 
- Protocolo 1: Bootstrap HTTP/REST
- Protocolo 2: Protocolo de Solicitud de Tiles sobre WebSocket
- Protocolo 3: Protocolo de Control de Caché del Servidor (SCCP)
- Protocolo 4: Protocolo de Gestos y Cambio de Calidad (GACP) ✓
- Protocolo 5: Protocolo de Notificación de Presión de Memoria (MPNP) ✓
- Protocolo 6: Protocolo de Compresión Adaptativa

⚠️ **Sujeto a cambios:** Esquema de indexación de tiles (Z/X/Y vs Quadkey vs Sin Zoom)  
❓ **Pendiente validación:** Compatibilidad con esquemas VAS360, Flare, VATP360, VAD360, TWRD

---

## Preguntas a Chaclan

1. ¿Aprueba el uso de estos protocolos?
2. ¿Cuál esquema de indexación de tiles recomienda: Z/X/Y, Quadkey o Sin Zoom?
3. ¿Hay restricciones con respecto a VAS360, Flare, VATP360, VAD360 o TWRD?
4. ¿Hay algún protocolo que otro estudiante esté usando que deba evitar?

---

**FIN PROPUESTA**
