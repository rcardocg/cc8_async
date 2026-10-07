"use strict";

const byId = id => document.getElementById(id);
const columns = 4;
const rows = 3;
const slots = new Map();
const decoded = new Set();
const logLines = [];
let socket;
let metadata;
let metadataGeneration = 0;
let requestId = null;
let requestNumber = 0;
let ackDropped = false;
let protocolReady = false;
let messageChain = Promise.resolve();
const pyramid = new PyramidView(byId("pyramid-canvas"), () => loadRegion());
let activeViewRequest = null;
let viewGeneration = 0;
const fragments = new Map();
const activeTiles = new Set();

let localFile = null;
let inspectionText = null;
let localGeneration = 0;
let inspectionAbort = null;
let previewUrl = null;
let previewDimensions = null;
let previewScale = 1;
let registrationBusy = false;

function bytesLabel(value) {
    if (!Number.isFinite(value)) return "No disponible";
    const units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"];
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
    return `${value.toLocaleString("es", { maximumFractionDigits: 2 })} ${units[unit]}`;
}

function releasePreview() {
    byId("local-preview-section").hidden = true;
    byId("local-preview").removeAttribute("src");
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = null;
    previewDimensions = null;
}

function resetInspection() {
    inspectionAbort?.abort();
    inspectionText = null;
    byId("register-image").disabled = true;
    byId("download-inspection").disabled = true;
    byId("inspection-summary").replaceChildren();
    byId("inspection-summary").hidden = true;
    byId("inspection-details").hidden = true;
    byId("inspection-json").textContent = "";
    byId("local-next").hidden = true;
    releasePreview();
}

function setPreviewScale(scale) {
    if (!previewDimensions) return;
    previewScale = Math.max(.05, Math.min(8, scale));
    byId("local-preview").style.width = `${previewDimensions.width * previewScale}px`;
    byId("local-preview").style.height = `${previewDimensions.height * previewScale}px`;
    byId("preview-scale").textContent = `${Math.round(previewScale * 100)}%`;
}

function fitPreview() {
    if (!previewDimensions) return;
    const area = byId("local-viewport");
    setPreviewScale(Math.min(1, area.clientWidth / previewDimensions.width, area.clientHeight / previewDimensions.height));
    area.scrollLeft = area.scrollTop = 0;
}

