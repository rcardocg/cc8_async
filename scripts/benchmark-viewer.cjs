// P7: reproducible application-level experiments, on isolated data and server.
"use strict";
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const { once } = require("node:events");
const fs = require("node:fs/promises");
const { tmpdir } = require("node:os");
const path = require("node:path");
const { deflateSync } = require("node:zlib");
const { chromium } = require("playwright");

function options(args) {
    const result = { jar: ".build/server.jar", work: null, image: null, output: null, repetitions: 1, steps: 10 };
    if (args[0] && !args[0].startsWith("--")) result.jar = args.shift();
    while (args.length) {
        const key = args.shift();
        if (key === "--help") return null;
        if (!["--work", "--image", "--output", "--repetitions", "--steps"].includes(key) || !args.length) throw new Error(`Argumento inválido: ${key}`);
        result[key.slice(2)] = args.shift();
    }
    for (const key of ["repetitions", "steps"]) {
        result[key] = Number(result[key]);
        if (!Number.isInteger(result[key]) || result[key] < 1 || result[key] > 100) throw new Error(`${key}: entero de 1 a 100 requerido`);
    }
    if (!!result.work !== !!result.image) throw new Error("Use --work y --image juntos para un dataset preparado");
    if (result.image && !/^[a-zA-Z0-9_-]{1,64}$/.test(result.image)) throw new Error("image: identificador inválido");
    return result;
}

function chunk(type, data) {
    const contents = Buffer.concat([Buffer.from(type), data]);
    let crc = 0xffffffff;
    for (const byte of contents) {
        crc ^= byte;
        for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
    const checksum = Buffer.alloc(4); checksum.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
    return Buffer.concat([length, contents, checksum]);
}

function fixture() {
    const width = 3073, height = 1537, stride = width * 3 + 1;
    const pixels = Buffer.alloc(stride * height);
    let seed = 12345;
    for (let y = 0; y < height; y++) for (let x = 0; x < width * 3; x++) {
        seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
        pixels[y * stride + 1 + x] = seed & 255;
    }
    const header = Buffer.alloc(13); header.writeUInt32BE(width); header.writeUInt32BE(height, 4);
    header[8] = 8; header[9] = 2;
    return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", header),
        chunk("IDAT", deflateSync(pixels)), chunk("IEND", Buffer.alloc(0))]);
}

async function json(base, route, init) {
    const response = await fetch(base + route, init);
    if (!response.ok) throw new Error(`${route}: HTTP ${response.status} ${await response.text()}`);
    return response.status === 204 ? null : response.json();
}

async function prepare(base) {
    const png = fixture(), id = "p7_synthetic";
    const headers = { "Content-Type": "application/octet-stream" };
    await json(base, `/api/images?imageId=${id}&name=fixture.png&sizeBytes=${png.length}&tileSize=256`, { method: "POST", headers, body: png.subarray(0, 33) });
    for (let offset = 0; offset < png.length; offset += 1048576) {
        await json(base, `/api/image/${id}/upload?offset=${offset}`, { method: "PUT", headers, body: png.subarray(offset, offset + 1048576) });
    }
    const started = performance.now();
    await json(base, `/api/image/${id}/ingest`, { method: "POST" });
    let status;
    do {
        await new Promise(resolve => setTimeout(resolve, 100));
        status = await json(base, `/api/image/${id}/status`);
        if (performance.now() - started > 120000) throw new Error("Timeout de ingesta sintética");
    } while (status.state === "processing");
    assert.equal(status.state, "ready", status.error);
    return { id, kind: "synthetic", sourceBytes: png.length, ingestionMs: performance.now() - started };
}

