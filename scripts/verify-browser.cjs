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
        await page.locator("#image").selectOption("browser_p2");
        await page.waitForFunction(() => document.getElementById("catalog-status").textContent.includes("browser_p2: pending"));
        assert.equal(await page.locator("#load").isEnabled(), false);
        assert.equal(await page.locator("#tiles img").count(), 0);
        await page.locator("#register-image").click();
        await page.waitForFunction(() => document.getElementById("registration-status").textContent.includes("duplicado"));
        await page.locator("#ingest-image").click();
        await page.waitForFunction(() => document.getElementById("ingest-status").textContent.includes("browser_p2: ready"), null, { timeout: 30000 });
        await page.locator("#check-image-status").click();
        await page.waitForFunction(() => document.getElementById("catalog-status").textContent.includes("browser_p2: ready"));
        assert.equal(await page.locator("#load").isEnabled(), false, "P3 genera tiles; el transporte multinivel es P5");
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
        await page.locator("#catalog-view summary").click();
        await page.locator("#drop-ack").check();
        await page.locator("#load").click();
        const second = await context.newPage();
        second.on("pageerror", error => errors.push(error.message));
        await second.goto(base);
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
        await page.locator('[data-dx="4"]').evaluate(button => { button.click(); button.click(); });
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
        assert.deepEqual(external, [], "Todos los recursos deben provenir del servidor Java");
        assert.deepEqual(errors, [], "No debe haber errores JavaScript en el navegador");
        console.log("PASS: PNG local, preview/zoom, registro P2 pendiente, catálogo/estado/duplicados; transferencia y procesamiento P3 ready; tiles GTP, dos clientes, recuperación, memoria, cancelación, reconexión y recursos locales.");
    } finally {
        if (browser) await Promise.race([browser.close(), new Promise(resolve => { const timer = setTimeout(resolve, 5000); timer.unref(); })]);
        if (server.exitCode === null && server.signalCode === null) { const exited = once(server, "exit"); server.kill(); await exited; }
        await rm(temporary, { recursive: true, force: true });
    }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