async function inspectLocalFile() {
    const generation = ++localGeneration;
    resetInspection();
    const file = localFile;
    byId("inspect-again").disabled = !file;
    if (!file) return;
    const controller = new AbortController();
    inspectionAbort = controller;
    byId("local-status").textContent = `Inspección de ${file.name} (${bytesLabel(file.size)}) · lectura de 33 bytes…`;
    try {
        const header = await file.slice(0, 33).arrayBuffer();
        if (generation !== localGeneration) return;
        const query = new URLSearchParams({ name: file.name, sizeBytes: String(file.size), tileSize: byId("inspect-tile-size").value });
        const response = await fetch(`/api/png/inspect?${query}`, {
            method: "POST", headers: { "Content-Type": "application/octet-stream" }, body: header, signal: controller.signal
        });
        const raw = await response.text();
        const report = JSON.parse(raw);
        if (generation !== localGeneration) return;
        if (!response.ok) throw new Error(report.error || `Inspección HTTP ${response.status}`);
        inspectionText = raw;
        byId("register-image").disabled = registrationBusy;
        byId("registration-id").value = file.name.replace(/\.png$/i, "").normalize("NFKD")
            .replace(/[^a-zA-Z0-9_-]+/g, "_").slice(0, 64) || `imagen_${Date.now()}`;
        byId("registration-status").textContent = "Cabecera lista para registrar. Se guardarán metadata y niveles, no el original completo.";
        byId("download-inspection").disabled = false;
        const facts = [
            ["Archivo", file.name], ["Tamaño comprimido", bytesLabel(file.size)],
            ["Dimensiones", `${report.width.toLocaleString("es")} × ${report.height.toLocaleString("es")} px`],
            ["Color", `${report.bitDepth} bits · tipo ${report.colorType} · ${report.channels} canales`],
            ["Entrelazado", report.interlaced ? "Adam7 (requiere pipeline adicional)" : "No"],
            ["Pirámide estimada", `${report.pyramid.maxZoom + 1} niveles · ${report.pyramid.totalTiles.toLocaleString("es")} tiles`],
            ["RAM si se abre completa (RGBA8)", bytesLabel(report.fullDecodeRgbaBytes)],
            ["Mínimo de buffers de filas", bytesLabel(report.minimumRowBuffersBytes)],
            ["Disco de tiles estimado", bytesLabel(report.pyramid.estimatedWorkBytes)]
        ];
        for (const [label, value] of facts) {
            const box = document.createElement("div");
            const term = document.createElement("dt"); term.textContent = label;
            const description = document.createElement("dd"); description.textContent = value;
            box.append(term, description); byId("inspection-summary").append(box);
        }
        byId("inspection-summary").hidden = false;
        byId("inspection-details").hidden = false;
        byId("inspection-json").textContent = JSON.stringify(report, null, 2);
        byId("local-status").textContent = `Cabecera válida: ${file.name}. Se enviaron ${header.byteLength} bytes al servidor.`;
        byId("local-next").hidden = false;
        byId("local-next").textContent = "P1 verifica firma/IHDR/CRC, no la integridad completa. P3 transfiere y procesa solo al pulsar su botón. El servidor recibe un nombre, no una ruta ni acceso al archivo local. Las estimaciones no garantizan RAM/disco reales.";
        if (report.width * report.height > 4_000_000 || file.size > 16 * 1024 * 1024) {
            byId("local-next").textContent += " Vista previa omitida por tamaño: el original grande no se decodifica en el navegador.";
            return;
        }
        const url = URL.createObjectURL(file);
        let installed = false;
        try {
            const image = new Image(); image.id = "local-preview";
            image.alt = `Vista previa local de ${file.name}`; image.draggable = false; image.src = url;
            await image.decode();
            if (generation !== localGeneration) return;
            previewUrl = url; installed = true;
            byId("local-preview").replaceWith(image);
            previewDimensions = { width: image.naturalWidth, height: image.naturalHeight };
            byId("local-preview-section").hidden = false;
            fitPreview();
        } catch (error) {
            if (generation === localGeneration) {
                byId("local-status").textContent = `Cabecera válida, pero la vista previa no se pudo decodificar: ${file.name}.`;
            }
        } finally {
            if (!installed) URL.revokeObjectURL(url);
        }
    } catch (error) {
        if (generation === localGeneration && error.name !== "AbortError") byId("local-status").textContent = error.message;
    }
}

byId("local-file").addEventListener("change", () => {
    localFile = byId("local-file").files[0] || null;
    if (!localFile) { ++localGeneration; resetInspection(); byId("inspect-again").disabled = true; return; }
    inspectLocalFile();
});
byId("inspect-again").addEventListener("click", inspectLocalFile);
byId("inspect-tile-size").addEventListener("change", () => { if (localFile) inspectLocalFile(); });
byId("clear-local").addEventListener("click", () => {
    ++localGeneration; resetInspection(); localFile = null;
    byId("local-file").value = ""; byId("inspect-again").disabled = true;
    byId("local-status").textContent = "Ningún archivo seleccionado.";
});
byId("download-inspection").addEventListener("click", () => {
    if (!inspectionText) return;
    const url = URL.createObjectURL(new Blob([inspectionText], { type: "application/json" }));
    const link = document.createElement("a"); link.href = url; link.download = "p1-inspeccion.json";
    document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
});
byId("preview-in").addEventListener("click", () => setPreviewScale(previewScale * 1.25));
byId("preview-out").addEventListener("click", () => setPreviewScale(previewScale / 1.25));
byId("preview-native").addEventListener("click", () => setPreviewScale(1));
byId("preview-fit").addEventListener("click", fitPreview);

async function refreshCatalog() {
    const response = await fetch("/api/images", { cache: "no-store" });
    if (!response.ok) throw new Error(`Catálogo HTTP ${response.status}`);
    const ids = await response.json();
    const select = byId("image"); const previous = select.value;
    select.replaceChildren(...ids.map(id => new Option(id, id)));
    if (ids.includes(previous)) select.value = previous;
    return ids;
}

async function checkImageStatus() {
    const id = byId("image").value;
    if (!id) return;
    try {
        const response = await fetch(`/api/image/${encodeURIComponent(id)}/status`, { cache: "no-store" });
        if (!response.ok) throw new Error(`Estado HTTP ${response.status}`);
        const state = await response.json();
        if (byId("image").value !== id) return;
        byId("catalog-status").textContent = state.state === "ready"
            ? `${id}: ready · ${state.processedTiles.toLocaleString("es")} bloques preparados.`
            : `${id}: ${state.state}; ${state.processedTiles}/${state.totalTiles} tiles; ${state.message}${state.error ? `; ${state.error}` : ""}`;
    } catch (error) { byId("catalog-status").textContent = error.message; }
}

