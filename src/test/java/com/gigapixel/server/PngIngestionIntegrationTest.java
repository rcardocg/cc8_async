package com.gigapixel.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.gigapixel.server.image.PngFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PngIngestionIntegrationTest {
    @TempDir static Path root;
    @Autowired TestRestTemplate rest;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("images.directory", () -> root.resolve("work").toString());
        registry.add("images.originals-directory", () -> root.resolve("originales").toString());
    }

    @Test void boundedUploadChecksOffsetsAndPublishesReadyAsynchronously() throws Exception {
        byte[] png = PngFixtures.rgb(129, 65);
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        var registration = rest.postForEntity("/api/images?imageId=http_p3&name=original.png&sizeBytes=" + png.length + "&tileSize=64",
                new HttpEntity<>(Arrays.copyOf(png, 33), headers), JsonNode.class);
        assertEquals(202, registration.getStatusCode().value());
        String base = "/api/image/http_p3";
        assertEquals(0, rest.getForObject(base + "/upload", JsonNode.class).path("receivedBytes").asLong());
        assertEquals(413, rest.exchange(base + "/upload?offset=0", HttpMethod.PUT,
                new HttpEntity<>(new byte[1024 * 1024 + 1], headers), JsonNode.class).getStatusCode().value());
        assertEquals(200, rest.exchange(base + "/upload?offset=0", HttpMethod.PUT,
                new HttpEntity<>(Arrays.copyOf(png, 50), headers), JsonNode.class).getStatusCode().value());
        assertEquals(409, rest.exchange(base + "/upload?offset=0", HttpMethod.PUT,
                new HttpEntity<>(Arrays.copyOf(png, 50), headers), JsonNode.class).getStatusCode().value());
        assertEquals(400, rest.postForEntity(base + "/ingest", null, JsonNode.class).getStatusCode().value());
        assertEquals(200, rest.exchange(base + "/upload?offset=50", HttpMethod.PUT,
                new HttpEntity<>(Arrays.copyOfRange(png, 50, png.length), headers), JsonNode.class).getStatusCode().value());
        var started = rest.postForEntity(base + "/ingest", null, JsonNode.class);
        assertEquals(202, started.getStatusCode().value());
        assertEquals(base + "/status", started.getHeaders().getLocation().toString());
        JsonNode status; long deadline = System.nanoTime() + 10_000_000_000L;
        do { Thread.sleep(30); status = rest.getForObject(base + "/status", JsonNode.class); }
        while ("processing".equals(status.path("state").asText()) && System.nanoTime() < deadline);
        assertEquals("ready", status.path("state").asText(), status.toString());
        assertEquals(status.path("totalTiles").asLong(), status.path("processedTiles").asLong());
        var metadata = rest.getForObject(base + "/metadata", JsonNode.class);
        assertEquals(3, metadata.path("completedLevels").size());
        assertEquals(3, metadata.path("availableQualities").get(0).asInt());
        assertEquals(409, rest.postForEntity(base + "/ingest", null, JsonNode.class).getStatusCode().value());
        assertEquals(404, rest.getForEntity("/api/image/unknown/upload", JsonNode.class).getStatusCode().value());
    }
}
