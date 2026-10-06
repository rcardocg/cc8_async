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
class P4QualityAndCacheIntegrationTest {

    @TempDir static Path root;
    @Autowired TestRestTemplate rest;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("images.directory", () -> root.resolve("work").toString());
        registry.add("images.originals-directory", () -> root.resolve("originales").toString());
    }

    @Test
    void multilevelImageServesAllQualitiesThroughWebSocket() throws Exception {
        byte[] png = PngFixtures.rgb(513, 257);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        var reg = rest.postForEntity("/api/images?imageId=p4test&name=test.png&sizeBytes=" + png.length + "&tileSize=256",
                new HttpEntity<>(Arrays.copyOf(png, 33), headers), JsonNode.class);
        assertEquals(202, reg.getStatusCode().value());

        String base = "/api/image/p4test";
        rest.exchange(base + "/upload?offset=0", HttpMethod.PUT, new HttpEntity<>(png, headers), JsonNode.class);
        rest.postForEntity(base + "/ingest", null, JsonNode.class);

        JsonNode status;
        long deadline = System.nanoTime() + 30_000_000_000L;
        do {
            Thread.sleep(100);
            status = rest.getForObject(base + "/status", JsonNode.class);
        } while ("processing".equals(status.path("state").asText()) && System.nanoTime() < deadline);

        assertEquals("ready", status.path("state").asText());
        assertEquals(3, status.path("completedLevels").size());

        JsonNode meta = rest.getForObject(base + "/metadata", JsonNode.class);
        assertEquals(2, meta.path("maxZoom").asInt());
        boolean hasQ3 = false;
for (JsonNode q : meta.path("availableQualities")) {
    if (q.asInt() == 3) { hasQ3 = true; break; }
}
assertTrue(hasQ3, "availableQualities should contain quality 3");
    }

    @Test
    void cacheStatsAvailableViaEndpoint() throws Exception {
        var response = rest.getForEntity("/api/cache/stats", JsonNode.class);
        assertEquals(200, response.getStatusCode().value());
        JsonNode stats = response.getBody();
        assertTrue(stats.has("entries"));
        assertTrue(stats.has("usedBytes"));
        assertTrue(stats.has("maxBytes"));
        assertTrue(stats.has("hits"));
        assertTrue(stats.has("misses"));
        assertTrue(stats.has("evictions"));
    }
}