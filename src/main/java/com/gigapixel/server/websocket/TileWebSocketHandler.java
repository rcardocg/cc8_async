package com.gigapixel.server.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gigapixel.server.model.TileKey;
import com.gigapixel.server.service.MetadataService;
import com.gigapixel.server.service.TileService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

@Component
public class TileWebSocketHandler extends TextWebSocketHandler {
    static final int MAX_BATCH = 128;
    static final int MAX_WINDOW = 32;
    static final int MAX_ATTEMPTS = 4;
    static final int MAX_IN_FLIGHT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_MESSAGE_BYTES = 32 * 1024;
    private static final Logger log = LoggerFactory.getLogger(TileWebSocketHandler.class);

    private final ObjectMapper mapper;
    private final MetadataService metadata;
    private final TileService tiles;
    private final Executor executor;
    private final LongSupplier clock;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final Semaphore sessionSlots = new Semaphore(64);

    @Autowired
    public TileWebSocketHandler(ObjectMapper mapper, MetadataService metadata, TileService tiles) {
        this(mapper, metadata, tiles, Executors.newVirtualThreadPerTaskExecutor(),
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    // Reloj y ejecutor sustituibles para probar timeouts sin esperas reales.
    TileWebSocketHandler(ObjectMapper mapper, MetadataService metadata, TileService tiles,
                         Executor executor, LongSupplier clock) {
        this.mapper = mapper;
        this.metadata = metadata;
        this.tiles = tiles;
        this.executor = executor;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (!sessionSlots.tryAcquire()) {
            session.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        SessionState state = new SessionState(session);
        sessions.put(session.getId(), state);
        state.lock.lock();
        try {
            try {
                session.setTextMessageSizeLimit(MAX_MESSAGE_BYTES);
                send(state, event("ready").put("protocol", "GTP/1").put("max_batch", MAX_BATCH)
                        .put("max_window", MAX_WINDOW).put("initial_window", 2));
            } catch (IOException | RuntimeException exception) {
                disconnect(state);
                throw exception;
            }
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        SessionState state = sessions.get(session.getId());
        if (state == null) return;
        state.lock.lock();
        try {
            if (state.closed) return;
            JsonNode request = null;
            try {
                if (message.getPayloadLength() > MAX_MESSAGE_BYTES) {
                    throw new IllegalArgumentException("Mensaje demasiado grande");
                }
                request = mapper.readTree(message.getPayload());
                if (request == null || !request.isObject() || integer(request, "version") != 1) {
                    throw new IllegalArgumentException("Se requiere version: 1 y un objeto JSON");
                }
                switch (text(request, "action")) {
                    case "fetch_tiles" -> fetch(state, request);
                    case "ack_tile" -> acknowledge(state, request);
                    case "memory_pressure" -> memoryPressure(state, request);
                    case "cancel_request" -> cancel(state, request);
                    default -> throw new IllegalArgumentException("Acción no soportada");
                }
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                sendError(state, request, "invalid_request", "JSON, campos o coordenadas inválidos: " + exception.getMessage());
            } catch (ResponseStatusException exception) {
                sendError(state, request, "unknown_image", "Imagen desconocida");
            }
            schedule(state);
        } finally {
            state.lock.unlock();
        }
    }

    private void fetch(SessionState state, JsonNode request) throws IOException {
        String requestId = text(request, "request_id");
        String imageId = text(request, "imageId");
        metadata.getImage(imageId);
        JsonNode array = request.get("tiles");
        if (array == null || !array.isArray() || array.isEmpty() || array.size() > MAX_BATCH) {
            throw new IllegalArgumentException("tiles debe contener de 1 a " + MAX_BATCH + " entradas");
        }
        if (request.has("replace") && !request.get("replace").isBoolean()) {
            throw new IllegalArgumentException("replace debe ser booleano");
        }
        var validated = new LinkedHashSet<TileKey>();
        for (JsonNode tile : array) {
            if (tile.hasNonNull("z")) throw new IllegalArgumentException("Esta versión admite sólo tiles X/Y, z debe ser null");
            TileKey key = new TileKey(imageId, integer(tile, "x"), integer(tile, "y"));
            metadata.validateTile(key);
            validated.add(key);
        }
        if (state.requestId != null && !request.path("replace").asBoolean(false)) {
            sendError(state, request, "request_busy", "Hay una solicitud activa; use replace: true para sustituirla");
            return;
        }
        if (requestId.equals(state.lastRequestId)) {
            throw new IllegalArgumentException("Use un request_id nuevo para cada solicitud");
        }
        if (state.requestId != null) cancelActive(state);
        state.clearRequest();
        state.requestId = requestId;
        state.lastRequestId = requestId;
        state.queue.addAll(validated);
        state.total = validated.size();
        send(state, event("request_accepted").put("request_id", requestId).put("total", state.total));
    }

    private void acknowledge(SessionState state, JsonNode request) throws IOException {
        String requestId = text(request, "request_id");
        String transferId = text(request, "transfer_id");
        String tileId = text(request, "tile_id");
        InFlight flight = state.inFlight.get(transferId);
        if (!Objects.equals(state.requestId, requestId) || flight == null || !flight.key.id().equals(tileId)) {
            send(state, event("ack_ignored").put("request_id", requestId).put("transfer_id", transferId));
            return;
        }
        state.inFlight.remove(transferId);
        state.inFlightBytes -= flight.data.length;
        state.acknowledged++;
        // Karn: un ACK después de retransmitir no es una muestra inequívoca de RTT.
        if (flight.attempts == 1) {
            long rtt = Math.max(1, clock.getAsLong() - flight.sentAt);
            if (rtt > Math.max(200, state.smoothedRtt * 2)) {
                state.decreaseWindow();
            } else {
                state.cwnd = Math.min(MAX_WINDOW, state.cwnd + (state.cwnd < state.ssthresh ? 1 : 1 / state.cwnd));
            }
            state.smoothedRtt = state.smoothedRtt == 0 ? rtt : 0.875 * state.smoothedRtt + 0.125 * rtt;
            state.rto = Math.max(1000, Math.min(10_000, (long) (state.smoothedRtt * 3)));
        }
        send(state, status(state));
    }

    private void memoryPressure(SessionState state, JsonNode request) throws IOException {
        long usage = nonNegativeLong(request, "current_usage");
        long limit = nonNegativeLong(request, "memory_limit");
        if (limit == 0) throw new IllegalArgumentException("memory_limit debe ser positivo");
        double ratio = (double) usage / limit;
        int previousWindow = state.receiverWindow;
        state.receiverWindow = ratio >= 0.8 ? 2 : ratio >= 0.6 ? 4 : MAX_WINDOW;
        if (state.receiverWindow < previousWindow && ratio >= 0.8) state.decreaseWindow();
        send(state, event("adjust_strategy").put("receiver_window", state.receiverWindow)
                .put("max_tiles_concurrent", state.window()).put("reduce_prefetch", ratio >= 0.6)
                .put("decrease_quality", false));
    }

    private void cancel(SessionState state, JsonNode request) throws IOException {
        String requestId = text(request, "request_id");
        if (Objects.equals(state.requestId, requestId)) cancelActive(state);
        else send(state, event("cancel_ignored").put("request_id", requestId));
    }

    private void cancelActive(SessionState state) throws IOException {
        send(state, event("request_cancelled").put("request_id", state.requestId));
        state.clearRequest();
    }

    // Un solo trabajador por sesión. Lectura/compresión/envío no bloquean el planificador global.
    private void schedule(SessionState state) {
        if (state.closed || state.scheduled || state.requestId == null) return;
        state.scheduled = true;
        executor.execute(() -> {
            state.lock.lock();
            try {
                if (!state.closed) pump(state);
            } catch (IOException | RuntimeException exception) {
                log.warn("Se cierra la sesión {} por fallo de transmisión", state.session.getId(), exception);
                disconnect(state);
            } finally {
                state.scheduled = false;
                state.lock.unlock();
            }
        });
    }

    private void pump(SessionState state) throws IOException {
        long now = clock.getAsLong();
        boolean decreased = false;
        var iterator = state.inFlight.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            InFlight flight = entry.getValue();
            if (now - flight.sentAt < flight.timeout) continue;
            if (!decreased) {
                state.decreaseWindow();
                decreased = true;
            }
            if (flight.attempts >= MAX_ATTEMPTS) {
                iterator.remove();
                state.inFlightBytes -= flight.data.length;
                state.failed++;
                send(state, event("tile_error").put("request_id", state.requestId).put("tile_id", flight.key.id())
                        .put("transfer_id", entry.getKey()).put("code", "ack_timeout")
                        .put("message", "No se confirmó el tile tras cuatro intentos"));
            } else {
                flight.attempts++;
                flight.timeout = Math.min(10_000, flight.timeout * 2);
                sendTile(state, entry.getKey(), flight);
            }
        }
        while (state.requestId != null && !state.queue.isEmpty() && state.inFlight.size() < state.window()
                && state.inFlightBytes <= MAX_IN_FLIGHT_BYTES - TileService.MAX_TILE_BYTES) {
            TileKey key = state.queue.removeFirst();
            byte[] data;
            try {
                data = tiles.readTile(key);
            } catch (IOException exception) {
                state.failed++;
                log.warn("No se pudo leer el tile {}: {}", key.id(), exception.getMessage());
                send(state, event("tile_error").put("request_id", state.requestId).put("tile_id", key.id())
                        .put("code", "tile_unavailable").put("message", "Tile ausente o inválido en el servidor"));
                continue;
            }
            String transferId = Long.toString(state.nextTransferId++);
            InFlight flight = new InFlight(key, data, state.rto);
            state.inFlight.put(transferId, flight);
            state.inFlightBytes += data.length;
            sendTile(state, transferId, flight);
        }
        if (state.requestId != null && state.queue.isEmpty() && state.inFlight.isEmpty()) {
            send(state, event("request_complete").put("request_id", state.requestId).put("total", state.total)
                    .put("acknowledged", state.acknowledged).put("failed", state.failed));
            state.clearRequest();
        }
    }

    private void sendTile(SessionState state, String transferId, InFlight flight) throws IOException {
        String format = metadata.getImage(flight.key.imageId()).format();
        ObjectNode message = event("tile_data").put("request_id", state.requestId).put("transfer_id", transferId)
                .put("tile_id", flight.key.id()).put("imageId", flight.key.imageId())
                .put("x", flight.key.x()).put("y", flight.key.y()).putNull("z")
                .put("compression", format).put("size_bytes", flight.data.length).put("attempt", flight.attempts)
                .put("data", Base64.getEncoder().encodeToString(flight.data));
        flight.sentAt = clock.getAsLong();
        send(state, message);
    }

    @Scheduled(fixedDelay = 250)
    public void checkTimeouts() {
        for (SessionState state : sessions.values()) {
            // No se espera el lock de otro cliente en el hilo del planificador.
            if (!state.tickQueued.compareAndSet(false, true)) continue;
            executor.execute(() -> {
                state.lock.lock();
                try {
                    schedule(state);
                } finally {
                    state.tickQueued.set(false);
                    state.lock.unlock();
                }
            });
        }
    }

    private ObjectNode status(SessionState state) {
        return event("transfer_state").put("request_id", state.requestId).put("cwnd", state.cwnd)
                .put("receiver_window", state.receiverWindow).put("in_flight", state.inFlight.size())
                .put("pending", state.queue.size()).put("in_flight_bytes", state.inFlightBytes)
                .put("rtt_ms", state.smoothedRtt).put("rto_ms", state.rto);
    }

    private ObjectNode event(String action) {
        return mapper.createObjectNode().put("version", 1).put("action", action);
    }

    private void sendError(SessionState state, JsonNode request, String code, String message) throws IOException {
        ObjectNode error = event("error").put("code", code).put("message", message);
        if (request != null && request.path("request_id").isTextual()) {
            error.put("request_id", request.path("request_id").asText());
        }
        send(state, error);
    }

    private void send(SessionState state, ObjectNode message) throws IOException {
        state.session.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
    }

    private String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > 128) {
            throw new IllegalArgumentException("Campo requerido: " + field);
        }
        return value.asText();
    }

    private int integer(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("Se requiere un entero: " + field);
        }
        return value.intValue();
    }

