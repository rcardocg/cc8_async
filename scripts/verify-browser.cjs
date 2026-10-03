// Prueba E2E opcional: requiere Node.js, Playwright y Edge (BROWSER_CHANNEL permite cambiarlo).
// NODE_PATH puede apuntar a una instalacion de Playwright fuera del proyecto.
"use strict";
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const { chromium } = require("playwright");

async function main() {
    const jar = process.argv[2] || ".build/server-0.0.1-SNAPSHOT.jar";
    const server = spawn("java", ["-jar", jar, "--server.port=0", "--images.demo-enabled=true",
        `--images.directory=.build/browser-empty-${Date.now()}`], { stdio: ["ignore", "pipe", "pipe"] });
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
        browser = await chromium.launch({ headless: true, channel: process.env.BROWSER_CHANNEL || "msedge" });
        const context = await browser.newContext();
        const page = await context.newPage();
        const errors = [];
        const external = [];
        const frames = [];
        context.on("request", request => {
            if (new URL(request.url()).origin !== base) external.push(request.url());
        });
        page.on("pageerror", error => errors.push(error.message));
        page.on("websocket", socket => socket.on("framereceived", event => {
            const message = JSON.parse(event.payload.toString());
            if (message.action === "tile_data") delete message.data;
            frames.push(message);
        }));

        async function complete(target) {
            await target.waitForFunction(() => document.getElementById("status").textContent.includes("12/12 confirmados; 0 fallidos"), null, { timeout: 15000 });
            assert.equal(await target.locator("#tiles img").count(), 12);
            assert.ok(await target.locator("#tiles img").evaluateAll(images => images.every(image => image.complete && image.naturalWidth === 256)));
        }

        await page.goto(base);
        await complete(page);
        assert.equal(await page.locator("#tiles img").first().getAttribute("alt"), "Tile 0,0");

        // Omisión deliberada del ACK de aplicación y un segundo cliente independiente.
        await page.locator("summary").click();
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
        assert.deepEqual(external, [], "Todos los recursos deben provenir del servidor Java");
        assert.deepEqual(errors, [], "No debe haber errores JavaScript en el navegador");
        console.log("PASS: PNG decodificado, dos clientes, recuperación de ACK, presión de memoria, reemplazo de región, cancelación, reconexión y recursos locales.");
    } finally {
        if (browser) await browser.close();
        server.kill();
    }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
