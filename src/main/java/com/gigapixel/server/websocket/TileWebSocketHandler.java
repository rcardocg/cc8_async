package com.gigapixel.server.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedList;
import java.util.Queue;

public class TileWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();
    
    // --- Variables de Estado TCP-Like (Por sesión) ---
    // En una app real, esto estaría en una clase de estado por sesión (SessionState)
    private int cwnd = 2; // Congestion Window (Iniciamos lento: 2 tiles a la vez)
    private int ssthresh = 16; // Slow Start Threshold
    private int unackedTiles = 0; // Tiles enviados pero no confirmados
    private final Queue<JsonNode> pendingTilesQueue = new LinkedList<>(); // Cola de tiles pendientes

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        System.out.println("------->>> [WS] Conexión establecida. ID: " + session.getId());
        // Reiniciamos estado al conectar
        cwnd = 2;
        unackedTiles = 0;
        pendingTilesQueue.clear();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        JsonNode jsonNode = mapper.readTree(payload);
        
        String action = jsonNode.has("action") ? jsonNode.get("action").asText() : "";

        switch (action) {
            case "fetch_tiles":
                handleFetchTiles(session, jsonNode);
                break;
            case "ack_tile":
                handleAck(session, jsonNode);
                break;
            case "memory_pressure":
                handleCongestion(session);
                break;
            default:
                System.out.println("------->>> [WS] Acción desconocida: " + action);
        }
    }

    /**
     * Procesa la solicitud inicial de un bloque grande de tiles.
     */
    private void handleFetchTiles(WebSocketSession session, JsonNode request) throws Exception {
        System.out.println("------->>> [WS] Solicitud de tiles recibida.");
        
        JsonNode tilesArray = request.get("tiles");
        if (tilesArray != null && tilesArray.isArray()) {
            // Encolamos todos los tiles solicitados
            for (JsonNode tileInfo : tilesArray) {
                pendingTilesQueue.offer(tileInfo);
            }
            // Intentamos enviar basados en nuestra ventana actual
            transmitAllowedTiles(session);
        }
    }

    /**
     * Procesa los "Acuse de Recibo" (ACK).
     * Implementa la lógica de incremento de ventana (AIMD / Slow Start).
     */
    private void handleAck(WebSocketSession session, JsonNode ack) throws Exception {
        String tileId = ack.get("tile_id").asText();
        int latencyMs = ack.has("latency_ms") ? ack.get("latency_ms").asInt() : 0;
        
        unackedTiles = Math.max(0, unackedTiles - 1); // Liberamos un espacio
        System.out.println("------->>> [ACK] Tile " + tileId + " confirmado. Latencia: " + latencyMs + "ms");

        // Lógica AIMD / Slow Start
        if (latencyMs > 200) {
            // Latencia alta: asumimos congestión
            handleCongestion(session);
        } else {
            // Latencia baja: incrementamos ventana
            if (cwnd < ssthresh) {
                // FASE: Slow Start (Crecimiento exponencial simulado sumando por cada ACK)
                cwnd += 1; 
            } else {
                // FASE: Congestion Avoidance (Crecimiento lineal más lento)
                // (Para simplificar, crecemos más lento o mantenemos)
                // cwnd += 1 / cwnd (Lógica teórica TCP, aquí lo limitamos)
                if (cwnd < 32) cwnd++; 
            }
        }
        
        System.out.println("------->>> [ESTADO] Ventana actual (cwnd): " + cwnd + " | Pendientes (unacked): " + unackedTiles);
        
        // Al liberar espacio, intentamos enviar más tiles de la cola
        transmitAllowedTiles(session);
    }

    /**
     * Simula la reducción de ventana ante congestión (Multiplicative Decrease).
     */
    private void handleCongestion(WebSocketSession session) {
        ssthresh = Math.max(2, cwnd / 2); // Cortamos el umbral a la mitad
        cwnd = ssthresh; // Reducimos la ventana actual
        System.out.println("------->>> [CONGESTIÓN] Reduciendo ventana. Nuevo cwnd/ssthresh: " + cwnd);
    }

    /**
     * Envía tiles desde la cola respetando el límite del Congestion Window.
     */
    private void transmitAllowedTiles(WebSocketSession session) throws Exception {
        while (!pendingTilesQueue.isEmpty() && unackedTiles < cwnd) {
            JsonNode nextTile = pendingTilesQueue.poll();
            
            // Simular armado del tile (aquí usarías JPIP o leerías el archivo real)
            int x = nextTile.get("x").asInt();
            int y = nextTile.get("y").asInt();
            String tileId = x + "_" + y;
            
            String responseStr = String.format("""
                {
                  "action": "tile_data",
                  "tile_id": "%s",
                  "data": "base64_aqui...",
                  "status": "sent"
                }
                """, tileId);

            session.sendMessage(new TextMessage(responseStr));
            unackedTiles++;
            System.out.println("------->>> [SEND] Enviado tile " + tileId + ". (Unacked: " + unackedTiles + "/" + cwnd + ")");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        System.out.println("------->>> [WS] Conexión cerrada. ID: " + session.getId());
    }
}