// Prueba E2E opcional: requiere Node.js, Playwright y Edge (BROWSER_CHANNEL permite cambiarlo).
// NODE_PATH puede apuntar a una instalacion de Playwright fuera del proyecto.
"use strict";
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const { chromium } = require("playwright");
const { deflateSync } = require("node:zlib");
const { mkdtemp, mkdir, rm } = require("node:fs/promises");
const { tmpdir } = require("node:os");
const { join, resolve } = require("node:path");
const { once } = require("node:events");

function pngChunk(type, data) {
    const content = Buffer.concat([Buffer.from(type), data]);
    let crc = 0xffffffff;
    for (const byte of content) {
        crc ^= byte;
        for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
    const checksum = Buffer.alloc(4); checksum.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
    return Buffer.concat([length, content, checksum]);
}

function pngHeader(width, height) {
    const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(width); ihdr.writeUInt32BE(height, 4);
    ihdr[8] = 8; ihdr[9] = 2;
    return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), pngChunk("IHDR", ihdr)]);
}

async function main() {
    const jar = resolve(process.argv[2] || ".build/server.jar");
    const temporary = await mkdtemp(join(tmpdir(), "gtp-browser-"));
    await mkdir(join(temporary, "originales"));
    const server = spawn("java", ["-jar", jar, "--server.port=0", "--images.demo-enabled=true",
        `--images.originals-directory=${join(temporary, "originales")}`,
        `--images.directory=${join(temporary, "work")}`], { stdio: ["ignore", "pipe", "pipe"] });
    let browser;
    let output = "";
    try {
        const port = await new Promise((resolve, reject) => {
            const timeout = setTimeout(() => reject(new Error(`Servidor no inició:\n${output}`)), 30000);
            function capture(chunk) {
                output = (output + chunk).slice(-20000);
                const match = output.match(/Tomcat started on port (\d+)/);
                if (match) { clearTimeout(timeout); resolve(Number(match[1])); }
            }
            server.stdout.on("data", capture);
            server.stderr.on("data", capture);
            server.once("error", error => { clearTimeout(timeout); reject(error); });
            server.once("exit", code => { clearTimeout(timeout); reject(new Error(`Servidor terminó (${code}): ${output}`)); });
        });
        const base = `http://localhost:${port}`;
        browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE_PATH
            ? { executablePath: process.env.BROWSER_EXECUTABLE_PATH }
            : { channel: process.env.BROWSER_CHANNEL || "msedge" }) });
        const context = await browser.newContext();
        const page = await context.newPage();
        const errors = [];
        const external = [];
        const frames = [];
        const inspections = [];
        context.on("request", request => {
            if (new URL(request.url()).origin !== base) external.push(request.url());
            if (new URL(request.url()).pathname === "/api/png/inspect") inspections.push(request.postDataBuffer().length);
        });
        page.on("pageerror", error => errors.push(error.message));
        page.on("websocket", socket => socket.on("framereceived", event => {
            const message = JSON.parse(event.payload.toString());
            if (message.action === "tile_data") delete message.data;
            frames.push(message);
        }));

        async function complete(target) {
            try {
                await target.waitForFunction(() => document.getElementById("status").textContent.includes("12/12 confirmados; 0 fallidos"), null, { timeout: 20000, polling: 100 });
            } catch (error) {
                console.error("Diagnóstico E2E:", JSON.stringify({ errors, frames: frames.slice(-25), server: output.slice(-2000) }));
                throw error;
            }
            assert.equal(await target.locator("#tiles img").count(), 12);
            assert.ok(await target.locator("#tiles img").evaluateAll(images => images.every(image => image.complete && image.naturalWidth === 256)));
        }

        await page.goto(base);
        await complete(page);
        assert.equal(await page.locator("#tiles img").first().getAttribute("alt"), "Tile 0,0");
        assert.ok((await page.locator("#connection").textContent()).includes(`ws://localhost:${port}/ws/tiles`));
        assert.ok((await page.locator("#region").textContent()).includes("píxeles X=0–1023, Y=0–767"));
        const trace = await page.locator("#log").textContent();
        for (const action of ["→ fetch_tiles", "← request_accepted", "← tile_data", "→ ack_tile", "← request_complete"]) {
            assert.ok(trace.includes(action), `Falta en la traza: ${action}`);
        }
        const accepted = frames.find(frame => frame.action === "request_accepted");
        assert.ok(trace.includes(`request_id=${accepted.request_id}`));

        // Abrir archivo local: solo 33 bytes viajan por HTTP, vista pequeña con zoom.
        const pixels = Buffer.alloc((128 * 3 + 1) * 64);
        const small = Buffer.concat([pngHeader(128, 64), pngChunk("IDAT", deflateSync(pixels)), pngChunk("IEND", Buffer.alloc(0))]);
        await page.locator("#local-file").setInputFiles({ name: "pequena.png", mimeType: "image/png", buffer: small });
        await page.waitForFunction(() => !document.getElementById("local-preview-section").hidden);
        assert.ok((await page.locator("#inspection-summary").textContent()).includes("128 × 64 px"));
        await page.locator("#preview-native").click();
        assert.equal(await page.locator("#preview-scale").textContent(), "100%");
        await page.locator("#preview-in").click();
        assert.equal(await page.locator("#preview-scale").textContent(), "125%");
        const download = page.waitForEvent("download");
        await page.locator("#download-inspection").click();
        assert.equal((await download).suggestedFilename(), "p1-inspeccion.json");

        await page.locator("#registration-id").fill("browser_p2");
        await page.locator("#register-image").click();
        await page.waitForFunction(() => document.getElementById("registration-status").textContent.includes("Registrada browser_p2: pending"));
        assert.equal(await page.locator("#image").inputValue(), "browser_p2", "Registrar debe seleccionar la imagen automáticamente");
        await page.waitForFunction(() => document.getElementById("catalog-status").textContent.includes("browser_p2: pending"));
        assert.equal(await page.locator("#load").isEnabled(), false);
        assert.equal(await page.locator("#tiles img").count(), 0);
        await page.locator("#register-image").click();
        await page.waitForFunction(() => document.getElementById("registration-status").textContent.includes("duplicado"));
        await page.locator("#ingest-image").click();
        await page.waitForFunction(() => document.getElementById("ingest-status").textContent.includes("browser_p2: ready"), null, { timeout: 30000 });
        await page.locator("#check-image-status").click();
        await page.waitForFunction(() => document.getElementById("catalog-status").textContent.includes("browser_p2: ready"));
        assert.equal(await page.locator("#load").isEnabled(), true, "La imagen P3 ready permite renderizado multinivel");
        await page.waitForFunction(() => pyramid.cache.size === 1);
        assert.equal(await page.locator("#pyramid-section").isVisible(), true);
        await page.locator("#render-native").click();
        await page.waitForFunction(() => document.getElementById("render-scale").textContent.includes("100.00%"));
        const prepared = await (await context.request.get(`${base}/api/image/browser_p2/metadata`)).json();
        assert.deepEqual(prepared.completedLevels, [0]);
        assert.deepEqual(prepared.availableQualities, [3]);
        await page.locator("#image").selectOption("demo_numeros");
        await complete(page);

        // Dimensiones grandes en cabecera sintética: nunca se intenta decodificar el cuerpo.
        await page.locator("#local-file").setInputFiles({ name: "grande.png", mimeType: "image/png", buffer: pngHeader(100000, 100000) });
        await page.waitForFunction(() => document.getElementById("local-status").textContent.includes("Cabecera válida: grande.png"));
        assert.equal(await page.locator("#local-preview-section").isVisible(), false);
        assert.ok((await page.locator("#local-next").textContent()).includes("omitida por tamaño"));
        await page.locator("#inspect-tile-size").selectOption("512");
        await page.waitForFunction(() => document.getElementById("inspection-json").textContent.includes('"tileSize": 512'));
        await page.locator("#local-file").setInputFiles({ name: "falso.png", mimeType: "image/png", buffer: Buffer.alloc(33) });
        await page.waitForFunction(() => document.getElementById("local-status").textContent.includes("se requiere PNG"));
        assert.equal(await page.locator("#download-inspection").isEnabled(), false);
        await page.locator("#clear-local").click();
        assert.equal(await page.locator("#local-status").textContent(), "Ningún archivo seleccionado.");
        assert.ok(inspections.length >= 4 && inspections.every(size => size === 33));

        // Omisión deliberada del ACK de aplicación y un segundo cliente independiente.
        await page.locator("#protocol-diagnostics > summary").click();
        await page.locator("#drop-ack").check();
        await page.locator("#load").click();
        const second = await context.newPage();
        second.on("pageerror", error => errors.push(error.message));
        await second.goto(base);
        await second.locator("#image").selectOption("demo_numeros");
        await Promise.all([complete(page), complete(second)]);
        assert.ok(frames.some(frame => frame.action === "tile_data" && frame.attempt === 2));
        await page.locator("#drop-ack").uncheck();

        await page.locator("#pressure").selectOption("90");
        await page.locator("#report-memory").click();
        await page.waitForFunction(() => document.getElementById("metrics").textContent.includes("Ventana del receptor: 2"));
        await page.locator("#pressure").selectOption("20");
        await page.locator("#report-memory").click();
        await page.waitForFunction(() => document.getElementById("metrics").textContent.includes("Ventana del receptor: 32"));

        // Dos cambios rápidos: respuestas de la región anterior no deben repintar la nueva.
        await page.locator('#compatibility-controls [data-dx="4"]').evaluate(button => { button.click(); button.click(); });
        await complete(page);
        assert.equal(await page.locator("#tiles img").first().getAttribute("alt"), "Tile 8,0");
        assert.equal(await second.locator("#tiles img").first().getAttribute("alt"), "Tile 0,0");

        await page.locator("#cancel").click();
        assert.equal(await page.locator("#tiles img").count(), 0);
        await page.locator("#reconnect").click();
        await complete(page);
        assert.equal(await page.locator("#tiles img").first().getAttribute("alt"), "Tile 8,0");
        assert.ok((await page.locator("#region").textContent()).includes("píxeles X=2048–3071, Y=0–767"));

        // En el borde la región se reduce a un tile, sin coordenadas fuera de imagen.
        await page.locator("#x").fill("15");
        await page.locator("#y").fill("15");
        await page.locator("#load").click();
        await page.waitForFunction(() => document.getElementById("status").textContent.includes("1/1 confirmados; 0 fallidos"));
        assert.equal(await page.locator("#tiles img").count(), 1);
        assert.ok((await page.locator("#region").textContent()).includes("píxeles X=3840–4095, Y=3840–4095"));
        assert.equal(await page.locator(".tile").getAttribute("title"), "Tile 15,15; píxeles X=3840–4095, Y=3840–4095");

        // Real multilevel pipeline on a synthetic PNG: no original decoded in the browser.
        const w = 3073, h = 1537;
        const source = Buffer.alloc((w * 3 + 1) * h);
        let seed = 12345;
        for (let y = 0; y < h; y++) for (let x = 0; x < w * 3; x++) {
            seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
            source[y * (w * 3 + 1) + 1 + x] = seed & 255;
        }
        const large = Buffer.concat([pngHeader(w, h), pngChunk("IDAT", deflateSync(source)), pngChunk("IEND", Buffer.alloc(0))]);
        await page.locator("#local-file").setInputFiles({ name: "p5-pyramid.png", mimeType: "image/png", buffer: large });
        await page.waitForFunction(() => !document.getElementById("register-image").disabled);
        await page.locator("#registration-id").fill("browser_p5");
        await page.locator("#register-image").click();
        await page.waitForFunction(() => document.getElementById("registration-status").textContent.includes("Registrada browser_p5"));
        assert.equal(await page.locator("#image").inputValue(), "browser_p5");
        await page.locator("#ingest-image").click();
        await page.waitForFunction(() => document.getElementById("ingest-status").textContent.includes("browser_p5: ready"), null, { timeout: 60000 });
        await page.waitForFunction(() => metadata?.imageId === "browser_p5" && requestId === null && pyramid.cache.size > 0);
        assert.ok(await page.evaluate(() => pyramid.z < metadata.maxZoom), "Ajustar debe usar un nivel reducido");
        const fitTileCount = await page.evaluate(() => pyramid.visible.size);
        assert.ok(fitTileCount < Math.ceil(w / 256) * Math.ceil(h / 256));
        await page.locator("#render-native").click();
        await page.waitForFunction(() => pyramid.scale === 1 && pyramid.z === metadata.maxZoom && requestId === null &&
            [...pyramid.visible].every(id => pyramid.cache.has(id)));
        // Compare a native tile bitmap against source pixels, independently of screen scaling.
        const sampled = await page.evaluate(() => {
            const entry = [...pyramid.cache.values()].find(entry => entry.z === metadata.maxZoom);
            const canvas = document.createElement("canvas"); canvas.width = canvas.height = 1;
            const ctx = canvas.getContext("2d"); ctx.drawImage(entry.bitmap, 0, 0);
            return { x: entry.x * metadata.tileSize, y: entry.y * metadata.tileSize,
                rgb: [...ctx.getImageData(0, 0, 1, 1).data].slice(0, 3) };
        });
        const index = sampled.y * (w * 3 + 1) + 1 + sampled.x * 3;
        assert.deepEqual(sampled.rgb, [...source.subarray(index, index + 3)], "El tile nativo debe preservar los píxeles RGB");
        await page.waitForFunction(() => [...pyramid.cache.keys()].every(id => pyramid.protects(id)), null, { timeout: 10000 });
        assert.ok(await page.evaluate(() => pyramid.cache.has(pyramid.backupId)), "El nivel 0 debe sobrevivir al TTL");
        assert.ok(await page.evaluate(() => pyramid.bytes <= pyramid.maxBytes));

        // Clear graphics, then reconstruct native tiles with a 1500-byte application window.
        await page.locator("#cancel").click();
        await page.locator("#transfer-window").selectOption("1500");
        await page.waitForFunction(() => requestId === null && pyramid.cache.size > 0 &&
            [...pyramid.visible].every(id => pyramid.cache.has(id)), null, { timeout: 60000 });
        assert.ok(frames.some(frame => frame.action === "tile_fragment" && frame.fragment_bytes === 1500));
        assert.ok(frames.filter(frame => frame.action === "tile_fragment").every(frame => frame.fragment_bytes <= 1500));
        await page.locator("#render-native").click();
        await page.waitForFunction(() => pyramid.scale === 1 && requestId === null && [...pyramid.visible].every(id => pyramid.cache.has(id)), null, { timeout: 60000 });
        await page.locator("#transfer-window").selectOption("0");
        await page.locator('.viewer-toolbar [data-dx="4"]').click();
        await page.waitForFunction(() => requestId === null && [...pyramid.visible].every(id => pyramid.cache.has(id)));
        await page.locator("#reconnect").click();
        await page.waitForFunction(() => protocolReady && requestId === null && pyramid.cache.size > 0);
        assert.equal(await page.locator("#catalog-view").count(), 0, "El segmento antiguo debe eliminarse");
        await page.locator("#pyramid-canvas").focus();
        await page.keyboard.press("1");
        await page.waitForFunction(() => pyramid.scale === 1 && requestId === null && [...pyramid.visible].every(id => pyramid.cache.has(id)));
        const beforeKey = await page.evaluate(() => pyramid.cx);
        await page.keyboard.press("ArrowLeft");
        assert.ok(await page.evaluate(before => pyramid.cx < before, beforeKey), "Flecha izquierda debe desplazar la vista");
        await page.waitForFunction(() => requestId === null && [...pyramid.visible].every(id => pyramid.cache.has(id)));
        const box = await page.locator("#pyramid-canvas").boundingBox();
        await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
        const beforeDrag = await page.evaluate(() => pyramid.cx);
        await page.mouse.down();
        await page.mouse.move(box.x + box.width / 2 + 70, box.y + box.height / 2, { steps: 8 });
        await page.mouse.up();
        assert.ok(await page.evaluate(before => pyramid.cx < before, beforeDrag), "Arrastrar debe mover la cámara");
        await page.waitForFunction(() => requestId === null && [...pyramid.visible].every(id => pyramid.cache.has(id)));
        assert.equal(await page.locator("#pyramid-canvas").getAttribute("aria-busy"), "false");
        assert.ok((await page.locator("#render-progress-label").textContent()).includes("Vista completa"));
        await page.setViewportSize({ width: 390, height: 844 });
        await page.waitForFunction(() => requestId === null && [...pyramid.visible].every(id => pyramid.cache.has(id)));
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true, "El layout móvil no debe desbordar");
        await page.emulateMedia({ reducedMotion: "reduce" });
        assert.equal(await page.locator("#render-fit").evaluate(button => getComputedStyle(button).transitionDuration), "0s");
        await page.keyboard.press("0");
        await page.waitForFunction(() => pyramid.z < metadata.maxZoom && requestId === null && [...pyramid.visible].every(id => pyramid.cache.has(id)));
        assert.deepEqual(external, [], "Todos los recursos deben provenir del servidor Java");
        assert.deepEqual(errors, [], "No debe haber errores JavaScript en el navegador");
        console.log("PASS: PNG/P2/P3, GTP y dos clientes; pirámide P5, ajustar/nativo, píxeles RGB sin pérdida, TTL, ventana de fragmentos 1500 bytes, navegación/reconexión y recursos locales.");
    } catch (error) {
        if (browser) {
            for (const context of browser.contexts()) for (const page of context.pages()) {
                console.error("Browser diagnostics:", await page.evaluate(() => ({
                    status: document.getElementById("status")?.textContent,
                    log: document.getElementById("log")?.textContent,
                    rendering: typeof pyramid !== "undefined" ? { level: pyramid.z, bytes: pyramid.bytes,
                        visible: [...pyramid.visible], cached: [...pyramid.cache.keys()], scale: pyramid.scale } : null
                })).catch(() => null));
            }
        }
        throw error;
    } finally {
        if (browser) await Promise.race([browser.close(), new Promise(resolve => { const timer = setTimeout(resolve, 5000); timer.unref(); })]);
        if (server.exitCode === null && server.signalCode === null) { const exited = once(server, "exit"); server.kill(); await exited; }
        await rm(temporary, { recursive: true, force: true });
    }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
