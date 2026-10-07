"use strict";

// Resources are budgeted by decoded pixels. Eviction uses spatial utility, never recency.
class PyramidView {
    constructor(canvas, changed) {
        this.canvas = canvas;
        this.context = canvas.getContext("2d");
        this.changed = changed;
        this.cache = new Map();
        this.visible = new Set();
        this.maxBytes = 32 * 1024 * 1024;
        this.ttlMs = 5000;
        this.bytes = 0;
        this.scale = 1;
        this.cx = this.cy = 0;
        this.image = null;
        this.generation = 0;
        this.drag = null;
        this.timer = null;
        this.drawFrame = null;
        this.peakBytes = 0;
        this.evictions = 0;
        this.fitMode = true;
        canvas.addEventListener("wheel", event => {
            if (!this.image) return;
            event.preventDefault();
            const rect = canvas.getBoundingClientRect();
            this.zoom(Math.exp(-event.deltaY * 0.002), event.clientX - rect.left, event.clientY - rect.top);
        }, { passive: false });
        canvas.addEventListener("pointerdown", event => {
            if (!this.image || this.drag || !event.isPrimary || event.button !== 0) return;
            canvas.setPointerCapture(event.pointerId);
            this.drag = { x: event.clientX, y: event.clientY, pointerId: event.pointerId };
        });
        canvas.addEventListener("pointermove", event => {
            if (!this.drag || this.drag.pointerId !== event.pointerId) return;
            this.cx -= (event.clientX - this.drag.x) / this.scale;
            this.cy -= (event.clientY - this.drag.y) / this.scale;
            this.drag = { x: event.clientX, y: event.clientY, pointerId: event.pointerId };
            this.clamp(); this.refresh();
        });
        for (const name of ["pointerup", "pointercancel", "lostpointercapture"]) {
            canvas.addEventListener(name, event => {
                if (this.drag?.pointerId !== event.pointerId) return;
                this.drag = null; this.flush();
            });
        }
        canvas.addEventListener("keydown", event => {
            if (!this.image || event.ctrlKey || event.metaKey || event.altKey) return;
            const step = 80 / this.scale;
            const arrows = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
            if (arrows[event.key]) {
                event.preventDefault();
                this.fitMode = false;
                this.cx += arrows[event.key][0]; this.cy += arrows[event.key][1];
                this.clamp(); this.refresh(); this.flush();
            } else if (["+", "=", "-", "0", "1"].includes(event.key)) {
                event.preventDefault();
                if (event.key === "0") this.fit();
                else if (event.key === "1") this.native();
                else this.zoom(event.key === "-" ? 0.5 : 2);
                this.flush();
            }
        });
        this.observer = new ResizeObserver(() => {
            if (!this.image) return;
            this.resize();
            if (this.fitMode) this.fit();
            else { this.clamp(); this.refresh(); }
        });
        this.observer.observe(canvas);
        this.sweeper = setInterval(() => this.sweep(), 500);
    }

    release() {
        this.generation++;
        clearTimeout(this.timer);
        cancelAnimationFrame(this.drawFrame);
        this.timer = this.drawFrame = null;
        this.drag = null;
        for (const entry of this.cache.values()) entry.bitmap.close();
        this.cache.clear(); this.visible.clear(); this.bytes = 0; this.image = null;
        this.peakBytes = this.evictions = 0;
        this.context.clearRect(0, 0, this.canvas.width, this.canvas.height);
    }

    open(image) {
        this.release(); this.image = image;
        this.cx = image.width / 2; this.cy = image.height / 2;
        this.resize(); this.fit();
    }

    resize() {
        this.dpr = Math.min(2, window.devicePixelRatio || 1);
        this.width = Math.max(1, this.canvas.clientWidth);
        this.height = Math.max(1, this.canvas.clientHeight);
        this.canvas.width = Math.round(this.width * this.dpr);
        this.canvas.height = Math.round(this.height * this.dpr);
    }

    get backupId() { return this.image ? `${this.image.imageId}:0:0:0:3` : null; }
    protects(id) { return this.visible.has(id) || id === this.backupId; }

    fit() {
        if (!this.image) return;
        this.fitMode = true;
        this.scale = Math.min(this.width / this.image.width, this.height / this.image.height, 1);
        this.cx = this.image.width / 2; this.cy = this.image.height / 2;
        this.refresh();
    }