byId("register-image").addEventListener("click", async () => {
    if (!localFile || !inspectionText || registrationBusy || !byId("registration-id").reportValidity()) return;
    const id = byId("registration-id").value;
    if (!/^[a-zA-Z0-9_-]{1,64}$/.test(id)) { byId("registration-status").textContent = "Escribe un identificador: letras, números, guion o guion bajo."; return; }
    const file = localFile; const generation = localGeneration;
    const tileSize = JSON.parse(inspectionText).pyramid.tileSize;
    registrationBusy = true; byId("register-image").disabled = true;
    byId("registration-status").textContent = `Registrando ${id}…`;
    try {
        const header = await file.slice(0, 33).arrayBuffer();
        const query = new URLSearchParams({ imageId: id, name: file.name, sizeBytes: String(file.size), tileSize: String(tileSize) });
        const response = await fetch(`/api/images?${query}`, {
            method: "POST", headers: { "Content-Type": "application/octet-stream" }, body: header
        });
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || `Registro HTTP ${response.status}`);
        await refreshCatalog();
        if (generation === localGeneration) {
            byId("image").value = id;
            await selectImage();
            byId("registration-status").textContent = `Registrada ${id}: ${result.state}. Ahora puedes transferir y preparar la imagen.`;
        }
    } catch (error) {
        if (generation === localGeneration) byId("registration-status").textContent = error.message;
    } finally {
        registrationBusy = false; byId("register-image").disabled = !inspectionText;
    }
});
byId("refresh-catalog").addEventListener("click", async () => {
    try { await refreshCatalog(); await selectImage(); }
    catch (error) { byId("catalog-status").textContent = error.message; }
});
byId("check-image-status").addEventListener("click", checkImageStatus);

let ingestion = null;
async function ingestionRequest(path, options = {}) {
    const response = await fetch(path, { cache: "no-store", ...options });
    const body = response.status === 204 ? null : await response.json();
    if (!response.ok) throw new Error(body?.error || `Ingesta HTTP ${response.status}`);
    return body;
}

byId("ingest-image").addEventListener("click", async () => {
    if (ingestion) return;
    const file = localFile, id = byId("registration-id").value;
    if (!file || !inspectionText || !/^[a-zA-Z0-9_-]{1,64}$/.test(id)) {
        byId("ingest-status").textContent = "Selecciona e inspecciona el PNG y escribe su ID registrado."; return;
    }
    const report = JSON.parse(inspectionText);
    if (report.interlaced || report.bitDepth === 16) {
        byId("ingest-status").textContent = "P3 inicial no procesa Adam7 ni 16 bits. Inspección y registro siguen disponibles."; return;
    }
    const job = { id, abort: new AbortController(), stopping: false, processing: false };
    ingestion = job;
    byId("ingest-image").disabled = byId("reset-upload").disabled = true;
    byId("stop-ingest").disabled = false;
    const base = `/api/image/${encodeURIComponent(id)}`;
    try {
        const metadata = await ingestionRequest(`${base}/metadata`);
        const upload = await ingestionRequest(`${base}/upload`);
        if (file.size !== upload.declaredSizeBytes || metadata.width !== report.width || metadata.height !== report.height) {
            throw new Error("El archivo no coincide con el registro. Para cambiar de original usa otro ID.");
        }
        let offset = upload.receivedBytes;
        while (offset < file.size) {
            if (job.stopping) throw new Error("Transferencia detenida. Puedes reanudar con el mismo archivo e ID.");
            const chunk = file.slice(offset, Math.min(file.size, offset + upload.maxChunkBytes));
            const result = await ingestionRequest(`${base}/upload?offset=${offset}`, {
                method: "PUT", headers: { "Content-Type": "application/octet-stream" }, body: chunk, signal: job.abort.signal
            });
            offset = result.receivedBytes;
            byId("ingest-status").textContent = `${id}: transferidos ${bytesLabel(offset)} / ${bytesLabel(file.size)}.`;
        }
        if (job.stopping) throw new Error("Transferencia detenida; el servidor conserva los bloques recibidos.");
        await ingestionRequest(`${base}/ingest`, { method: "POST" });
        job.processing = true;
        if (job.stopping) await ingestionRequest(`${base}/ingest/cancel`, { method: "POST" });
        for (;;) {
            const state = await ingestionRequest(`${base}/status`);
            byId("ingest-status").textContent = `${id}: ${state.state}; ${state.processedTiles}/${state.totalTiles} tiles. ${state.error || state.message}`;
            if (state.state !== "processing") break;
            await new Promise(resolve => setTimeout(resolve, 500));
        }
        await refreshCatalog();
        if (byId("image").value === id) await selectImage();
    } catch (error) {
        byId("ingest-status").textContent = error.name === "AbortError"
            ? "Transferencia detenida; consulta/reanuda con el mismo PNG. Los bloques confirmados se conservan."
            : error.message;
    } finally {
        ingestion = null;
        byId("ingest-image").disabled = byId("reset-upload").disabled = false;
        byId("stop-ingest").disabled = true;
    }
});

