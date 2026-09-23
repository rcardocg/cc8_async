# Bitácora de Desarrollo - Fases Iniciales
**Archivo:** `bitacora_fase1.md`
**Proyecto:** Servidor Asíncrono Java - Gestión de Imágenes Gigapíxel[cite: 1]
**Curso:** Ingeniería de Sistemas, Universidad Galileo
**Desarrolladores:** Cristian Sactic / Ricardo Caballeros[cite: 1]
**Fecha:** 23 de septiembre de 2026

---

## 1. Configuración Base del Proyecto
Se estableció la arquitectura base del servidor asíncrono utilizando Java y Spring Boot para la gestión de dependencias y el enrutamiento web.

*   **Creación del Entorno:** Se generó el archivo `pom.xml` en la raíz del proyecto para descargar las librerías `spring-boot-starter-web` y `spring-boot-starter-websocket`.
*   **Punto de Entrada:** Se creó la clase principal `GigapixelServerApplication.java` para levantar el contexto de Spring.
*   **Gestión de Puertos:** Debido a conflictos de puertos en el sistema, se creó el archivo `src/main/resources/application.properties` para reasignar el servidor al puerto `8081`.

## 2. Fase 1: Bootstrap HTTP/REST[cite: 1]
Se implementó la fase de inicialización mediante una API REST para la transferencia de la metadata de las imágenes[cite: 1].

*   **Modelo de Datos:** Se creó el archivo `ImageMetadata.java` dentro del paquete `model` utilizando un `Record` de Java. Este modelo estructura los datos iniciales (dimensiones, tamaño de tile, formato) permitiendo flexibilidad (ej. `maxZoom` nulo) mientras se define el esquema de indexación final (Z/X/Y vs. Matriz plana)[cite: 1].
*   **Controlador REST:** Se desarrolló `BootstrapController.java` en el paquete `controller`, exponiendo los siguientes endpoints:
    *   `GET /api/images`: Retorna un arreglo JSON con las imágenes disponibles.
    *   `GET /api/image/{id}/metadata`: Retorna la estructura JSON con las dimensiones de ultra alta resolución (65536x65536) y el `tileSize` de 256.

## 3. Fase 2: Protocolo de Solicitud de Tiles (WebSocket + TCP-Like)[cite: 1]
Se implementó el canal de comunicación bidireccional para reducir el *overhead* de HTTP y se integraron políticas de control de transmisión basadas en TCP[cite: 1].

*   **Configuración WebSocket:** Se creó `WebSocketConfig.java` para habilitar el endpoint `ws://localhost:8081/ws/tiles`.
*   **Gestión de Transmisión (TileWebSocketHandler.java):** Se desarrolló el manejador de mensajes integrando inteligencia en la transmisión de red:
    *   **Arranque Lento (Slow Start):** Se configuró una Ventana de Congestión (`cwnd`) inicial de 2. Ante peticiones masivas de tiles, el servidor restringe la salida inicial para no saturar el buffer del cliente.
    *   **Ventana Deslizante (Sliding Window):** Se implementó una cola de tiles pendientes (`pendingTilesQueue`) y un contador de tiles sin confirmar (`unackedTiles`). El servidor encola las peticiones y despacha estrictamente el volumen que la ventana permite.
    *   **AIMD y Acuses de Recibo (ACKs):** El servidor escucha los mensajes `ack_tile` del cliente. Si la latencia es estable, la ventana de transmisión crece gradualmente; si se detecta latencia alta (>200ms) o presión de memoria, la ventana se recorta drásticamente a la mitad.
*   **Cliente de Pruebas:** Se construyó un script HTML/JS (`test-websocket.html`) para simular la solicitud de ráfagas de tiles y la emisión automática de ACKs con latencia variable.

## 4. Resultados y Pruebas
Ambas fases fueron probadas exitosamente en el entorno local:
1.  Los endpoints REST responden con el formato JSON correcto bajo el estándar RFC 7231[cite: 1].
2.  Los logs del servidor y del cliente de prueba demostraron el funcionamiento exacto del algoritmo TCP: la transmisión inició enviando 2 tiles y la concurrencia creció de forma escalonada (3, 4, 5... hasta 10) conforme el cliente devolvía los acuses de recibo en tiempos óptimos (15ms - 50ms).