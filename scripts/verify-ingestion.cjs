// Smoke P3 portable, solo módulos Node estándar: PNG completo real (sintético), heap 64 MiB.
// Genera filas secuencialmente; nunca acumula la imagen de prueba completa.
"use strict";
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const { mkdtemp, mkdir, open, readFile, rm, stat } = require("node:fs/promises");
const { tmpdir } = require("node:os");
const { resolve, join } = require("node:path");
const { Readable } = require("node:stream");
const { createDeflate } = require("node:zlib");
const { createHash } = require("node:crypto");
const { once } = require("node:events");

const crcTable = Array.from({ length: 256 }, (_, i) => {
    for (let bit = 0; bit < 8; bit++) i = (i >>> 1) ^ ((i & 1) ? 0xedb88320 : 0);
    return i;
});
function chunk(type, data) {
    const name = Buffer.from(type), prefix = Buffer.alloc(8), suffix = Buffer.alloc(4);
    prefix.writeUInt32BE(data.length); name.copy(prefix, 4);
    let crc = 0xffffffff;
    for (const bytes of [name, data]) for (const byte of bytes) crc = (crc >>> 8) ^ crcTable[(crc ^ byte) & 255];
    suffix.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
    return Buffer.concat([prefix, data, suffix]);
}
async function fixture(file, width, height) {
    const output = await open(file, "wx"); const sha = createHash("sha256");
    async function write(bytes) { sha.update(bytes); await output.writeFile(bytes); }
    try {
        await write(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
        const header = Buffer.alloc(13); header.writeUInt32BE(width); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 2;
        await write(chunk("IHDR", header));
        function* rows() {
            for (let y = 0; y < height; y++) {
                const row = Buffer.alloc(1 + width * 3);
                for (let x = 0; x < width; x++) { row[1 + x * 3] = x & 255; row[2 + x * 3] = y & 255; row[3 + x * 3] = (x + y) & 255; }
                yield row;
            }
        }
        for await (const compressed of Readable.from(rows()).pipe(createDeflate())) await write(chunk("IDAT", compressed));
        await write(chunk("IEND", Buffer.alloc(0)));
        return sha.digest("hex");
    } finally { await output.close(); }
}
function run(args, cwd, env) {
    return new Promise((resolve, reject) => {
        const child = spawn("java", args, { cwd, env, stdio: ["ignore", "pipe", "pipe"] });
        let out = "", err = "";
        child.stdout.on("data", data => out += data);
        child.stderr.on("data", data => err += data);
        const timer = setTimeout(() => { child.kill(); reject(new Error("Ingesta excedió timeout de smoke")); }, 180000);
        child.once("error", error => { clearTimeout(timer); reject(error); });
        child.once("exit", code => { clearTimeout(timer); code === 0 ? resolve(out) : reject(new Error(`Java ${code}: ${err}\n${out}`)); });
    });
}
async function server(jar, cwd, env) {
    const child = spawn("java", ["-Xmx128m", "-jar", jar, "--server.port=0"], { cwd, env, stdio: ["ignore", "pipe", "pipe"] });
    const stop = async () => {
        if (child.exitCode === null && child.signalCode === null) { const exited = once(child, "exit"); child.kill(); await exited; }
    };
    try {
        const port = await new Promise((resolve, reject) => {
            let output = "";
            const timer = setTimeout(() => reject(new Error(`Arranque excedido: ${output}`)), 30000);
            function capture(bytes) {
                output = (output + bytes).slice(-10000);
                const match = output.match(/Tomcat started on port (\d+)/);
                if (match) { clearTimeout(timer); resolve(Number(match[1])); }
            }
            child.stdout.on("data", capture); child.stderr.on("data", capture);
            child.once("error", error => { clearTimeout(timer); reject(error); });
            child.once("exit", code => { clearTimeout(timer); reject(new Error(`Servidor ${code}: ${output}`)); });
        });
        return { base: `http://localhost:${port}`, stop };
    } catch (error) { await stop(); throw error; }
}
async function main() {
    const jar = resolve(process.argv[2] || ".build/server.jar");
    const directory = await mkdtemp(join(tmpdir(), "gtp p3 smoke "));
    try {
        const originals = join(directory, "originales"), work = join(directory, "work");
        await mkdir(originals);
        const source = join(originals, "imagen con espacios.png");
        const width = 8193, height = 4097;
        const sha = await fixture(source, width, height);
        const env = { ...process.env, GTP_IMAGES: originals, GTP_WORK: work };
        const output = await run(["-Xmx64m", "-jar", jar, "cli", "ingest", source, "--image-id", "smoke", "--max-memory-mib", "64", "--pretty"], directory, env);
        const state = JSON.parse(output);
        assert.equal(state.state, "ready"); assert.equal(state.processedTiles, state.totalTiles);
        const manifest = JSON.parse(await readFile(join(work, "smoke", "meta.json"), "utf8"));
        assert.equal(manifest.source.sha256, sha);
        const first = await readFile(join(work, "smoke", "tiles", "0", "0_0.png"));
        assert.equal(first.readUInt32BE(16), 129); assert.equal(first.readUInt32BE(20), 65);
        const last = await readFile(join(work, "smoke", "tiles", String(manifest.metadata.maxZoom), "32_16.png"));
        assert.equal(last.readUInt32BE(16), 1); assert.equal(last.readUInt32BE(20), 1);
        // Dos JVM distintas comprueban persistencia P2/P3 y offset de subida sin conservar objetos Java.
        const file = await open(source, "r"), firstBlock = Buffer.alloc(1024 * 1024);
        try { await file.read(firstBlock, 0, firstBlock.length, 0); } finally { await file.close(); }
        const headers = { "Content-Type": "application/octet-stream" };
        let running = await server(jar, directory, env);
        try {
            const url = `${running.base}/api/images?imageId=restart_pending&name=original.png&sizeBytes=${(await stat(source)).size}`;
            assert.equal((await fetch(url, { method: "POST", headers, body: firstBlock.subarray(0, 33) })).status, 202);
            assert.equal((await fetch(url, { method: "POST", headers, body: firstBlock.subarray(0, 33) })).status, 409);
            assert.equal((await fetch(`${running.base}/api/image/restart_pending/upload?offset=0`, { method: "PUT", headers, body: firstBlock })).status, 200);
        } finally { await running.stop(); }
        running = await server(jar, directory, env);
        try {
            const pending = await (await fetch(`${running.base}/api/image/restart_pending/status`)).json();
            assert.equal(pending.state, "pending"); assert.equal(pending.processedTiles, 0);
            const upload = await (await fetch(`${running.base}/api/image/restart_pending/upload`)).json();
            assert.equal(upload.receivedBytes, firstBlock.length);
            const ready = await (await fetch(`${running.base}/api/image/smoke/status`)).json();
            assert.equal(ready.state, "ready"); assert.equal(ready.totalTiles, 783);
        } finally { await running.stop(); }
        console.log(JSON.stringify({ status: "PASS", platform: process.platform, width, height,
            fullRgbaBytes: width * height * 4, heapLimitMiB: 64, compressedBytes: (await stat(source)).size,
            levels: state.completedLevels, tiles: state.totalTiles, sha256: sha, serverRestartPersistence: "pending, ready y offset de 1 MiB",
            note: "PNG sintético íntegro; no acredita datasets del curso ni mide RSS. Sin imagen completa en RAM." }, null, 2));
    } finally { await rm(directory, { recursive: true, force: true }); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