byId("stop-ingest").addEventListener("click", async () => {
    if (!ingestion) return;
    ingestion.stopping = true;
    if (!ingestion.processing) ingestion.abort.abort();
    else {
        try { await ingestionRequest(`/api/image/${encodeURIComponent(ingestion.id)}/ingest/cancel`, { method: "POST" }); }
        catch (error) { byId("ingest-status").textContent = error.message; }
    }
});
byId("reset-upload").addEventListener("click", async () => {
    const id = byId("registration-id").value;
    if (!/^[a-zA-Z0-9_-]{1,64}$/.test(id) || ingestion) return;
    try {
        await ingestionRequest(`/api/image/${encodeURIComponent(id)}/upload`, { method: "DELETE" });
        byId("ingest-status").textContent = `${id}: copia temporal de transferencia eliminada; el original local se conserva.`;
    } catch (error) { byId("ingest-status").textContent = error.message; }
});
let previewDrag = null;
byId("local-viewport").addEventListener("pointerdown", event => {
    if (event.pointerType !== "mouse" || event.button !== 0) return;
    const area = event.currentTarget;
    previewDrag = { x: event.clientX, y: event.clientY, left: area.scrollLeft, top: area.scrollTop };
    area.setPointerCapture(event.pointerId); area.classList.add("dragging"); event.preventDefault();
});
byId("local-viewport").addEventListener("pointermove", event => {
    if (!previewDrag) return;
    event.currentTarget.scrollLeft = previewDrag.left + previewDrag.x - event.clientX;
    event.currentTarget.scrollTop = previewDrag.top + previewDrag.y - event.clientY;
});
for (const type of ["pointerup", "pointercancel", "lostpointercapture"]) {
    byId("local-viewport").addEventListener(type, event => { previewDrag = null; event.currentTarget.classList.remove("dragging"); });
}
window.addEventListener("pagehide", () => { ++localGeneration; inspectionAbort?.abort(); releasePreview(); });

function log(message) {
    logLines.push(`${new Date().toLocaleTimeString()} ${message}`);
    if (logLines.length > 150) logLines.shift();
    byId("log").textContent = logLines.join("\n");
    byId("log").scrollTop = byId("log").scrollHeight;
}

function send(message) {
    if (!protocolReady || socket?.readyState !== WebSocket.OPEN) return false;
    socket.send(JSON.stringify({ version: 1, ...message }));
    // Traza de aplicación: correlación sin copiar payloads Base64 al registro.
    log(`→ ${message.action}${traceIds(message)}${message.tiles ? `; ${message.tiles.length} tiles` : ""}`);
    return true;
}

function traceIds(message) {
    return ["request_id", "transfer_id", "tile_id"]
        .filter(key => message[key] != null)
        .map(key => `; ${key}=${message[key]}`).join("");
}

function clearView() {
    // Al retirar referencias y elementos se liberan los bitmaps de la región anterior.
    byId("tiles").replaceChildren();
    slots.clear();
    decoded.clear();
    pyramid.release();
    activeViewRequest = null;
    viewGeneration++;
    fragments.clear();
    activeTiles.clear();
    byId("region").textContent = "Sin región solicitada.";
}

function cancelCurrent() {
    if (requestId) send({ action: "cancel_request", request_id: requestId });
    requestId = null;
    fragments.clear();
    activeTiles.clear();
}

function updateLoadButton() {
    byId("load").disabled = !protocolReady || !metadata || metadata.state !== "ready";
}