    private long nonNegativeLong(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException("Se requiere un entero no negativo: " + field);
        }
        return value.longValue();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionState state = sessions.get(session.getId());
        if (state == null) return;
        state.lock.lock();
        try {
            release(state);
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        SessionState state = sessions.get(session.getId());
        if (state == null) return;
        state.lock.lock();
        try {
            disconnect(state);
        } finally {
            state.lock.unlock();
        }
    }

    private void release(SessionState state) {
        state.closed = true;
        state.clearRequest();
        if (sessions.remove(state.session.getId(), state)) sessionSlots.release();
    }

    private void disconnect(SessionState state) {
        release(state);
        try {
            if (state.session.isOpen()) state.session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException exception) {
            log.debug("Error cerrando WebSocket", exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        for (SessionState state : sessions.values()) {
            state.lock.lock();
            try {
                disconnect(state);
            } finally {
                state.lock.unlock();
            }
        }
        if (executor instanceof ExecutorService service) service.shutdownNow();
    }

    private static final class SessionState {
        final WebSocketSession session;
        final ArrayDeque<TileKey> queue = new ArrayDeque<>();
        final Map<String, InFlight> inFlight = new LinkedHashMap<>();
        // ReentrantLock evita fijar el carrier de un hilo virtual durante I/O en Java 21.
        final ReentrantLock lock = new ReentrantLock();
        final AtomicBoolean tickQueued = new AtomicBoolean();
        double cwnd = 2;
        double ssthresh = 16;
        double smoothedRtt;
        long rto = 1000;
        long nextTransferId = 1;
        int receiverWindow = MAX_WINDOW;
        int inFlightBytes;
        String requestId;
        String lastRequestId;
        int total;
        int acknowledged;
        int failed;
        boolean scheduled;
        boolean closed;

        SessionState(WebSocketSession session) { this.session = session; }

        int window() { return Math.min(receiverWindow, (int) cwnd); }

        void decreaseWindow() {
            ssthresh = Math.max(2, cwnd / 2);
            cwnd = ssthresh;
        }

        void clearRequest() {
            requestId = null;
            queue.clear();
            inFlight.clear();
            inFlightBytes = 0;
            total = acknowledged = failed = 0;
        }
    }

    private static final class InFlight {
        final TileKey key;
        final byte[] data;
        int attempts = 1;
        long sentAt;
        long timeout;

        InFlight(TileKey key, byte[] data, long timeout) {
            this.key = key;
            this.data = data;
            this.timeout = timeout;
        }
    }
}