async function startServer(jar, work, originals) {
    const child = spawn("java", ["-Xmx256m", "-jar", path.resolve(jar), "--server.port=0", "--images.demo-enabled=false",
        `--images.directory=${work}`, `--images.originals-directory=${originals}`], { stdio: ["ignore", "pipe", "pipe"] });
    let output = "";
    try {
        const port = await new Promise((resolve, reject) => {
            const timeout = setTimeout(() => reject(new Error(`Inicio de Java agotado: ${output}`)), 30000);
            const capture = buffer => {
                output = (output + buffer).slice(-16000);
                const match = output.match(/Tomcat started on port (\d+)/);
                if (match) { clearTimeout(timeout); resolve(Number(match[1])); }
            };
            child.stdout.on("data", capture); child.stderr.on("data", capture);
            child.once("error", error => { clearTimeout(timeout); reject(error); });
            child.once("exit", code => { clearTimeout(timeout); reject(new Error(`Java terminó (${code}): ${output}`)); });
        });
        return { child, base: `http://localhost:${port}` };
    } catch (error) { child.kill(); throw error; }
}

async function settled(page) {
    await page.waitForFunction(() => protocolReady && metadata?.state === "ready" && requestId === null &&
        pyramid.cache.has(pyramid.backupId) && pyramid.visible.size > 0 && [...pyramid.visible].every(id => pyramid.cache.has(id)),
    null, { timeout: 120000 });
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
}

