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
    byId("region").textContent = "Sin región solicitada.";
}

function cancelCurrent() {
    if (requestId) send({ action: "cancel_request", request_id: requestId });
    requestId = null;
}

function updateLoadButton() {
    byId("load").disabled = !protocolReady || !metadata;
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
        byId("metadata").textContent = `${metadata.width} × ${metadata.height}; tiles de ${metadata.tileSize}px; ${metadata.totalTiles} tiles; ${metadata.format}`;
        byId("x").max = Math.ceil(metadata.width / metadata.tileSize) - 1;
        byId("y").max = Math.ceil(metadata.height / metadata.tileSize) - 1;
        byId("x").value = byId("y").value = 0;
        updateLoadButton();
        if (protocolReady) loadRegion();
    } catch (error) {
        if (generation === metadataGeneration) byId("status").textContent = error.message;
    }
}

function loadRegion() {
    if (!metadata || !protocolReady || !byId("controls").reportValidity()) return;
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
    if (!send({ action: "fetch_tiles", request_id: requestId, imageId: metadata.imageId, tiles, replace: true })) {
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
    if (["request_cancelled", "cancel_ignored", "ack_ignored"].includes(message.action)) {
        log(`← ${message.action}${traceIds(message)}`);
        return;
    }
    if (message.request_id && message.request_id !== requestId) return;
    if (message.action === "request_accepted") {
        log(`← request_accepted${traceIds(message)}; total=${message.total}`);
    } else if (message.action === "tile_data") {
        const activeRequest = requestId;
        const slot = slots.get(message.tile_id);
        if (!slot || !activeRequest) return;
        log(`← tile_data${traceIds(message)}; intento=${message.attempt}; ${message.size_bytes} bytes comprimidos`);
        if (!decoded.has(message.tile_id)) {
            let url;
            try {
                if (!["png", "jpeg"].includes(message.compression) || message.size_bytes > 512 * 1024) {
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
    } else if (message.action === "tile_error") {
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
        updateLoadButton();
        byId("status").textContent = "Desconectado. Use Reconectar para volver a solicitar la región.";
        log(`WebSocket cerrado: ${url.href}`);
    };
    connection.onerror = () => { if (connection === socket) log("Error WebSocket"); };
}

byId("controls").addEventListener("submit", event => { event.preventDefault(); loadRegion(); });
byId("image").addEventListener("change", selectImage);
byId("cancel").addEventListener("click", () => {
    cancelCurrent();
    clearView();
    byId("status").textContent = "Solicitud cancelada y región liberada.";
});
byId("reconnect").addEventListener("click", connect);
byId("report-memory").addEventListener("click", () => {
    if (!send({ action: "memory_pressure", current_usage: Number(byId("pressure").value) * 1024, memory_limit: 100 * 1024 })) {
        log("No se pudo enviar: conexión cerrada");
    }
});
document.querySelectorAll("[data-dx]").forEach(button => button.addEventListener("click", () => {
    if (!metadata || !protocolReady) return;
    for (const axis of ["x", "y"]) {
        const field = byId(axis);
        field.value = Math.max(0, Math.min(Number(field.max), Number(field.value) + Number(button.dataset[`d${axis}`])));
    }
    loadRegion();
}));

async function start() {
    byId("connection").textContent = `Origen HTTP: ${location.origin}`;
    try {
        const response = await fetch("/api/images");
        log(`HTTP ${response.status} ${response.url}`);
        if (!response.ok) throw new Error(`Catálogo HTTP ${response.status}`);
        const images = await response.json();
        if (!images.length) throw new Error("No hay imágenes en el catálogo");
        for (const id of images) byId("image").add(new Option(id, id));
        await selectImage();
        connect();
    } catch (error) {
        byId("status").textContent = error.message;
    }
}
start();