async function selectImage() {
    const generation = ++metadataGeneration;
    cancelCurrent();
    clearView();
    metadata = null;
    updateLoadButton();
    try {
        const response = await fetch(`/api/image/${encodeURIComponent(byId("image").value)}/metadata`);
        log(`HTTP ${response.status} ${response.url}`);
        if (!response.ok) throw new Error(`Metadata HTTP ${response.status}`);
        const result = await response.json();
        if (generation !== metadataGeneration) return;
        metadata = result;
        checkImageStatus();
        byId("metadata").textContent = `${metadata.width} × ${metadata.height}; tiles de ${metadata.tileSize}px; ${metadata.totalTiles} tiles; ${metadata.format}`;
        byId("x").max = Math.ceil(metadata.width / metadata.tileSize) - 1;
        byId("y").max = Math.ceil(metadata.height / metadata.tileSize) - 1;
        byId("x").value = byId("y").value = 0;
        updateLoadButton();
        const multilevel = metadata.maxZoom != null && metadata.state === "ready";
        byId("pyramid-section").hidden = !multilevel;
        byId("viewport").hidden = multilevel;
        byId("x").disabled = byId("y").disabled = multilevel;
        byId("compatibility-controls").hidden = multilevel || metadata.state !== "ready";
        byId("compatibility-controls").open = !multilevel;
        byId("viewer-empty").hidden = multilevel;
        if (metadata.state !== "ready") byId("status").textContent = `Imagen ${metadata.state}: todavía no tiene tiles preparados.`;
        else if (multilevel) { pyramid.open(metadata); byId("status").textContent = "Imagen preparada: arrastra o usa el zoom para navegar."; }
        else if (protocolReady) loadRegion();
    } catch (error) {
        if (generation === metadataGeneration) byId("status").textContent = error.message;
    }
}

function loadRegion(force = false) {
    if (!metadata || metadata.state !== "ready" || !protocolReady) return;
    if (metadata.maxZoom != null) {
        if (!pyramid.image) pyramid.open(metadata);
        if (force) cancelCurrent();
        if (requestId) {
            const obsolete = [...activeTiles].filter(id => !pyramid.protects(id));
            if (obsolete.length) {
                send({ action: "cancel_tiles", request_id: requestId, tiles: obsolete.map(tile_id => ({ tile_id })) });
                for (const id of obsolete) activeTiles.delete(id);
                for (const [id, entry] of fragments) if (obsolete.includes(entry.tileId)) fragments.delete(id);
            }
            pyramid.progress();
            return; // Keep useful in-flight work; the latest missing view is queued on completion.
        }
        viewGeneration++;
        const tiles = pyramid.missing();
        if (!tiles.length) { pyramid.progress(); byId("status").textContent = "Vista actual completa."; return; }
        requestId = `view-${Date.now()}-${++requestNumber}`;
        activeViewRequest = requestId;
        activeTiles.clear();
        tiles.forEach(tile => activeTiles.add(`${metadata.imageId}:${tile.z}:${tile.x}:${tile.y}:${tile.q}`));
        ackDropped = false;
        send({ action: "memory_pressure", current_usage: pyramid.bytes, memory_limit: pyramid.maxBytes,
            receiver_window_bytes: Math.max(1024 * 1024, pyramid.maxBytes - pyramid.bytes) });
        send({ action: "fetch_tiles", request_id: requestId, imageId: metadata.imageId, tiles, replace: true,
            transfer_window_bytes: Number(byId("transfer-window").value) });
        byId("region").textContent = `${tiles.length} tiles pendientes en nivel ${pyramid.z}; región visible; resolución final PNG.`;
        byId("status").textContent = tiles[0].z === 0 && !pyramid.cache.has(pyramid.backupId)
            ? "Cargando vista general de respaldo…" : "Completando el detalle de la región visible…";
        pyramid.progress();
        return;
    }
    if (!byId("controls").reportValidity()) return;
    const x = Number(byId("x").value);
    const y = Number(byId("y").value);
    if (!Number.isInteger(x) || !Number.isInteger(y)) return;
    const oldRequest = requestId;
    clearView();
    requestId = `view-${Date.now()}-${++requestNumber}`;
    ackDropped = false;
    const width = Math.min(columns, Math.ceil(metadata.width / metadata.tileSize) - x);
    const height = Math.min(rows, Math.ceil(metadata.height / metadata.tileSize) - y);
    // Rangos inclusivos, recortados al tamaño real; X/Y de protocolo son índices de tile.
    const endX = Math.min(metadata.width, (x + width) * metadata.tileSize) - 1;
    const endY = Math.min(metadata.height, (y + height) * metadata.tileSize) - 1;
    byId("region").textContent = `Región: ${width} × ${height} tiles; columnas ${x}–${x + width - 1}, filas ${y}–${y + height - 1}; píxeles X=${x * metadata.tileSize}–${endX}, Y=${y * metadata.tileSize}–${endY} (límites inclusivos).`;
    byId("tiles").style.gridTemplateColumns = `repeat(${width}, ${metadata.tileSize}px)`;
    const tiles = [];
    for (let dy = 0; dy < height; dy++) {
        for (let dx = 0; dx < width; dx++) {
            const tx = x + dx;
            const ty = y + dy;
            const id = `${metadata.imageId}:${tx}:${ty}`;
            const element = document.createElement("div");
            element.className = "tile";
            element.style.width = `${Math.min(metadata.tileSize, metadata.width - tx * metadata.tileSize)}px`;
            element.style.height = `${Math.min(metadata.tileSize, metadata.height - ty * metadata.tileSize)}px`;
            element.title = `Tile ${tx},${ty}; píxeles X=${tx * metadata.tileSize}–${Math.min(metadata.width, (tx + 1) * metadata.tileSize) - 1}, Y=${ty * metadata.tileSize}–${Math.min(metadata.height, (ty + 1) * metadata.tileSize) - 1}`;
            element.textContent = `Esperando ${tx},${ty}`;
            byId("tiles").append(element);
            slots.set(id, element);
            tiles.push({ x: tx, y: ty, z: null });
        }
    }
    if (!send({ action: "fetch_tiles", request_id: requestId, imageId: metadata.imageId, tiles, replace: true,
        transfer_window_bytes: Number(byId("transfer-window").value) })) {
        requestId = null;
        byId("status").textContent = "Conexión cerrada. Reconecte para cargar la región.";
        return;
    }
    log(`Solicitados ${tiles.length} tiles${oldRequest ? "; región anterior sustituida" : ""}.`);
    byId("status").textContent = `Cargando ${tiles.length} tiles…`;
}