async function experiment(browser, base, dataset, windowBytes, scenario, steps) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 });
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    let started = false;
    const wire = { incomingJsonBytes: 0, outgoingJsonBytes: 0, payloadBytes: 0, fragments: 0, tiles: 0,
        retransmittedPayloadBytes: 0, stalePayloadBytes: 0, cancelMessages: 0, cancelledTiles: 0, completed: 0, failed: 0 };
    page.on("websocket", socket => {
        socket.on("framesent", frame => {
            if (!started) return;
            wire.outgoingJsonBytes += Buffer.byteLength(frame.payload);
            const message = JSON.parse(String(frame.payload));
            if (["cancel_request", "cancel_tiles"].includes(message.action)) wire.cancelMessages++;
        });
        socket.on("framereceived", frame => {
            if (!started) return;
            wire.incomingJsonBytes += Buffer.byteLength(frame.payload);
            const message = JSON.parse(String(frame.payload));
            if (message.action === "tile_fragment" || message.action === "tile_data") {
                const bytes = message.action === "tile_fragment" ? message.fragment_bytes : message.size_bytes;
                wire.payloadBytes += bytes;
                if (message.attempt > 1) wire.retransmittedPayloadBytes += bytes;
                if (message.action === "tile_fragment") {
                    wire.fragments++;
                    assert.ok(bytes <= windowBytes, "Fragmento excede ventana configurada");
                } else wire.tiles++;
            }
            if (message.action === "request_complete") {
                wire.completed++; wire.failed += message.failed; wire.cancelledTiles += message.cancelled || 0;
            }
        });
    });
    try {
        await page.goto(base);
        await page.locator("#image").selectOption(dataset.id);
        await settled(page);
        await json(base, "/api/cache/clear"); // This server and its cache belong exclusively to this experiment.
        const cacheBefore = await json(base, "/api/cache/stats");
        started = true;
        await page.evaluate(({ windowBytes, scenario }) => {
            cancelCurrent(); pyramid.release();
            document.getElementById("transfer-window").value = String(windowBytes);
            document.getElementById("drop-ack").checked = scenario === "missing_ack";
            const measurement = window.__p7 = { start: performance.now(), firstPaintMs: null, firstDetailMs: null,
                visibleCompleteMs: null, obsoletePayloadBytes: 0, peakBitmapBytes: 0 };
            const draw = pyramid.draw.bind(pyramid);
            pyramid.draw = () => {
                draw();
                const ms = performance.now() - measurement.start;
                if (measurement.firstPaintMs == null && pyramid.cache.size) measurement.firstPaintMs = ms;
                const loaded = [...pyramid.visible].filter(id => pyramid.cache.has(id)).length;
                if (measurement.firstDetailMs == null && loaded) measurement.firstDetailMs = ms;
                if (pyramid.visible.size && loaded === pyramid.visible.size) measurement.visibleCompleteMs = ms;
                measurement.peakBitmapBytes = Math.max(measurement.peakBitmapBytes, pyramid.bytes);
            };
            socket.addEventListener("message", event => {
                const message = JSON.parse(event.data);
                if (["tile_data", "tile_fragment"].includes(message.action) &&
                    (message.request_id !== requestId || !pyramid.protects(message.tile_id))) {
                    measurement.obsoletePayloadBytes += message.fragment_bytes ?? message.size_bytes;
                }
            });
            pyramid.open(metadata); pyramid.native();
            pyramid.cx = metadata.width * 0.35; pyramid.cy = metadata.height * 0.5;
            pyramid.clamp(); pyramid.refresh(); loadRegion();
        }, { windowBytes, scenario });
        if (scenario === "pan") {
            for (let i = 0; i < steps; i++) {
                await page.waitForTimeout(45);
                await page.evaluate(index => {
                    pyramid.cx = metadata.width * (index % 2 ? 0.75 : 0.25);
                    pyramid.cy = metadata.height * (index % 3 ? 0.65 : 0.35);
                    pyramid.clamp(); pyramid.refresh();
                }, i);
            }
            await page.evaluate(() => {
                window.__p7.visibleCompleteMs = null;
                window.__p7.lastGestureMs = performance.now() - window.__p7.start;
                pyramid.flush();
            });
        }
        await settled(page);
        const rendering = await page.evaluate(() => ({ ...window.__p7,
            transferCompleteMs: performance.now() - window.__p7.start, bitmapBytes: pyramid.bytes,
            bitmapBudgetBytes: pyramid.maxBytes, peakBitmapBytes: pyramid.peakBytes,
            exactVisibleTiles: pyramid.progress().loaded, requestedLevel: pyramid.z, maxZoom: metadata.maxZoom,
            backupReady: pyramid.cache.has(pyramid.backupId) }));
        if (scenario === "pan") {
            await page.waitForTimeout(await page.evaluate(() => pyramid.ttlMs + 600));
            rendering.afterTtl = await page.evaluate(() => ({ bytes: pyramid.bytes, entries: pyramid.cache.size,
                evictions: pyramid.evictions, allRetainedUseful: [...pyramid.cache.keys()].every(id => pyramid.protects(id)),
                backupReady: pyramid.cache.has(pyramid.backupId) }));
            assert.equal(rendering.afterTtl.allRetainedUseful, true);
            assert.equal(rendering.afterTtl.backupReady, true);
        }
        const hash = await page.evaluate(async () => {
            const items = [];
            for (const id of [...pyramid.visible].sort()) {
                const bitmap = pyramid.cache.get(id).bitmap;
                const canvas = document.createElement("canvas"); canvas.width = bitmap.width; canvas.height = bitmap.height;
                const ctx = canvas.getContext("2d"); ctx.drawImage(bitmap, 0, 0);
                const digest = await crypto.subtle.digest("SHA-256", ctx.getImageData(0, 0, canvas.width, canvas.height).data);
                items.push({ id, sha256: [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("") });
            }
            return items;
        });
        assert.equal(rendering.backupReady, true);
        assert.ok(rendering.peakBitmapBytes <= rendering.bitmapBudgetBytes);
        assert.equal(wire.failed, 0);
        assert.deepEqual(errors, []);
        if (scenario === "missing_ack") assert.ok(wire.retransmittedPayloadBytes > 0);
        wire.stalePayloadBytes = rendering.obsoletePayloadBytes;
        const cache = await json(base, "/api/cache/stats");
        const result = { windowBytes, scenario, rendering, wire, visibleTileHashes: hash, serverCache: { ...cache,
            caseHits: cache.hits - cacheBefore.hits, caseMisses: cache.misses - cacheBefore.misses,
            caseEvictions: cache.evictions - cacheBefore.evictions } };
        console.log(`${scenario.padEnd(11)} window=${String(windowBytes).padStart(5)}B first=${rendering.firstPaintMs?.toFixed(1)}ms complete=${rendering.transferCompleteMs.toFixed(1)}ms payload=${wire.payloadBytes}B retry=${wire.retransmittedPayloadBytes}B memory=${rendering.peakBitmapBytes}B`);
        return result;
    } finally { await context.close(); }
}

