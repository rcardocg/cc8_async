package com.gigapixel.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerIntegrationTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("images.directory", () -> System.getProperty("java.io.tmpdir") + "/cc8-test-" + UUID.randomUUID());
        registry.add("images.demo-enabled", () -> true);
    }

    @Test
    void bootstrapAndAllFrontendAssetsAreServedLocally() {
        assertArrayEquals(new String[]{"demo_numeros"}, rest.getForObject("/api/images", String[].class));
        assertEquals(404, rest.getForEntity("/api/image/not-found/metadata", String.class).getStatusCode().value());
        for (String path : new String[]{"/", "/viewer.js", "/viewer.css", "/api/image/demo_numeros/metadata"}) {
            assertEquals(200, rest.getForEntity(path, String.class).getStatusCode().value(), path);
        }
    }

    @Test
    void realWebSocketDeliversDecodablePngAndRecoversAnUnacknowledgedTile() throws Exception {
        Inbox inbox = new Inbox();
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket socket = client.newWebSocketBuilder()
                    .header("Origin", "http://localhost:" + port)
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/tiles"), inbox).get(5, TimeUnit.SECONDS);
            try {
                assertEquals("ready", inbox.next().path("action").asText());
                socket.sendText("""
                        {"version":1,"action":"fetch_tiles","request_id":"network","imageId":"demo_numeros","tiles":[{"x":0,"y":0}]}
                        """, true).join();
                assertEquals("request_accepted", inbox.next().path("action").asText());
                JsonNode tile = inbox.next();
                assertEquals("tile_data", tile.path("action").asText());
                byte[] bytes = Base64.getDecoder().decode(tile.path("data").asText());
                assertEquals(bytes.length, tile.path("size_bytes").intValue());
                assertEquals(256, ImageIO.read(new ByteArrayInputStream(bytes)).getWidth());
                // Primer ACK omitido: el temporizador real debe retransmitir la misma transferencia.
                JsonNode retry = inbox.next();
                assertEquals(tile.path("transfer_id"), retry.path("transfer_id"));
                assertEquals(2, retry.path("attempt").intValue());
                socket.sendText(mapper.createObjectNode().put("version", 1).put("action", "ack_tile")
                        .put("request_id", "network").put("transfer_id", retry.path("transfer_id").asText())
                        .put("tile_id", retry.path("tile_id").asText()).toString(), true).join();
                assertEquals("transfer_state", inbox.next().path("action").asText());
                JsonNode completed = inbox.next();
                assertEquals("request_complete", completed.path("action").asText());
                assertEquals(1, completed.path("acknowledged").intValue());
                assertEquals(0, completed.path("failed").intValue());
            } finally {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            }
        }
    }

    private final class Inbox implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            socket.request(1);
            return null;
        }

        JsonNode next() throws Exception {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "Se esperaba un mensaje WebSocket");
            return mapper.readTree(message);
        }
    }
}