async function handleMessage(message, source) {
    if (source !== socket) return;
    if (message.version !== 1) throw new Error("Versión de protocolo no soportada");
    if (message.action === "ready") {
        pyramid.ttlMs = message.out_of_view_ttl_ms || 5000;
        protocolReady = true;
        updateLoadButton();
        log(`← ready; Conectado: ${message.protocol}`);
        byId("status").textContent = "Conectado";
        loadRegion();
        return;
    }
    if (message.action === "adjust_strategy") {
        log(`← adjust_strategy; receiver_window=${message.receiver_window}; límite=${message.max_tiles_concurrent}`);
        byId("metrics").textContent = `Ventana del receptor: ${message.receiver_window}; límite efectivo: ${message.max_tiles_concurrent}`;
        return;
    }
    if (["request_cancelled", "cancel_tiles_accepted", "cancel_ignored", "ack_ignored"].includes(message.action)) {
        log(`← ${message.action}${traceIds(message)}`);
        return;
    }
    if (message.request_id && message.request_id !== requestId) return;
    if (message.action === "tile_fragment") {
        if (!Number.isInteger(message.size_bytes) || message.size_bytes <= 0 || message.size_bytes > 2 * 1024 * 1024 ||
            !Number.isInteger(message.offset) || message.offset < 0) throw new Error("Fragmento inválido");
        let entry = fragments.get(message.transfer_id);
        if (!entry || entry.attempt !== message.attempt) {
            if (message.offset !== 0) return;
            const retained = [...fragments.values()].reduce((sum, item) => sum + item.bytes.length, 0);
            if (retained + message.size_bytes > 4 * 1024 * 1024) throw new Error("Presupuesto de reconstrucción excedido");
            entry = { bytes: new Uint8Array(message.size_bytes), offset: 0, attempt: message.attempt, tileId: message.tile_id };
            fragments.set(message.transfer_id, entry);
        }
        const raw = atob(message.data);
        if (raw.length !== message.fragment_bytes || message.offset + raw.length > entry.bytes.length || entry.tileId !== message.tile_id) {
            throw new Error("Longitud o identidad de fragmento incorrecta");
        }
        if (message.offset === entry.offset) {
            entry.bytes.set(Uint8Array.from(raw, c => c.charCodeAt(0)), message.offset);
            entry.offset += raw.length;
        } else if (message.offset + raw.length > entry.offset) return;
        send({ action: "ack_fragment", request_id: requestId, transfer_id: message.transfer_id,
            tile_id: message.tile_id, attempt: message.attempt, offset: message.offset + raw.length });
        if (entry.offset !== entry.bytes.length) return;
        fragments.delete(message.transfer_id);
        let binary = "";
        for (let i = 0; i < entry.bytes.length; i += 8192) binary += String.fromCharCode(...entry.bytes.subarray(i, i + 8192));
        message = { ...message, action: "tile_data", data: btoa(binary) };
    }
    if (message.action === "request_accepted") {
        log(`← request_accepted${traceIds(message)}; total=${message.total}`);
    } else if (message.action === "tile_data") {
        const activeRequest = requestId;
        if (metadata?.maxZoom != null) {
            const generation = viewGeneration;
            await pyramid.consume(message);
            if (source !== socket || activeRequest !== requestId || generation !== viewGeneration) return;
            if (byId("drop-ack").checked && !ackDropped) {
                ackDropped = true; log(`ACK omitido deliberadamente${traceIds(message)}`); return;
            }
            send({ action: "ack_tile", request_id: activeRequest, transfer_id: message.transfer_id, tile_id: message.tile_id });
            activeTiles.delete(message.tile_id);
            return;
        }
        const slot = slots.get(message.tile_id);
        if (!slot || !activeRequest) return;
        log(`← tile_data${traceIds(message)}; intento=${message.attempt}; ${message.size_bytes} bytes comprimidos`);
        if (!decoded.has(message.tile_id)) {
            let url;
            try {
                if (!["png", "jpeg"].includes(message.compression) || message.size_bytes > 2 * 1024 * 1024) {
                    throw new Error("Formato o tamaño de tile inválido");
                }
                const raw = atob(message.data);
                if (raw.length !== message.size_bytes) throw new Error("Longitud del tile incorrecta");
                const bytes = Uint8Array.from(raw, character => character.charCodeAt(0));
                url = URL.createObjectURL(new Blob([bytes], { type: `image/${message.compression}` }));
                const image = new Image();
                image.alt = `Tile ${message.x},${message.y}`;
                image.src = url;
                await image.decode();
                if (source !== socket || requestId !== activeRequest) return;
                slot.replaceChildren(image);
                slot.classList.remove("failed");
                decoded.add(message.tile_id);
            } catch (error) {
                if (source === socket && requestId === activeRequest) {
                    slot.textContent = "Error de decodificación; esperando recuperación";
                    slot.classList.add("failed");
                    log(error.message);
                }
                return; // No confirmar datos que no se pudieron consumir.
            } finally {
                if (url) URL.revokeObjectURL(url);
            }
        }
        if (byId("drop-ack").checked && !ackDropped) {
            ackDropped = true;
            log(`ACK omitido deliberadamente${traceIds(message)}`);
            return;
        }
        send({ action: "ack_tile", request_id: activeRequest, transfer_id: message.transfer_id, tile_id: message.tile_id });
        log(`Tile ${message.tile_id}, intento ${message.attempt}; ACK después de decodificar.`);
    } else if (message.action === "transfer_state") {
        byId("metrics").textContent = `cwnd=${message.cwnd.toFixed(2)}; en vuelo=${message.in_flight}; cola=${message.pending}; RTT aplicación=${message.rtt_ms.toFixed(1)}ms; RTO=${message.rto_ms}ms`;
    } else if (message.action === "request_complete") {
        log(`← request_complete${traceIds(message)}; confirmados=${message.acknowledged}/${message.total}; fallidos=${message.failed}`);
        byId("status").textContent = `Terminada: ${message.acknowledged}/${message.total} confirmados; ${message.failed} fallidos. Puede volver a cargar la región.`;
        requestId = null;
        activeTiles.clear();
        if (metadata?.maxZoom != null) pyramid.progress(message.failed ? "No se pudo completar la vista. Usa Actualizar vista para reintentar." : "");
        if (metadata?.maxZoom != null) byId("status").textContent = message.failed
            ? "Vista incompleta. El respaldo se conserva; puedes reintentar con Actualizar vista."
            : "Imagen disponible. Arrastra para explorar y acerca para ver el detalle.";
        if (metadata?.maxZoom != null && message.failed === 0 && activeViewRequest === message.request_id && pyramid.missing().length) {
            loadRegion();
        }
    } else if (message.action === "tile_error") {
        activeTiles.delete(message.tile_id);
        fragments.delete(message.transfer_id);
        if (metadata?.maxZoom != null) pyramid.progress(`Tile no disponible: ${message.code}. El respaldo se conserva.`);
        const slot = slots.get(message.tile_id);
        if (slot && !decoded.has(message.tile_id)) {
            slot.textContent = message.message;
            slot.classList.add("failed");
        }
        log(`← tile_error${traceIds(message)}; ${message.code}`);
    } else if (message.action === "error") {
        byId("status").textContent = `${message.code}: ${message.message}`;
        log(`← error${traceIds(message)}; ${byId("status").textContent}`);
    }
}

