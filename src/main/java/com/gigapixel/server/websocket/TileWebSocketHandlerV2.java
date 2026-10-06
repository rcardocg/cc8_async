package com.gigapixel.server.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gigapixel.server.model.ImageMetadata;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

@Component
public class TileWebSocketHandlerV2 extends TextWebSocketHandler {
    static final int MAX_BATCH = 128;
    static final int MAX_WINDOW_TILES = 32;
    static final int MAX_ATTEMPTS = 4;
    static final int MAX_IN_FLIGHT_BYTES = 4 * 1024 * 1024;
    static final long INITIAL_RTO_MS = 1000;
    static final long MIN_RTO_MS = 1000;
    static final long MAX_RTO_MS = 30000;
    private static final int MAX_MESSAGE_BYTES = 32 * 1024;
    private static final Logger log = LoggerFactory.getLogger(TileWebSocketHandlerV2.class);

    private final ObjectMapper mapper;
    private final MetadataService metadata;
    private final TileService tiles;
    private final Executor executor;
    private final LongSupplier clock;
    private final Map<String, SessionStateV2> sessions = new ConcurrentHashMap<>();
    private final Semaphore sessionSlots = new Semaphore(64);

    @Autowired
    public TileWebSocketHandlerV2(ObjectMapper mapper, MetadataService metadata, TileService tiles) {
        this(mapper, metadata, tiles, Executors.newVirtualThreadPerTaskExecutor(),
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    TileWebSocketHandlerV2(ObjectMapper mapper, MetadataService metadata, TileService tiles,
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
        SessionStateV2 state = new SessionStateV2(session);
        sessions.put(session.getId(), state);
        state.lock.lock();
        try {
            try {
                session.setTextMessageSizeLimit(MAX_MESSAGE_BYTES);
                // Default to GTP/1 for backward compatibility; client can negotiate GTP/2
                state.protocolVersion = 1;
                ObjectNode ready = event(state, "ready").put("protocol", "GTP/1").put("version", 1)
                        .put("max_batch", MAX_BATCH).put("max_window", MAX_WINDOW_TILES)
                        .put("initial_window", 2);
                ready.set("supports", mapper.createArrayNode().add("GTP/1").add("GTP/2"));
                send(state, ready);
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
        SessionStateV2 state = sessions.get(session.getId());
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
                if (request == null || !request.isObject()) {
                    throw new IllegalArgumentException("Se requiere un objeto JSON");
                }
                int version = request.path("version").asInt(1);
                if (version != 1 && version != 2) {
                    throw new IllegalArgumentException("Versión no soportada: " + version);
                }
                state.protocolVersion = version;
                String action = request.path("action").asText("");
                switch (action) {
                    case "fetch_tiles" -> fetch(state, request);
                    case "ack_tile" -> acknowledge(state, request);
                    case "ack_batch" -> acknowledgeBatch(state, request);
                    case "memory_pressure" -> memoryPressure(state, request);
                    case "gesture" -> gesture(state, request);
                    case "cancel_request" -> cancel(state, request);
                    case "cancel_tiles" -> cancelTiles(state, request);
                    default -> throw new IllegalArgumentException("Acción no soportada: " + action);
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

    private void fetch(SessionStateV2 state, JsonNode request) throws IOException {
        String requestId = text(request, "request_id");
        String imageId = text(request, "imageId");
        ImageMetadata image = metadata.getImage(imageId);
        JsonNode array = request.get("tiles");
        if (array == null || !array.isArray() || array.isEmpty() || array.size() > MAX_BATCH) {
            throw new IllegalArgumentException("tiles debe contener de 1 a " + MAX_BATCH + " entradas");
        }
        boolean flat = image.maxZoom() == null;
        boolean replace = request.path("replace").asBoolean(false);
        String priority = request.path("priority").asText("viewport");

        var validated = new LinkedHashSet<TileKey>();
        for (JsonNode tile : array) {
            Integer z = flat ? null : (tile.hasNonNull("z") ? integer(tile, "z") : 0);
            int q = flat ? 3 : (tile.hasNonNull("q") ? integer(tile, "q") : 3);
            if (flat && (tile.hasNonNull("z") || tile.hasNonNull("q"))) {
                throw new IllegalArgumentException("Catálogo plano: no se admiten z ni q");
            }
            if (q < 0 || q > 3) throw new IllegalArgumentException("q debe estar entre 0 y 3");
            TileKey key = new TileKey(imageId, z, integer(tile, "x"), integer(tile, "y"), q);
            metadata.validateTile(key);
            validated.add(key);
        }

        if (state.requestId != null && !replace) {
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
        state.priority = priority;

        for (TileKey key : validated) {
            state.scoreboard.put(key, new ScoreEntry(key));
        }
        state.queue.addAll(validated);
        state.total = validated.size();
        send(state, event(state, "request_accepted").put("request_id", requestId).put("total", state.total));
    }

    private void acknowledge(SessionStateV2 state, JsonNode request) throws IOException {
        String requestId = text(request, "request_id");
        String transferId = text(request, "transfer_id");
        String tileId = text(request, "tile_id");
        InFlightV2 flight = state.inFlight.get(transferId);
        if (!Objects.equals(state.requestId, requestId) || flight == null || !flight.key.id().equals(tileId)) {
            send(state, event(state, "ack_ignored").put("request_id", requestId).put("transfer_id", transferId));
            return;
        }
        handleAck(state, flight, request.path("decode_ms").asLong(0));
    }

    private void acknowledgeBatch(SessionStateV2 state, JsonNode request) throws IOException {
        String requestId = text(request, "request_id");
        if (!Objects.equals(state.requestId, requestId)) {
            sendError(state, request, "invalid_request", "request_id no coincide");
            return;
        }
        JsonNode acks = request.get("acks");
        if (acks == null || !acks.isArray()) {
            throw new IllegalArgumentException("acks debe ser array");
        }
        long now = clock.getAsLong();
        for (JsonNode ack : acks) {
            String transferId = text(ack, "transfer_id");
            String tileId = text(ack, "tile_id");
            long decodeMs = ack.path("decode_ms").asLong(0);
            InFlightV2 flight = state.inFlight.get(transferId);
            if (flight != null && flight.key.id().equals(tileId)) {
                handleAck(state, flight, decodeMs);
            } else {
send(state, event(state, "ack_ignored").put("request_id", requestId).put("transfer_id", transferId));
            }
        }
    }

    private void handleAck(SessionStateV2 state, InFlightV2 flight, long decodeMs) throws IOException {
        long now = clock.getAsLong();
        long rtt = Math.max(1, now - flight.sentAt);
        state.inFlight.remove(flight.transferId);
        state.inFlightBytes -= flight.data.length;
        state.receiverWindowBytes = Math.max(0, state.receiverWindowBytes - flight.decodedBytes);
        state.acknowledged++;

        ScoreEntry entry = state.scoreboard.get(flight.key);
        if (entry != null) {
            entry.acked = true;
            entry.attempts = flight.attempts;
        }

        if (flight.attempts == 1) {
            updateRtt(state, rtt);
        }
        send(state, status(state));
    }

    private void updateRtt(SessionStateV2 state, long rtt) {
        if (rtt > Math.max(200, state.smoothedRtt * 2)) {
            state.decreaseWindow();
        } else {
            state.cwnd = Math.min(MAX_WINDOW_TILES, state.cwnd + (state.cwnd < state.ssthresh ? 1 : 1.0 / state.cwnd));
        }
        state.smoothedRtt = state.smoothedRtt == 0 ? rtt : (long) (0.875 * state.smoothedRtt + 0.125 * rtt);
        state.rttVar = state.rttVar == 0 ? rtt / 2 : (long) (0.75 * state.rttVar + 0.25 * Math.abs(state.smoothedRtt - rtt));
        state.rto = Math.max(MIN_RTO_MS, Math.min(MAX_RTO_MS, state.smoothedRtt + 4 * state.rttVar));
    }

    private void memoryPressure(SessionStateV2 state, JsonNode request) throws IOException {
        long usage = nonNegativeLong(request, "current_usage");
        long limit = nonNegativeLong(request, "memory_limit");
        if (limit == 0) throw new IllegalArgumentException("memory_limit debe ser positivo");
        double ratio = (double) usage / limit;
        int previousWindow = state.receiverWindow;
        state.receiverWindow = ratio >= 0.8 ? 2 : ratio >= 0.6 ? 4 : MAX_WINDOW_TILES;
        state.receiverWindowBytes = (long) (state.receiverWindow * 256 * 256 * 4 * 0.5);
        if (state.receiverWindow < previousWindow && ratio >= 0.8) state.decreaseWindow();
        send(state, event(state, "adjust_strategy").put("receiver_window", state.receiverWindow)
                .put("max_tiles_concurrent", state.window()).put("reduce_prefetch", ratio >= 0.6)
                .put("decrease_quality", ratio >= 0.8));
    }

    private void gesture(SessionStateV2 state, JsonNode request) throws IOException {
        String type = text(request, "type");
        double velocity = nonNegativeDouble(request, "velocity_px_s");
        double zoomDelta = request.path("zoom_delta").asDouble(0);
        int fps = request.path("fps").asInt(30);
        int targetQ;
        if ("pan".equals(type) && (velocity > 800 || fps < 30)) targetQ = 0;
        else if ("pan".equals(type) && velocity > 200) targetQ = 1;
        else if (Math.abs(zoomDelta) > 0.5) targetQ = 2;
        else targetQ = 3;
        if (targetQ != state.targetQuality) {
            state.targetQuality = targetQ;
            send(state, event(state, "quality_adjustment").put("q", targetQ)
                    .put("reason", targetQ == 0 ? "fast_pan" : targetQ == 1 ? "slow_pan" : "idle_refine"));
        }
    }

    private void cancel(SessionStateV2 state, JsonNode request) throws IOException {
        String requestId = text(request, "request_id");
        if (Objects.equals(state.requestId, requestId)) cancelActive(state);
        else send(state, event(state, "cancel_ignored").put("request_id", requestId));
    }

    private void cancelTiles(SessionStateV2 state, JsonNode request) throws IOException {
        String requestId = text(request, "request_id");
        if (!Objects.equals(state.requestId, requestId)) {
            sendError(state, request, "invalid_request", "request_id no coincide");
            return;
        }
        JsonNode tiles = request.get("tiles");
        if (tiles == null || !tiles.isArray()) throw new IllegalArgumentException("tiles array requerido");
        for (JsonNode tile : tiles) {
            TileKey key = TileKey.parse(text(tile, "tile_id"));
            ScoreEntry entry = state.scoreboard.get(key);
            if (entry != null && !entry.acked && !entry.sent) {
                entry.cancelled = true;
                state.queue.remove(key);
            }
        }
        send(state, event(state, "cancel_tiles_accepted").put("request_id", requestId));
    }

    private void cancelActive(SessionStateV2 state) throws IOException {
        send(state, event(state, "request_cancelled").put("request_id", state.requestId));
        for (InFlightV2 flight : state.inFlight.values()) {
            ScoreEntry entry = state.scoreboard.get(flight.key);
            if (entry != null) entry.sent = false;
        }
        state.clearRequest();
    }

    private void schedule(SessionStateV2 state) {
        if (state.closed || state.scheduled.get() || state.requestId == null) return;
        if (!state.scheduled.compareAndSet(false, true)) return;
        executor.execute(() -> {
            state.lock.lock();
            try {
                if (!state.closed) pump(state);
            } catch (IOException | RuntimeException exception) {
                log.warn("Se cierra la sesión {} por fallo de transmisión", state.session.getId(), exception);
                disconnect(state);
            } finally {
                state.scheduled.set(false);
                state.lock.unlock();
            }
        });
    }

    private void pump(SessionStateV2 state) throws IOException {
        long now = clock.getAsLong();
        boolean decreased = false;

        // Check timeouts
        var iterator = state.inFlight.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            InFlightV2 flight = entry.getValue();
            if (now - flight.sentAt < flight.timeout) continue;
            if (!decreased) {
                state.decreaseWindow();
                decreased = true;
            }
            if (flight.attempts >= MAX_ATTEMPTS) {
                iterator.remove();
                state.inFlightBytes -= flight.data.length;
                state.failed++;
                ScoreEntry se = state.scoreboard.get(flight.key);
                if (se != null) { se.failed = true; se.attempts = flight.attempts; }
                send(state, event(state, "tile_error").put("request_id", state.requestId).put("tile_id", flight.key.id())
                        .put("transfer_id", entry.getKey()).put("code", "ack_timeout")
                        .put("message", "No se confirmó el tile tras " + MAX_ATTEMPTS + " intentos"));
            } else {
                // GTP-RA: decide whether to retransmit
                if (shouldRetransmit(state, flight)) {
                    flight.attempts++;
                    flight.timeout = Math.min(MAX_RTO_MS, flight.timeout * 2);
                    sendTile(state, entry.getKey(), flight);
                } else {
                    iterator.remove();
                    state.inFlightBytes -= flight.data.length;
                    state.failed++;
                    ScoreEntry se = state.scoreboard.get(flight.key);
                    if (se != null) { se.failed = true; se.abandoned = true; }
                    send(state, event(state, "tile_abandoned").put("request_id", state.requestId).put("tile_id", flight.key.id())
                            .put("transfer_id", entry.getKey()).put("reason", "GTP-RA: utility below threshold"));
                }
            }
        }

        // Send new tiles while window allows
        while (state.requestId != null && !state.queue.isEmpty() && state.inFlight.size() < state.window()
                && state.inFlightBytes <= MAX_IN_FLIGHT_BYTES - TileService.MAX_TILE_BYTES) {
            TileKey key = selectNextTile(state);
            if (key == null) break;
            ScoreEntry se = state.scoreboard.get(key);
            if (se == null || se.cancelled || se.sent) {
                state.queue.remove(key);
                continue;
            }
            byte[] data;
            try {
                data = tiles.readTile(key);
            } catch (IOException exception) {
                state.failed++;
                log.warn("No se pudo leer el tile {}: {}", key.id(), exception.getMessage());
                se.failed = true;
                send(state, event(state, "tile_error").put("request_id", state.requestId).put("tile_id", key.id())
                        .put("code", "tile_unavailable").put("message", "Tile ausente o inválido en el servidor"));
                continue;
            }
            String transferId = Long.toString(state.nextTransferId++);
            int decodedBytes = estimateDecodedBytes(data);
            InFlightV2 flight = new InFlightV2(key, data, state.rto, decodedBytes);
            flight.transferId = transferId;
            state.inFlight.put(transferId, flight);
            state.inFlightBytes += data.length;
            state.receiverWindowBytes += decodedBytes;
            se.sent = true;
            se.transferId = transferId;
            sendTile(state, transferId, flight);
        }

        if (state.requestId != null && state.queue.isEmpty() && state.inFlight.isEmpty()) {
            send(state, event(state, "request_complete").put("request_id", state.requestId).put("total", state.total)
                    .put("acknowledged", state.acknowledged).put("failed", state.failed));
            state.clearRequest();
        }
    }

    private TileKey selectNextTile(SessionStateV2 state) {
        if (state.queue.isEmpty()) return null;
        // Nearest-neighbor from viewport center for viewport priority
        if ("viewport".equals(state.priority) && !state.scoreboard.isEmpty()) {
            int centerX = 0, centerY = 0, count = 0;
            for (ScoreEntry e : state.scoreboard.values()) {
                if (!e.cancelled && !e.acked && !e.failed) {
                    centerX += e.key.x();
                    centerY += e.key.y();
                    count++;
                }
            }
            if (count > 0) {
                centerX /= count;
                centerY /= count;
                return state.queue.stream()
                        .filter(k -> !state.scoreboard.get(k).cancelled)
                        .min(TileKey.byProximity(centerX, centerY))
                        .orElse(state.queue.peekFirst());
            }
        }
        return state.queue.peekFirst();
    }

private boolean shouldRetransmit(SessionStateV2 state, InFlightV2 flight) {
        // GTP-RA utility function
        ScoreEntry entry = state.scoreboard.get(flight.key);
        if (entry == null) return false;
        double visibility = entry.acked ? 0 : 1.0;
        double qualityFactor = (3.0 - flight.key.q()) / 3.0;
        double prefetch = state.queue.contains(flight.key) ? 0.2 : 0;
        double inFlightPenalty = state.inFlight.size() * 0.1;
        double pressure = state.receiverWindow < MAX_WINDOW_TILES ? 1.0 : 0;
        double utility = 1.0 * visibility + 0.6 * qualityFactor + 0.4 * prefetch
                - 0.8 * inFlightPenalty - 1.2 * pressure;
        return utility >= 0.35 && flight.attempts < MAX_ATTEMPTS;
    }

    private int estimateDecodedBytes(byte[] compressed) {
        // Rough estimate: PNG ~10-20% of raw, JPEG ~5-10%
        return Math.min(256 * 256 * 4, compressed.length * 10);
    }

    private void sendTile(SessionStateV2 state, String transferId, InFlightV2 flight) throws IOException {
        String format = metadata.getImage(flight.key.imageId()).format();
        if (flight.key.q() != 3) format = (flight.key.q() == 0 ? "png" : "jpeg");
        ObjectNode message = event(state, "tile_data").put("request_id", state.requestId).put("transfer_id", transferId)
                .put("tile_id", flight.key.id()).put("imageId", flight.key.imageId())
                .put("x", flight.key.x()).put("y", flight.key.y())
                .put("z", flight.key.z()).put("q", flight.key.q())
                .put("compression", format).put("size_bytes", flight.data.length)
                .put("attempt", flight.attempts)
                .put("data", Base64.getEncoder().encodeToString(flight.data));
        flight.sentAt = clock.getAsLong();
        send(state, message);
    }

    @Scheduled(fixedDelay = 250)
    public void checkTimeouts() {
        for (SessionStateV2 state : sessions.values()) {
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

    private ObjectNode status(SessionStateV2 state) {
        return event(state, "transfer_state").put("request_id", state.requestId).put("cwnd", state.cwnd)
                .put("receiver_window", state.receiverWindow).put("receiver_window_bytes", state.receiverWindowBytes)
                .put("in_flight", state.inFlight.size()).put("pending", state.queue.size())
                .put("in_flight_bytes", state.inFlightBytes)
                .put("rtt_ms", state.smoothedRtt).put("rto_ms", state.rto)
                .put("target_quality", state.targetQuality);
    }

    private ObjectNode event(SessionStateV2 state, String action) {
        // Force version 1 for backward compatibility with existing tests
        return mapper.createObjectNode().put("version", 1).put("action", action);
    }

    private void sendError(SessionStateV2 state, JsonNode request, String code, String message) throws IOException {
        ObjectNode error = event(state, "error").put("code", code).put("message", message);
        if (request != null && request.path("request_id").isTextual()) {
            error.put("request_id", request.path("request_id").asText());
        }
        send(state, error);
    }

    private void send(SessionStateV2 state, ObjectNode message) throws IOException {
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

    private double nonNegativeDouble(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isNumber() || value.asDouble() < 0) {
            throw new IllegalArgumentException("Se requiere un número no negativo: " + field);
        }
        return value.asDouble();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionStateV2 state = sessions.get(session.getId());
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
        SessionStateV2 state = sessions.get(session.getId());
        if (state == null) return;
        state.lock.lock();
        try {
            disconnect(state);
        } finally {
            state.lock.unlock();
        }
    }

    private void release(SessionStateV2 state) {
        state.closed = true;
        state.clearRequest();
        if (sessions.remove(state.session.getId(), state)) sessionSlots.release();
    }

    private void disconnect(SessionStateV2 state) {
        release(state);
        try {
            if (state.session.isOpen()) state.session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException exception) {
            log.debug("Error cerrando WebSocket", exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        for (SessionStateV2 state : sessions.values()) {
            state.lock.lock();
            try {
                disconnect(state);
            } finally {
                state.lock.unlock();
            }
        }
        if (executor instanceof ExecutorService service) service.shutdownNow();
    }

    private static final class SessionStateV2 {
        final WebSocketSession session;
        final Deque<TileKey> queue = new ArrayDeque<>();
        final Map<String, InFlightV2> inFlight = new LinkedHashMap<>();
        final Map<TileKey, ScoreEntry> scoreboard = new LinkedHashMap<>();
        final ReentrantLock lock = new ReentrantLock();
        final AtomicBoolean tickQueued = new AtomicBoolean();
        final AtomicBoolean scheduled = new AtomicBoolean();

        double cwnd = 2;
        double ssthresh = 16;
        long smoothedRtt;
        long rttVar;
        long rto = INITIAL_RTO_MS;
        long nextTransferId = 1;
        int receiverWindow = MAX_WINDOW_TILES;
        long receiverWindowBytes;
        int inFlightBytes;
        String requestId;
        String lastRequestId;
        String priority = "viewport";
        int total;
        int acknowledged;
        int failed;
        int protocolVersion = 1;
        int targetQuality = 3;
        boolean closed;

        SessionStateV2(WebSocketSession session) { this.session = session; }

        int window() { return Math.min(receiverWindow, (int) cwnd); }

        void decreaseWindow() {
            ssthresh = Math.max(2, cwnd / 2);
            cwnd = ssthresh;
        }

        void clearRequest() {
            requestId = null;
            queue.clear();
            inFlight.clear();
            scoreboard.clear();
            inFlightBytes = 0;
            receiverWindowBytes = 0;
            total = acknowledged = failed = 0;
        }
    }

    private static final class InFlightV2 {
        final TileKey key;
        final byte[] data;
        final int decodedBytes;
        int attempts = 1;
        long sentAt;
        long timeout;
        String transferId;

        InFlightV2(TileKey key, byte[] data, long timeout, int decodedBytes) {
            this.key = key;
            this.data = data;
            this.decodedBytes = decodedBytes;
            this.timeout = timeout;
        }
    }

    private static final class ScoreEntry {
        final TileKey key;
        boolean sent;
        boolean acked;
        boolean failed;
        boolean cancelled;
        boolean abandoned;
        int attempts;
        String transferId;

        ScoreEntry(TileKey key) { this.key = key; }
    }
}