    native() { if (this.image) { this.fitMode = false; this.scale = 1; this.clamp(); this.refresh(); } }

    zoom(factor, px = this.width / 2, py = this.height / 2) {
        if (!this.image) return;
        this.fitMode = false;
        const x = this.cx + (px - this.width / 2) / this.scale;
        const y = this.cy + (py - this.height / 2) / this.scale;
        const minimum = Math.min(this.width / this.image.width, this.height / this.image.height, 1);
        this.scale = Math.max(minimum, Math.min(8, this.scale * factor));
        this.cx = x - (px - this.width / 2) / this.scale;
        this.cy = y - (py - this.height / 2) / this.scale;
        this.clamp(); this.refresh();
    }

    clamp() {
        const halfW = this.width / this.scale / 2, halfH = this.height / this.scale / 2;
        this.cx = halfW * 2 >= this.image.width ? this.image.width / 2
            : Math.max(halfW, Math.min(this.image.width - halfW, this.cx));
        this.cy = halfH * 2 >= this.image.height ? this.image.height / 2
            : Math.max(halfH, Math.min(this.image.height - halfH, this.cy));
    }

    tiles() {
        if (!this.image) return [];
        this.z = Math.max(0, Math.min(this.image.maxZoom,
            this.image.maxZoom + Math.ceil(Math.log2(this.scale * this.dpr))));
        const level = this.image.levels.find(level => level.z === this.z);
        const factor = 2 ** (this.image.maxZoom - this.z);
        const size = this.image.tileSize;
        const x0 = Math.max(0, Math.floor((this.cx - this.width / this.scale / 2) / factor / size));
        const y0 = Math.max(0, Math.floor((this.cy - this.height / this.scale / 2) / factor / size));
        const x1 = Math.min(level.columns - 1, Math.ceil((this.cx + this.width / this.scale / 2) / factor / size) - 1);
        const y1 = Math.min(level.rows - 1, Math.ceil((this.cy + this.height / this.scale / 2) / factor / size) - 1);
        const tiles = [];
        for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
            tiles.push({ x, y, z: this.z, q: 3, id: `${this.image.imageId}:${this.z}:${x}:${y}:3` });
        }
        this.visible = new Set(tiles.map(tile => tile.id));
        const now = performance.now();
        for (const [id, entry] of this.cache) {
            if (this.protects(id)) entry.outsideSince = null;
            else if (entry.outsideSince == null) entry.outsideSince = now;
        }
        return tiles;
    }

    refresh() {
        this.tiles(); this.requestDraw(); this.progress();
        // Throttle the latest view rather than waiting indefinitely for a gesture to stop.
        if (this.timer == null) this.timer = setTimeout(() => this.flush(), 120);
    }

    flush() {
        clearTimeout(this.timer); this.timer = null;
        if (this.image) this.changed();
    }

    requestDraw() {
        if (this.drawFrame != null) return;
        this.drawFrame = requestAnimationFrame(() => { this.drawFrame = null; this.draw(); });
    }

    missing() {
        this.tiles();
        if (this.image && !this.cache.has(this.backupId)) return [{ x: 0, y: 0, z: 0, q: 3 }];
        return this.tiles().filter(tile => !this.cache.has(tile.id)).slice(0, 128)
            .map(({ x, y, z, q }) => ({ x, y, z, q }));
    }

    async consume(message) {
        if (this.cache.has(message.tile_id)) return;
        const generation = this.generation;
        if (!this.image || message.imageId !== this.image.imageId || message.q !== 3 ||
            message.compression !== "png" || message.size_bytes > 2 * 1024 * 1024) throw new Error("Tile multinivel inválido");
        const raw = atob(message.data);
        if (raw.length !== message.size_bytes) throw new Error("Longitud de tile incorrecta");
        const bytes = Uint8Array.from(raw, c => c.charCodeAt(0));
        const bitmap = await createImageBitmap(new Blob([bytes], { type: "image/png" }));
        if (generation !== this.generation || !this.protects(message.tile_id)) { bitmap.close(); return; }
        const level = this.image.levels.find(level => level.z === message.z);
        const width = level && Math.min(this.image.tileSize, level.width - message.x * this.image.tileSize);
        const height = level && Math.min(this.image.tileSize, level.height - message.y * this.image.tileSize);
        if (bitmap.width !== width || bitmap.height !== height) { bitmap.close(); throw new Error("Dimensiones del tile incorrectas"); }
        const size = bitmap.width * bitmap.height * 4;
        const previous = this.cache.get(message.tile_id);
        if (previous) { bitmap.close(); return; }
        const entry = { bitmap, size, x: message.x, y: message.y, z: message.z, outsideSince: null };
        this.makeRoom(size);
        if (this.bytes + size > this.maxBytes) { bitmap.close(); throw new Error("Presupuesto de bitmaps insuficiente"); }
        this.cache.set(message.tile_id, entry); this.bytes += size;
        this.peakBytes = Math.max(this.peakBytes, this.bytes);
        this.sweep(); this.requestDraw(); this.progress();
    }

    sweep() {
        const now = performance.now();
        for (const [id, entry] of this.cache) {
            if (!this.protects(id) && entry.outsideSince != null && now - entry.outsideSince >= this.ttlMs) this.evict(id);
        }
        this.makeRoom(0);
        const label = document.getElementById("render-memory");
        if (label) label.textContent = `${this.cache.size} bitmaps · ${(this.bytes / 1048576).toFixed(2)} / ${(this.maxBytes / 1048576).toFixed(0)} MiB · TTL ${this.ttlMs / 1000} s · respaldo ${this.cache.has(this.backupId) ? "disponible" : "pendiente"}`;
    }

    makeRoom(required) {
        if (this.bytes + required > this.maxBytes) {
            const victims = [...this.cache].filter(([id]) => !this.protects(id));
            victims.sort((a, b) => this.distance(b[1]) - this.distance(a[1]));
            for (const [id] of victims) { if (this.bytes + required <= this.maxBytes) break; this.evict(id); }
        }
    }

    progress(note = "") {
        const loaded = [...this.visible].filter(id => this.cache.has(id)).length;
        const total = this.visible.size;
        const progress = document.getElementById("render-progress");
        if (progress) { progress.max = Math.max(1, total); progress.value = loaded; }
        const label = document.getElementById("render-progress-label");
        if (label) label.textContent = note || (loaded === total && total > 0
            ? `Vista completa · ${total} tiles en resolución del nivel ${this.z}`
            : `${loaded} / ${total} tiles visibles · ${this.cache.has(this.backupId) ? "respaldo activo" : "cargando vista general"}`);
        this.canvas.setAttribute("aria-busy", String(total > loaded));
        return { loaded, total, backupReady: this.cache.has(this.backupId) };
    }

    distance(entry) {
        const factor = 2 ** (this.image.maxZoom - entry.z);
        return Math.hypot((entry.x + 0.5) * this.image.tileSize * factor - this.cx,
            (entry.y + 0.5) * this.image.tileSize * factor - this.cy);
    }

    evict(id) {
        const entry = this.cache.get(id);
        entry.bitmap.close(); this.bytes -= entry.size; this.cache.delete(id);
        this.evictions++;
    }

    draw() {
        if (!this.image) return;
        const ctx = this.context;
        ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
        ctx.fillStyle = "#d5ddeb"; ctx.fillRect(0, 0, this.width, this.height);
        // Coarse levels first, current exact level last: progressive replacement on one canvas.
        const entries = [...this.cache.values()].filter(entry => entry.z <= this.z).sort((a, b) => a.z - b.z);
        ctx.imageSmoothingEnabled = this.scale < 1;
        ctx.save(); ctx.beginPath();
        ctx.rect(this.width / 2 - this.cx * this.scale, this.height / 2 - this.cy * this.scale,
            this.image.width * this.scale, this.image.height * this.scale);
        ctx.clip();
        for (const entry of entries) {
            const factor = 2 ** (this.image.maxZoom - entry.z);
            const x = (entry.x * this.image.tileSize * factor - this.cx) * this.scale + this.width / 2;
            const y = (entry.y * this.image.tileSize * factor - this.cy) * this.scale + this.height / 2;
            ctx.drawImage(entry.bitmap, x, y, entry.bitmap.width * factor * this.scale, entry.bitmap.height * factor * this.scale);
        }
        ctx.restore();
        document.getElementById("render-scale").textContent = `${(this.scale * 100).toFixed(2)}%; nivel ${this.z}/${this.image.maxZoom}; PNG sin pérdida`;
    }
}
