package com.gigapixel.server.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gigapixel.server.image.PyramidMath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class GtpCliTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private record Result(int code, String out, String err) { }

    @Test
    void realPngUsesSignatureAndReportsPartialEdgesWithoutDecoding() throws Exception {
        Path image = directory.resolve("imagen sin extension");
        ImageIO.write(new BufferedImage(300, 280, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        Result result = run("inspect", image.toString(), "--pretty");
        assertEquals(0, result.code());
        JsonNode json = mapper.readTree(result.out());
        assertEquals(300, json.path("width").asInt());
        assertEquals(280, json.path("height").asInt());
        assertEquals(1, json.at("/pyramid/maxZoom").asInt());
        assertEquals(5, json.at("/pyramid/totalTiles").asLong());
        assertEquals(900 + 1, json.path("packedScanlineBytes").asLong());
        assertEquals(300L * 280 * 4, json.path("fullDecodeRgbaBytes").asLong());
        assertEquals("", result.err());
    }

    @Test
    void rejectsInvalidCrcTruncatedHeaderAndDisguisedZip() throws Exception {
        Path image = header("crc.png", 256, 256, 8, 2, 0);
        byte[] bytes = Files.readAllBytes(image);
        bytes[20] ^= 1; Files.write(image, bytes);
        assertEquals(1, run("inspect", image.toString()).code());
        Files.write(image, new byte[]{(byte) 137, 80, 78, 71});
        assertEquals(1, run("inspect", image.toString()).code());
        Files.write(image, "PK".repeat(17).getBytes(StandardCharsets.UTF_8));
        Result result = run("inspect", image.toString());
        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertTrue(mapper.readTree(result.err()).path("error").asText().contains("se requiere PNG"));
    }

    @Test
    void pngColorDepthPairsAndInterlaceAreValidated() throws Exception {
        for (int type : new int[]{0, 2, 3, 4, 6}) {
            int[] depths = type == 0 ? new int[]{1, 2, 4, 8, 16}
                    : type == 3 ? new int[]{1, 2, 4, 8} : new int[]{8, 16};
            for (int depth : depths) {
                Path image = header("valid.png", 17, 9, depth, type, 0);
                assertEquals(0, run("inspect", image.toString()).code(), "type=" + type + ", depth=" + depth);
            }
        }
        assertEquals(1, run("inspect", header("bad.png", 10, 10, 16, 3, 0).toString()).code());
        assertEquals(1, run("inspect", header("bad.png", 10, 10, 8, 2, 2).toString()).code());
        assertEquals(1, run("inspect", header("bad.png", 0, 10, 8, 2, 0).toString()).code());
    }

    @Test
    void ladderReportsErrorsAndSortsBySizeWithoutRecursing() throws Exception {
        Path small = header("small.png", 256, 256, 8, 2, 0);
        Path large = header("large.png", 300, 280, 8, 2, 0);
        Files.write(large, new byte[20], java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(directory.resolve("not-png.txt"), "invalid");
        Path nested = Files.createDirectory(directory.resolve("tiles"));
        Files.writeString(nested.resolve("not-listed"), "x");
        Result result = run("ladder", directory.toString());
        assertEquals(1, result.code());
        JsonNode json = mapper.readTree(result.out());
        assertEquals(3, json.path("files").size());
        assertEquals(1, json.path("failures").asLong());
        assertEquals(small.toString(), json.at("/files/1/path").asText());
        assertEquals(large.toString(), json.at("/files/2/path").asText());
    }

    @Test
    void dryRunDoesNotCreateWorkOrChangeSource() throws Exception {
        Path image = header("small.png", 32, 32, 8, 2, 0);
        byte[] before = Files.readAllBytes(image);
        Path work = directory.resolve("work con espacios/new");
        Result result = run("ingest", image.toString(), "--dry-run", "--work", work.toString(), "--image-id", "small");
        assertEquals(0, result.code(), result.err());
        JsonNode json = mapper.readTree(result.out());
        assertEquals("preflight_ok", json.path("status").asText());
        assertTrue(json.path("ingestionImplemented").asBoolean());
        assertFalse(Files.exists(work.getParent()));
        assertArrayEquals(before, Files.readAllBytes(image));
        assertTrue(json.path("notes").toString().contains("Solo cabecera"));
    }

    @Test
    void diskMemoryAndInterlaceHaveDistinctVerdicts() throws Exception {
        String work = directory.resolve("work").toString();
        Path giant = header("giant.png", 1_000_000_000, 1_000_000_000, 8, 2, 0);
        Result disk = run("ingest", giant.toString(), "--dry-run", "--work", work);
        assertEquals(2, disk.code());
        assertEquals("insufficient_disk", mapper.readTree(disk.out()).path("status").asText());
        Path small = header("small.png", 256, 256, 8, 2, 0);
        Result memory = run("ingest", small.toString(), "--dry-run", "--work", work, "--max-memory-mib", "1");
        assertEquals(1, memory.code());
        assertEquals("memory_review", mapper.readTree(memory.out()).path("status").asText());
        Path adam7 = header("adam7.png", 256, 256, 8, 2, 1);
        Result interlace = run("ingest", adam7.toString(), "--dry-run", "--work", work);
        assertEquals(1, interlace.code());
        assertEquals("interlace_review", mapper.readTree(interlace.out()).path("status").asText());
    }

    @Test
    void rejectsRealIngestWithoutIdAndOriginalInsideDiscardableWork() throws Exception {
        Path image = header("small.png", 32, 32, 8, 2, 0);
        assertEquals(1, run("ingest", image.toString()).code());
        Result inside = run("ingest", image.toString(), "--dry-run", "--work", directory.toString());
        assertEquals(1, inside.code());
        assertTrue(inside.err().contains("original no puede estar dentro"));
        assertEquals(1, run("inspect", image.toString(), "--work", directory.toString()).code());
        assertEquals(1, run("inspect", image.toString(), "--tile-size").code());
        assertEquals(1, run("inspect", image.toString(), "--tile-size", "0").code());
        assertEquals(1, run("inspect", image.toString(), "--unknown").code());
    }

    @Test
    void pyramidMathHandlesSmallImagesOddDimensionsAndOverflow() {
        assertEquals(0, PyramidMath.calculate(1, 1, 256).maxZoom());
        assertEquals(0, PyramidMath.calculate(256, 256, 256).maxZoom());
        assertEquals(8, PyramidMath.calculate(40000, 30131, 256).maxZoom());
        assertEquals(9, PyramidMath.calculate(108200, 81500, 256).maxZoom());
        var pyramid = PyramidMath.calculate(513, 257, 256);
        assertEquals(List.of(129L, 257L, 513L), pyramid.levels().stream().map(PyramidMath.Level::width).toList());
        assertEquals(9, pyramid.totalTiles());
        assertTrue(PyramidMath.calculate(20_000_000, 20_000_000, 256).totalTiles() > Integer.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> PyramidMath.calculate(Long.MAX_VALUE, Long.MAX_VALUE, 256));
    }

    private Result run(String... args) {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int code = new GtpCli().run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    // Cabeceras sintéticas: inspect no afirma validar ni decodificar IDAT/IEND.
    private Path header(String name, int width, int height, int depth, int type, int interlace) throws Exception {
        var data = new ByteArrayOutputStream();
        try (var stream = new DataOutputStream(data)) {
            stream.writeLong(0x89504e470d0a1a0aL);
            stream.writeInt(13); stream.writeInt(0x49484452);
            stream.writeInt(width); stream.writeInt(height);
            stream.writeByte(depth); stream.writeByte(type); stream.writeByte(0); stream.writeByte(0); stream.writeByte(interlace);
        }
        byte[] header = data.toByteArray();
        var crc = new CRC32(); crc.update(header, 12, 17);
        byte[] complete = ByteBuffer.allocate(33).put(header).putInt((int) crc.getValue()).array();
        return Files.write(directory.resolve(name), complete);
    }
}