function connect() {
    protocolReady = false;
    requestId = null;
    if (socket) socket.close();
    clearView();
    updateLoadButton();
    const url = new URL("/ws/tiles", location.href);
    url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
    byId("connection").textContent = `Origen HTTP: ${location.origin} | Destino WebSocket: ${url.href}`;
    log(`Abriendo WebSocket ${url.href}`);
    const connection = new WebSocket(url);
    socket = connection;
    messageChain = Promise.resolve();
    connection.onmessage = event => {
        if (connection !== socket) return;
        messageChain = messageChain.then(() => handleMessage(JSON.parse(event.data), connection))
            .catch(error => log(`Error: ${error.message}`));
    };
    connection.onclose = () => {
        if (connection !== socket) return;
        protocolReady = false;
        requestId = null;
        fragments.clear();
        activeTiles.clear();
        updateLoadButton();
        byId("status").textContent = "Desconectado. Use Reconectar para volver a solicitar la región.";
        log(`WebSocket cerrado: ${url.href}`);
    };
    connection.onerror = () => { if (connection === socket) log("Error WebSocket"); };
}

byId("controls").addEventListener("submit", event => { event.preventDefault(); loadRegion(true); });
byId("image").addEventListener("change", selectImage);
byId("cancel").addEventListener("click", () => {
    cancelCurrent();
    clearView();
    byId("status").textContent = "Solicitud cancelada y región liberada.";
});
byId("reconnect").addEventListener("click", connect);
byId("render-fit").addEventListener("click", () => pyramid.fit());
byId("render-native").addEventListener("click", () => pyramid.native());
byId("render-in").addEventListener("click", () => pyramid.zoom(2));
byId("render-out").addEventListener("click", () => pyramid.zoom(0.5));
byId("transfer-window").addEventListener("change", () => loadRegion(true));
byId("report-memory").addEventListener("click", () => {
    if (!send({ action: "memory_pressure", current_usage: Number(byId("pressure").value) * 1024, memory_limit: 100 * 1024 })) {
        log("No se pudo enviar: conexión cerrada");
    }
});
document.querySelectorAll("[data-dx]").forEach(button => button.addEventListener("click", () => {
    if (!metadata || !protocolReady) return;
    if (metadata.maxZoom != null) {
        pyramid.fitMode = false;
        pyramid.cx += Number(button.dataset.dx) * metadata.tileSize * 2 ** (metadata.maxZoom - pyramid.z);
        pyramid.cy += Number(button.dataset.dy) * metadata.tileSize * 2 ** (metadata.maxZoom - pyramid.z);
        pyramid.clamp(); pyramid.refresh(); return;
    }
    for (const axis of ["x", "y"]) {
        const field = byId(axis);
        field.value = Math.max(0, Math.min(Number(field.max), Number(field.value) + Number(button.dataset[`d${axis}`])));
    }
    loadRegion();
}));

async function start() {
    byId("connection").textContent = `Origen HTTP: ${location.origin}`;
    try {
        const images = await refreshCatalog();
        if (!images.length) {
            byId("metadata").textContent = "No hay tiles preparados. Puedes abrir e inspeccionar un PNG arriba.";
            byId("status").textContent = "Catálogo vacío.";
            return;
        }
        const preferred = images.find(id => id !== "demo_numeros");
        if (preferred) byId("image").value = preferred;
        await selectImage();
        connect();
    } catch (error) {
        byId("status").textContent = error.message;
    }
}
start();