async function main() {
    const config = options(process.argv.slice(2));
    if (!config) {
        console.log("node scripts/benchmark-viewer.cjs [JAR] [--work DIR --image ID] [--output REPORT.json] [--repetitions 1] [--steps 10]");
        return;
    }
    if (config.output) await fs.access(path.dirname(path.resolve(config.output)));
    const temporary = await fs.mkdtemp(path.join(tmpdir(), "gtp-p7-"));
    const work = path.join(temporary, "work"), originals = path.join(temporary, "originales");
    await fs.mkdir(work); await fs.mkdir(originals);
    let server, browser, dataset;
    try {
        if (config.work) {
            const directory = path.resolve(config.work, config.image);
            const record = JSON.parse(await fs.readFile(path.join(directory, "meta.json"), "utf8"));
            assert.equal(record.metadata.state, "ready", "Dataset debe estar ready");
            assert.notEqual(record.metadata.maxZoom, null);
            const destination = path.join(work, config.image);
            await fs.mkdir(destination);
            await fs.copyFile(path.join(directory, "meta.json"), path.join(destination, "meta.json"));
            await fs.cp(path.join(directory, "tiles"), path.join(destination, "tiles"), { recursive: true });
            dataset = { id: config.image, kind: "prepared_local", sourceBytes: record.source?.declaredSizeBytes,
                sourceSha256: record.source?.sha256, ingestionMs: null, note: "Se copiaron metadata/tiles; no se mide ingesta ni se modifica el dataset original" };
        }
        server = await startServer(config.jar, work, originals);
        if (!dataset) dataset = await prepare(server.base);
        const metadata = await json(server.base, `/api/image/${dataset.id}/metadata`);
        browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE_PATH
            ? { executablePath: process.env.BROWSER_EXECUTABLE_PATH } : { channel: process.env.BROWSER_CHANNEL || "msedge" }) });
        const report = { schemaVersion: 1, phase: "P7_INITIAL", createdAt: new Date().toISOString(),
            environment: { platform: process.platform, node: process.version, browser: browser.version(), javaHeapLimitMiB: 256,
                viewport: { width: 1440, height: 1000, dpr: 1 } }, dataset: { ...dataset, metadata },
            methodology: "Contexto nuevo y caché servidor vacía por caso; misma escena/PNG q3; bytes JSON incluyen Base64, no cabeceras TCP/TLS; ACK omitido es de aplicación",
            cases: [] };
        for (let repetition = 1; repetition <= config.repetitions; repetition++) {
            for (const scenario of ["steady", "missing_ack", "pan"]) {
                let baseline;
                for (const windowBytes of [0, 1500, 16000]) {
                    const result = await experiment(browser, server.base, dataset, windowBytes, scenario, config.steps);
                    if (baseline) assert.deepEqual(result.visibleTileHashes, baseline, "La ventana no debe cambiar los píxeles finales");
                    else baseline = result.visibleTileHashes;
                    report.cases.push({ repetition, ...result });
                }
            }
        }
        if (config.output) {
            await fs.writeFile(path.resolve(config.output), JSON.stringify(report, null, 2) + "\n");
            const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
            await page.goto(server.base); await settled(page);
            await page.screenshot({ path: path.resolve(config.output).replace(/\.json$/i, "") + ".desktop.png", fullPage: true });
            await page.setViewportSize({ width: 390, height: 844 }); await settled(page);
            await page.screenshot({ path: path.resolve(config.output).replace(/\.json$/i, "") + ".mobile.png", fullPage: true });
            console.log(`Reporte: ${path.resolve(config.output)}`);
        }
        console.log(`PASS P7: ${report.cases.length} casos; píxeles finales idénticos entre ventanas, TTL/respaldo y memoria acotada.`);
    } finally {
        if (browser) await browser.close();
        if (server && server.child.exitCode === null && server.child.signalCode === null) {
            const exited = once(server.child, "exit"); server.child.kill(); await exited;
        }
        await fs.rm(temporary, { recursive: true, force: true });
    }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
