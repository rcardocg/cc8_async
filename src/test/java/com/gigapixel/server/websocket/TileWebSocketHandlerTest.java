package com.gigapixel.server.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gigapixel.server.model.TileKey;
import com.gigapixel.server.service.MetadataService;
import com.gigapixel.server.service.ImagesLayout;
import com.gigapixel.server.service.TileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TileWebSocketHandlerTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong clock = new AtomicLong();
    private MetadataService metadata;
    private TileService tiles;
    private TileWebSocketHandler handler;

    @BeforeEach
    void setup() throws Exception {
        metadata = new MetadataService(mapper, new ImagesLayout(directory.resolve("originales").toString(),
                directory.resolve("work").toString()), true);
        tiles = mock(TileService.class);
        when(tiles.readTile(any())).thenReturn(new byte[]{1, 2, 3});
        handler = new TileWebSocketHandler(mapper, metadata, tiles, Runnable::run, clock::get);
    }

    @AfterEach
    void close() { handler.shutdown(); }

    @Test
    void twoClientsHaveIndependentQueuesWindowsAndCloseLifecycle() throws Exception {
        Client first = client("first");
        fetch(first, "a", 8, false);
        assertEquals(2, first.events("tile_data").size());
        Client second = client("second");
        fetch(second, "b", 8, false);
        assertEquals(2, second.events("tile_data").size());
        ack(first, first.events("tile_data").getFirst());
        assertEquals(4, first.events("tile_data").size());
        assertEquals(2, second.events("tile_data").size());
        handler.afterConnectionClosed(first.session, CloseStatus.NORMAL);
        ack(second, second.events("tile_data").getFirst());
        assertEquals(4, second.events("tile_data").size());
        assertEquals(3, second.last("transfer_state").path("cwnd").intValue());
    }

    @Test
    void duplicateWrongAndStaleAcksNeverReleaseSlotsOrGrowWindow() throws Exception {
        Client client = client("ack");
        fetch(client, "a", 8, false);
        JsonNode first = client.events("tile_data").getFirst();
        ObjectNode forged = acknowledgement(first).put("tile_id", "wrong");
        send(client, forged);
        assertEquals(2, client.events("tile_data").size());
        ack(client, first);
        ack(client, first);
        assertEquals(4, client.events("tile_data").size());
        assertEquals(2, client.events("ack_ignored").size());
        fetch(client, "b", 8, true);
        int before = client.events("tile_data").size();
        ack(client, first);
        assertEquals(before, client.events("tile_data").size());
    }

    @Test
    void timeoutRetransmitsOnlyUnacknowledgedTileAndCanRecover() throws Exception {
        Client client = client("recovery");
        fetch(client, "a", 2, false);
        JsonNode first = client.events("tile_data").getFirst();
        JsonNode second = client.events("tile_data").getLast();
        ack(client, second);
        clock.set(1000);
        handler.checkTimeouts();
        JsonNode retry = client.events("tile_data").getLast();
        assertEquals(3, client.events("tile_data").size());
        assertEquals(first.path("transfer_id"), retry.path("transfer_id"));
        assertEquals(first.path("data"), retry.path("data"));
        assertEquals(2, retry.path("attempt").intValue());
        ack(client, retry);
        assertEquals(2, client.last("request_complete").path("acknowledged").intValue());
        assertEquals(0, client.last("request_complete").path("failed").intValue());
    }

    @Test
    void exhaustedRetriesTerminateInsteadOfHangingForever() throws Exception {
        Client client = client("exhausted");
        fetch(client, "a", 1, false);
        for (long time : new long[]{1000, 3000, 7000, 15000}) {
            clock.set(time);
            handler.checkTimeouts();
        }
        assertEquals(4, client.events("tile_data").size());
        assertEquals("ack_timeout", client.last("tile_error").path("code").asText());
        assertEquals(1, client.last("request_complete").path("failed").intValue());
        handler.checkTimeouts();
        assertEquals(1, client.events("request_complete").size());
    }

    @Test
    void validatesWholeRequestBeforeReplacingExistingWork() throws Exception {
        Client client = client("validation");
        fetch(client, "a", 8, false);
        ObjectNode bad = request("fetch_tiles").put("request_id", "b").put("imageId", MetadataService.DEMO_ID).put("replace", true);
        bad.putArray("tiles").addObject().put("x", 900).put("y", 0);
        send(client, bad);
        assertEquals("invalid_request", client.last("error").path("code").asText());
        ack(client, client.events("tile_data").getFirst());
        assertEquals("a", client.events("tile_data").getLast().path("request_id").asText());
        handler.handleTextMessage(client.session, new TextMessage("{"));
        send(client, request("ack_tile"));
        assertEquals(3, client.events("error").size());
    }

    @Test
    void rejectsUnknownImagesOversizedBatchesAndNonIntegralCoordinates() throws Exception {
        Client client = client("limits");
        ObjectNode request = request("fetch_tiles").put("request_id", "a").put("imageId", "unknown");
        send(client, request);
        assertEquals("unknown_image", client.last("error").path("code").asText());
        fetch(client, "b", 129, false);
        request.put("imageId", MetadataService.DEMO_ID);
        request.putArray("tiles").addObject().put("x", 0.5).put("y", 0);
        send(client, request);
        assertEquals(3, client.events("error").size());
        assertTrue(client.events("tile_data").isEmpty());
        verifyNoInteractions(tiles);
    }

    @Test
    void deduplicatesTilesAndRejectsBusyRequests() throws Exception {
        Client client = client("duplicate");
        ObjectNode request = request("fetch_tiles").put("request_id", "a").put("imageId", MetadataService.DEMO_ID);
        var array = request.putArray("tiles");
        array.addObject().put("x", 0).put("y", 0);
        array.addObject().put("x", 0).put("y", 0);
        send(client, request);
        assertEquals(1, client.events("tile_data").size());
        fetch(client, "b", 1, false);
        assertEquals("request_busy", client.last("error").path("code").asText());
        ack(client, client.events("tile_data").getFirst());
        assertEquals(1, client.last("request_complete").path("total").intValue());
    }

    @Test
    void congestionAvoidanceGrowsSlowerThanSlowStartAndHasACap() throws Exception {
        Client client = client("growth");
        fetch(client, "a", 100, false);
        for (int i = 0; i < 15; i++) ack(client, client.events("tile_data").get(i));
        double window = client.last("transfer_state").path("cwnd").doubleValue();
        assertEquals(16 + 1.0 / 16, window, 0.0001);
        for (int i = 15; i < 100; i++) ack(client, client.events("tile_data").get(i));
        assertTrue(client.last("transfer_state").path("cwnd").doubleValue() <= 32);
        assertEquals(100, client.last("request_complete").path("acknowledged").intValue());
    }

    @Test
    void receiverPressureLimitsNewSendsAndRecoveryRestoresReceiverWindow() throws Exception {
        Client client = client("memory");
        fetch(client, "a", 16, false);
        ack(client, client.events("tile_data").getFirst());
        send(client, request("memory_pressure").put("current_usage", 90).put("memory_limit", 100));
        assertEquals(2, client.last("adjust_strategy").path("receiver_window").intValue());
        int before = client.events("tile_data").size();
        ack(client, client.events("tile_data").get(1));
        assertEquals(before, client.events("tile_data").size());
        ack(client, client.events("tile_data").get(2));
        assertEquals(before + 1, client.events("tile_data").size());
        send(client, request("memory_pressure").put("current_usage", 20).put("memory_limit", 100));
        assertEquals(32, client.last("adjust_strategy").path("receiver_window").intValue());
        send(client, request("memory_pressure").put("current_usage", 20).put("memory_limit", 0));
        assertEquals("invalid_request", client.last("error").path("code").asText());
    }

    @Test
    void byteBudgetBoundsRetainedPayloadsEvenWithLargeWindow() throws Exception {
        when(tiles.readTile(any())).thenReturn(new byte[TileService.MAX_TILE_BYTES]);
        Client client = client("bytes");
        fetch(client, "a", 40, false);
        for (int i = 0; i < 20; i++) {
            ack(client, client.events("tile_data").get(i));
            assertTrue(client.events("tile_data").size() - (i + 1) <= 8);
        }
    }

    @Test
    void missingTileIsReportedAndOtherTilesContinue() throws Exception {
        when(tiles.readTile(new TileKey(MetadataService.DEMO_ID, 0, 0))).thenThrow(new IOException("missing"));
        Client client = client("missing");
        fetch(client, "a", 2, false);
        ack(client, client.events("tile_data").getFirst());
        assertEquals("tile_unavailable", client.last("tile_error").path("code").asText());
        assertEquals(1, client.last("request_complete").path("failed").intValue());
        assertEquals(1, client.last("request_complete").path("acknowledged").intValue());
    }

    @Test
    void cancellationReleasesOutstandingWorkAndLateAcksAreIgnored() throws Exception {
        Client client = client("cancel");
        fetch(client, "a", 8, false);
        JsonNode old = client.events("tile_data").getFirst();
        send(client, request("cancel_request").put("request_id", "a"));
        clock.set(20_000);
        handler.checkTimeouts();
        ack(client, old);
        assertEquals(2, client.events("tile_data").size());
        assertEquals(1, client.events("ack_ignored").size());
        fetch(client, "b", 1, false);
        assertEquals(3, client.events("tile_data").size());
    }

    @Test
    void blockedTileReadInOneClientDoesNotBlockAnotherClientOrScheduler() throws Exception {
        handler.shutdown();
        handler = new TileWebSocketHandler(mapper, metadata, tiles, Executors.newVirtualThreadPerTaskExecutor(), clock::get);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        when(tiles.readTile(new TileKey(MetadataService.DEMO_ID, 0, 0))).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new byte[]{1};
        });
        when(tiles.readTile(new TileKey(MetadataService.DEMO_ID, 1, 0))).thenAnswer(call -> {
            secondFinished.countDown();
            return new byte[]{2};
        });
        try {
            Client first = client("blocked");
            fetch(first, "a", 1, false);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            Client second = client("free");
            ObjectNode request = request("fetch_tiles").put("request_id", "b").put("imageId", MetadataService.DEMO_ID);
            request.putArray("tiles").addObject().put("x", 1).put("y", 0);
            send(second, request);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), handler::checkTimeouts);
            assertTrue(secondFinished.await(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    private ObjectNode request(String action) { return mapper.createObjectNode().put("version", 1).put("action", action); }

    private void fetch(Client client, String id, int count, boolean replace) throws Exception {
        ObjectNode request = request("fetch_tiles").put("request_id", id).put("imageId", MetadataService.DEMO_ID).put("replace", replace);
        var array = request.putArray("tiles");
        for (int i = 0; i < count; i++) array.addObject().put("x", i % 16).put("y", i / 16);
        send(client, request);
    }

    private ObjectNode acknowledgement(JsonNode tile) {
        return request("ack_tile").put("request_id", tile.path("request_id").asText())
                .put("transfer_id", tile.path("transfer_id").asText()).put("tile_id", tile.path("tile_id").asText());
    }

    private void ack(Client client, JsonNode tile) throws Exception { send(client, acknowledgement(tile)); }

    private void send(Client client, JsonNode json) throws Exception {
        handler.handleTextMessage(client.session, new TextMessage(mapper.writeValueAsString(json)));
    }

    private Client client(String id) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Client client = new Client(session);
        doAnswer(call -> {
            TextMessage message = call.getArgument(0);
            client.messages.add(mapper.readTree(message.getPayload()));
            return null;
        }).when(session).sendMessage(any());
        handler.afterConnectionEstablished(session);
        return client;
    }

    private static final class Client {
        final WebSocketSession session;
        final List<JsonNode> messages = java.util.Collections.synchronizedList(new ArrayList<>());
        Client(WebSocketSession session) { this.session = session; }
        List<JsonNode> events(String action) {
            synchronized (messages) {
                return messages.stream().filter(event -> action.equals(event.path("action").asText())).toList();
            }
        }
        JsonNode last(String action) { return events(action).getLast(); }
    }
}
