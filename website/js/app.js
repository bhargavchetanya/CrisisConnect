/* =========================================================
   CrisisConnect website logic
   - LIVE mode : talks to the Java REST API (com.crisisconnect.api.ApiServer)
   - DEMO mode : no server found -> runs the same algorithm in JavaScript
   ========================================================= */
(() => {
  "use strict";

  const $ = s => document.querySelector(s);
  const fmt = n => Number(n).toLocaleString("en-IN");
  const LABEL = { FOOD: "Food & Water", MEDICAL: "Medical Supplies", SHELTER: "Shelter Beds", VEHICLE: "Rescue Vehicles", VOLUNTEER: "Volunteers" };
  const UNIT = { FOOD: "packs", MEDICAL: "units", SHELTER: "beds", VEHICLE: "vehicles", VOLUNTEER: "volunteers" };
  const WEIGHT = { CRITICAL: 100, HIGH: 70, MEDIUM: 40, LOW: 15 };
  const COLOR = { CRITICAL: "#ff4757", HIGH: "#ff8c1a", MEDIUM: "#f5c518", LOW: "#2ed573" };
  const MAX_RADIUS_KM = 180;

  let mode = "demo";
  let base = "";
  let knownRequestIds = new Set();
  let firstLoad = true;

  // ------------------------------------------------------------------ shared maths (same as Java)
  const hoursSince = iso => Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 3600000));
  function priority(r) {
    let s = WEIGHT[r.urgency] + 10 * Math.log10((r.peopleAffected || 0) + 1);
    s += Math.min(1.5 * hoursSince(r.createdAt || new Date().toISOString()), 30);
    if (r.vulnerable) s += 15;
    return Math.round(s * 10) / 10;
  }
  function haversine(a, b) {
    const R = 6371, rad = Math.PI / 180;
    const dLat = (b.lat - a.lat) * rad, dLon = (b.lon - a.lon) * rad;
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2;
    return Math.round(R * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h)) * 10) / 10;
  }
  const daysToExpiry = r => r.expiry ? Math.floor((new Date(r.expiry) - Date.now()) / 86400000) : Infinity;
  function matchCost(res, target) {          // polymorphic cost - mirrors the Resource subclasses
    const d = haversine(res, target);
    switch (res.type) {
      case "FOOD": return daysToExpiry(res) <= 3 ? Math.max(0, d - 25) : d;
      case "MEDICAL": return Math.max(0, d * 1.2 - (daysToExpiry(res) <= 7 ? 15 : 0));
      case "SHELTER": return d * 1.5;
      case "VEHICLE": return d * 0.8;
      default: return d;
    }
  }
  const remaining = r => Math.max(0, r.quantityNeeded - r.quantityFulfilled);
  const isActive = r => r.status === "OPEN" || r.status === "PARTIALLY_FULFILLED";

  // ------------------------------------------------------------------ demo engine (in-browser)
  const demo = {
    stats() {
      const D = window.DEMO, act = D.requests.filter(isActive);
      const supply = {}, demand = {}, urgency = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 };
      Object.keys(LABEL).forEach(t => { supply[t] = 0; demand[t] = 0; });
      D.resources.forEach(r => supply[r.type] += r.available);
      act.forEach(r => { demand[r.resourceType] += remaining(r); urgency[r.urgency]++; });
      const fulfilled = D.requests.filter(r => r.status === "FULFILLED").length;
      return {
        totalRequests: D.requests.length, activeRequests: act.length, criticalActive: urgency.CRITICAL,
        fulfilledRequests: fulfilled, fulfilmentRate: D.requests.length ? Math.round(fulfilled * 1000 / D.requests.length) / 10 : 0,
        resources: D.resources.length, unitsAvailable: D.resources.reduce((s, r) => s + r.available, 0),
        organizations: D.orgs.length, allocations: D.allocations.length,
        unitsDispatched: D.allocations.reduce((s, a) => s + a.quantity, 0),
        peopleAffected: act.reduce((s, r) => s + r.peopleAffected, 0), supply, demand, urgency
      };
    },
    queue() {
      return window.DEMO.requests.filter(isActive)
        .map(r => ({ ...r, priority: priority(r) }))
        .sort((a, b) => b.priority - a.priority || a.createdAt.localeCompare(b.createdAt));
    },
    createRequest(f) {
      const D = window.DEMO, errors = [];
      if (!f.title.trim()) errors.push("Title is required");
      if (!(+f.quantity > 0)) errors.push("Quantity needed must be greater than 0");
      if (!(+f.people > 0)) errors.push("People affected must be greater than 0");
      if (!D.cities[f.city]) errors.push("City must be selected");
      if (errors.length) return { ok: false, errors };
      const c = D.cities[f.city];
      const r = {
        id: Math.max(0, ...D.requests.map(x => x.id)) + 1, title: f.title.trim(), disasterType: f.disasterType,
        resourceType: f.resourceType, quantityNeeded: +f.quantity, quantityFulfilled: 0, peopleAffected: +f.people,
        urgency: f.urgency, city: f.city, lat: c[0], lon: c[1], status: "OPEN", vulnerable: !!f.vulnerable,
        source: "WEB", createdAt: new Date().toISOString().slice(0, 19)
      };
      r.priority = priority(r);
      D.requests.push(r);
      D.events.unshift({ type: "REQUEST_CREATED", message: `[${r.urgency}] ${r.title} - ${r.city} (via WEB)`, time: r.createdAt });
      return { ok: true, data: r };
    },
    match() {   // PriorityQueue -> candidates -> sort by cost -> greedy
      const D = window.DEMO, t0 = performance.now(), log = [];
      const queue = this.queue();
      let allocs = 0, units = 0, full = 0, partial = 0, unmet = 0, rank = 1;
      for (const q of queue) {
        const req = D.requests.find(r => r.id === q.id);
        let need = remaining(req);
        const head = `#${rank++}  [${req.urgency} | score ${q.priority.toFixed(1)}] ${req.title} (${req.city})`;
        const cands = D.resources.filter(r => r.type === req.resourceType && r.available > 0 && haversine(r, req) <= MAX_RADIUS_KM)
          .sort((a, b) => matchCost(a, req) - matchCost(b, req) || b.available - a.available);
        if (!cands.length) { unmet++; log.push(`${head}\n      x no matching ${LABEL[req.resourceType]} within ${MAX_RADIUS_KM} km`); continue; }
        const lines = [head];
        for (const res of cands) {
          if (!need) break;
          const take = Math.min(need, res.available);
          res.available -= take; need -= take; req.quantityFulfilled += take; units += take; allocs++;
          const dist = haversine(res, req);
          D.allocations.unshift({
            id: D.allocations.length + 1, requestId: req.id, resourceId: res.id, request: req.title, resource: res.name,
            organization: res.organization, quantity: take, distanceKm: dist, etaMinutes: Math.ceil(dist / 40 * 60), city: req.city
          });
          lines.push(`      -> ${take} ${UNIT[res.type]} from ${res.name}, ${res.city} (${dist.toFixed(1)} km)`);
        }
        req.status = remaining(req) === 0 ? "FULFILLED" : "PARTIALLY_FULFILLED";
        if (req.status === "FULFILLED") { full++; lines.push("      => FULFILLED"); }
        else { partial++; lines.push(`      ~ PARTIAL - still short of ${remaining(req)}`); }
        log.push(lines.join("\n"));
      }
      const headline = `${allocs} dispatches | ${units} units | ${full} fulfilled | ${partial} partial | ${unmet} unmet`;
      D.events.unshift({ type: "MATCH_COMPLETED", message: headline, time: new Date().toISOString() });
      return { headline, log, durationMs: Math.round(performance.now() - t0), unmet };
    }
  };

  // ------------------------------------------------------------------ data access (live or demo)
  async function getJSON(path) {
    const res = await fetch(base + path, { cache: "no-store" });
    if (!res.ok) throw new Error(res.status);
    return res.json();
  }
  const api = {
    stats: () => mode === "live" ? getJSON("/api/stats") : Promise.resolve(demo.stats()),
    queue: () => mode === "live" ? getJSON("/api/queue") : Promise.resolve(demo.queue()),
    requests: () => mode === "live" ? getJSON("/api/requests")
      : Promise.resolve(window.DEMO.requests.map(r => ({ ...r, priority: priority(r) }))),
    resources: () => mode === "live" ? getJSON("/api/resources") : Promise.resolve(window.DEMO.resources),
    allocations: () => mode === "live" ? getJSON("/api/allocations") : Promise.resolve(window.DEMO.allocations),
    events: () => mode === "live" ? getJSON("/api/events") : Promise.resolve(window.DEMO.events),
    meta: () => mode === "live" ? getJSON("/api/meta") : Promise.resolve(window.DEMO.meta),
    async createRequest(form) {
      if (mode !== "live") return demo.createRequest(form);
      const body = new URLSearchParams(form);
      const res = await fetch(base + "/api/requests", { method: "POST", body, headers: { "Content-Type": "application/x-www-form-urlencoded" } });
      const json = await res.json();
      return res.ok ? { ok: true, data: json } : { ok: false, errors: json.errors || ["Server error"] };
    },
    async match() {
      if (mode !== "live") return demo.match();
      return window.CC.api(base + "/api/match", { method: "POST" });     // needs a coordinator session
    }
  };

  async function detectServer() {
    const candidates = location.protocol.startsWith("http") ? ["", "http://localhost:8080"] : ["http://localhost:8080"];
    for (const c of candidates) {
      try {
        const ctl = new AbortController();
        const timer = setTimeout(() => ctl.abort(), 1500);
        const r = await fetch(c + "/api/stats", { signal: ctl.signal, cache: "no-store" });
        clearTimeout(timer);
        if (r.ok) { base = c; mode = "live"; break; }
      } catch (_) { /* try next */ }
    }
    const badge = $("#connBadge");
    badge.classList.add(mode);
    $("#connText").textContent = mode === "live" ? "LIVE · Java server" : "DEMO MODE";
    badge.title = mode === "live" ? "Connected to the CrisisConnect REST API"
      : "Java server not found - running the matching engine in your browser with sample data";
  }

  // ------------------------------------------------------------------ rendering
  function animateCount(el, to) {
    const from = +el.dataset.v || 0, start = performance.now(), dur = 900;
    el.dataset.v = to;
    const step = t => {
      const k = Math.min(1, (t - start) / dur), e = 1 - Math.pow(1 - k, 3);
      el.textContent = fmt(Math.round(from + (to - from) * e));
      if (k < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  function renderStats(s) {
    document.querySelectorAll("[data-count]").forEach(el => animateCount(el, s[el.dataset.count] || 0));
    animateCount($("#kActive"), s.activeRequests);
    animateCount($("#kCritical"), s.criticalActive);
    animateCount($("#kPeople"), s.peopleAffected);
    animateCount($("#kUnits"), s.unitsAvailable);
    animateCount($("#kDispatched"), s.unitsDispatched);
    $("#kRate").textContent = Math.round(s.fulfilmentRate) + "%";

    const max = Math.max(1, ...Object.values(s.supply), ...Object.values(s.demand));
    $("#gap").innerHTML = Object.keys(LABEL).map(t => {
      const sup = s.supply[t], dem = s.demand[t], gap = sup - dem;
      return `<div class="gap-row">
        <div class="lbl"><span>${LABEL[t]}</span>
          <span>${fmt(sup)} / ${fmt(dem)} · ${gap >= 0 ? `<span class="ok">OK</span>` : `<span class="short">SHORT ${fmt(-gap)}</span>`}</span></div>
        <div class="gap-bars"><div class="sup" style="width:${sup / max * 100}%"></div><div class="dem" style="width:${dem / max * 100}%"></div></div>
      </div>`;
    }).join("") + `<div class="muted small"><span style="color:#1fd1c1">■</span> supply available &nbsp; <span style="color:#ff4757">■</span> demand still needed</div>`;
  }

  function renderQueue(q) {
    const list = $("#queue");
    if (!q.length) { list.innerHTML = `<li><span></span><span class="t">All clear - no active requests 🎉</span><span></span></li>`; return; }
    list.innerHTML = q.slice(0, 12).map((r, i) => {
      const pct = r.quantityNeeded ? r.quantityFulfilled / r.quantityNeeded * 100 : 0;
      const isNew = !firstLoad && !knownRequestIds.has(r.id);
      return `<li class="${isNew ? "new" : ""}" style="animation-delay:${i * 30}ms">
        <span class="rank">#${i + 1}</span>
        <div><div class="t"><span class="badge b-${r.urgency}">${r.urgency}</span>${escapeHtml(r.title)}</div>
          <div class="m">${r.city} · needs ${fmt(remaining(r))} ${UNIT[r.resourceType]} · ${fmt(r.peopleAffected)} people${r.vulnerable ? " · vulnerable" : ""}</div></div>
        <div class="s"><b>${Number(r.priority).toFixed(1)}</b><div class="bar"><i style="width:${pct}%"></i></div></div>
      </li>`;
    }).join("");
    q.forEach(r => knownRequestIds.add(r.id));
  }

  // ---- SVG map (equirectangular projection of the Punjab / Haryana region)
  const B = { minLat: 29.9, maxLat: 31.85, minLon: 74.6, maxLon: 77.45 }, W = 640, H = 520, P = 34;
  const px = lon => P + (lon - B.minLon) / (B.maxLon - B.minLon) * (W - 2 * P);
  const py = lat => P + (B.maxLat - lat) / (B.maxLat - B.minLat) * (H - 2 * P);
  const jitter = (id, k) => [Math.cos(id * 2.4 + k) * (6 + (id % 3) * 5), Math.sin(id * 2.4 + k) * (6 + (id % 3) * 5)];

  // ---- Real map (Leaflet + OpenStreetMap/CARTO dark tiles). Falls back to the SVG sketch if Leaflet is missing.
  let lmap = null, lLayer = null, fitted = false;
  const jit = (id, k) => [Math.cos(id * 2.4 + k) * 0.035 * (1 + id % 3), Math.sin(id * 2.4 + k) * 0.035 * (1 + id % 3)];

  function renderMap(requests, resources, allocations) {
    if (!window.L) return renderSvgMap(requests, resources, allocations);
    if (!lmap) {
      lmap = L.map("map", { scrollWheelZoom: false, attributionControl: true }).setView([30.5, 76.2], 8);
      lmap.attributionControl.setPrefix('<a href="https://leafletjs.com">Leaflet</a>');
      // Free tile servers - no API key needed (OpenStreetMap + Esri imagery)
      const osmOpts = { attribution: "&copy; OpenStreetMap contributors", maxZoom: 19 };
      const dark = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { ...osmOpts, className: "dark-tiles" });
      const streets = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", osmOpts);
      const satellite = L.tileLayer("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        { attribution: "Imagery &copy; Esri", maxZoom: 18 });
      dark.addTo(lmap);
      L.control.layers({ "Dark": dark, "Streets": streets, "Satellite": satellite }, null, { position: "topright" }).addTo(lmap);
      let warned = false;
      [dark, streets, satellite].forEach(t => t.on("tileerror", () => {
        if (warned) return;
        warned = true;
        $("#mapNote").textContent = "Map background needs an internet connection — markers and routes still work.";
      }));
      lLayer = L.layerGroup().addTo(lmap);
      setTimeout(() => lmap.invalidateSize(), 200);
    }
    lLayer.clearLayers();
    const resPos = {}, reqPos = {}, all = [];
    resources.forEach(r => { const [a, b] = jit(r.id, 1); resPos[r.id] = [r.lat + a, r.lon + b]; all.push(resPos[r.id]); });
    requests.forEach(r => { const [a, b] = jit(r.id, 0); reqPos[r.id] = [r.lat + a, r.lon + b]; all.push(reqPos[r.id]); });

    allocations.slice(0, 60).forEach(a => {
      const from = resPos[a.resourceId], to = reqPos[a.requestId];
      if (!from || !to) return;
      L.polyline([from, to], { color: "#ff8c1a", weight: 2.2, opacity: .85, dashArray: "6 6", className: "route" })
        .bindTooltip(`${a.quantity} × ${escapeHtml(a.resource)} → ${escapeHtml(a.request)} (${Number(a.distanceKm).toFixed(1)} km)`, { sticky: true })
        .addTo(lLayer);
    });
    resources.forEach(r => {
      const s = Math.round(10 + Math.min(10, Math.sqrt(r.available) / 2));
      L.marker(resPos[r.id], {
        icon: L.divIcon({ className: "", html: `<div class="mk-res${r.available ? "" : " empty"}" style="width:${s}px;height:${s}px"></div>`, iconSize: [s, s] })
      }).bindPopup(`<b>${escapeHtml(r.name)}</b><br>${escapeHtml(r.organization)}<br>${fmt(r.available)} / ${fmt(r.capacity)} ${UNIT[r.type]} · ${r.city}`)
        .addTo(lLayer);
    });
    requests.filter(r => !isActive(r)).forEach(r => {
      L.circleMarker(reqPos[r.id], { radius: 6, color: r.status === "FULFILLED" ? "#2ed573" : "#59607a", weight: 2, fillOpacity: .15 })
        .bindPopup(`<b>${escapeHtml(r.title)}</b><br>${r.status.replace("_", " ")} · ${r.city}`).addTo(lLayer);
    });
    requests.filter(isActive).forEach(r => {
      const s = Math.round(12 + Math.min(14, r.priority / 10));
      L.marker(reqPos[r.id], {
        zIndexOffset: 1000,
        icon: L.divIcon({ className: "", html: `<div class="mk-req${r.urgency === "CRITICAL" ? " crit" : ""}" style="--c:${COLOR[r.urgency]};width:${s}px;height:${s}px"></div>`, iconSize: [s, s] })
      }).bindPopup(`<span class="badge b-${r.urgency}">${r.urgency}</span><b>${escapeHtml(r.title)}</b><br>
          ${r.city} · needs ${fmt(remaining(r))} ${UNIT[r.resourceType]} · ${fmt(r.peopleAffected)} people<br>priority <b>${Number(r.priority).toFixed(1)}</b>`)
        .addTo(lLayer);
    });
    if (!fitted && all.length) { lmap.fitBounds(all, { padding: [30, 30] }); fitted = true; }
  }

  function renderSvgMap(requests, resources, allocations) {
    let svg = $("#map svg");
    if (!svg) { $("#map").innerHTML = `<svg viewBox="0 0 640 520"></svg>`; svg = $("#map svg"); }
    const cities = window.DEMO.cities;
    let g = `<defs><radialGradient id="glow"><stop offset="0" stop-color="#ff4757" stop-opacity=".5"/><stop offset="1" stop-color="#ff4757" stop-opacity="0"/></radialGradient></defs>`;
    for (let i = 1; i < 8; i++) g += `<line x1="${i * 80}" y1="0" x2="${i * 80}" y2="${H}" stroke="#1a2344" />`;
    for (let i = 1; i < 7; i++) g += `<line x1="0" y1="${i * 80}" x2="${W}" y2="${i * 80}" stroke="#1a2344" />`;
    for (const [name, [lat, lon]] of Object.entries(cities)) {
      if (lat < B.minLat || lat > B.maxLat || lon < B.minLon || lon > B.maxLon) continue;
      g += `<circle cx="${px(lon)}" cy="${py(lat)}" r="2.5" fill="#3b4772"/><text class="city-label" x="${px(lon) + 7}" y="${py(lat) - 7}">${name}</text>`;
    }
    const resPos = {}, reqPos = {};
    resources.forEach(r => { const [dx, dy] = jitter(r.id, 1); resPos[r.id] = [px(r.lon) + dx, py(r.lat) + dy]; });
    requests.forEach(r => { const [dx, dy] = jitter(r.id, 0); reqPos[r.id] = [px(r.lon) + dx, py(r.lat) + dy]; });

    allocations.slice(0, 40).forEach(a => {
      const from = resPos[a.resourceId], to = reqPos[a.requestId];
      if (from && to) g += `<line class="route" x1="${from[0]}" y1="${from[1]}" x2="${to[0]}" y2="${to[1]}" stroke="#ff8c1a" stroke-width="1.6" opacity=".8"/>`;
    });
    resources.forEach(r => {
      const [x, y] = resPos[r.id], s = 3 + Math.min(6, Math.sqrt(r.available) / 3);
      const op = r.available > 0 ? 0.95 : 0.25;
      g += `<rect x="${x - s}" y="${y - s}" width="${2 * s}" height="${2 * s}" transform="rotate(45 ${x} ${y})" fill="#1fd1c1" opacity="${op}"><title>${escapeHtml(r.name)} — ${r.available}/${r.capacity} ${UNIT[r.type]} (${r.city})</title></rect>`;
    });
    requests.filter(r => !isActive(r)).forEach(r => {
      const [x, y] = reqPos[r.id];
      const c = r.status === "FULFILLED" ? "#2ed573" : "#59607a";
      g += `<circle cx="${x}" cy="${y}" r="5" fill="none" stroke="${c}" stroke-width="2"><title>${escapeHtml(r.title)} — ${r.status}</title></circle>`;
    });
    requests.filter(isActive).forEach(r => {
      const [x, y] = reqPos[r.id], rad = 4 + Math.min(9, r.priority / 18), c = COLOR[r.urgency];
      if (r.urgency === "CRITICAL") g += `<circle class="pulse-ring" cx="${x}" cy="${y}" r="${rad}" fill="none" stroke="${c}" stroke-width="2"/>`;
      g += `<circle cx="${x}" cy="${y}" r="${rad * 2.2}" fill="url(#glow)" opacity=".5"/>`;
      g += `<circle cx="${x}" cy="${y}" r="${rad}" fill="${c}" stroke="#0b1020" stroke-width="1.5"><title>${escapeHtml(r.title)} — priority ${Number(r.priority).toFixed(1)}</title></circle>`;
    });
    svg.innerHTML = g;
  }

  function renderDispatches(list) {
    $("#dispCount").textContent = list.length ? `${list.length} dispatches` : "";
    const body = $("#dispatchTable tbody");
    if (!list.length) { body.innerHTML = `<tr><td colspan="7" class="muted center">No dispatches yet — run the Smart Match engine</td></tr>`; return; }
    body.innerHTML = list.slice(0, 25).map(a => `<tr>
      <td>${a.id}</td><td>${escapeHtml(a.request)}</td><td>${escapeHtml(a.resource)}</td><td>${escapeHtml(a.organization)}</td>
      <td><b>${a.quantity}</b></td><td>${Number(a.distanceKm).toFixed(1)} km</td><td>~${a.etaMinutes} min</td></tr>`).join("");
  }

  function renderTicker(events, queue) {
    let items = events.slice(0, 10).map(e => `<span><b>●</b>${escapeHtml(e.message)}</span>`);
    if (items.length < 4) items = items.concat(queue.slice(0, 6).map(r => `<span><b>●</b>[${r.urgency}] ${escapeHtml(r.title)} — ${r.city}</span>`));
    $("#ticker").innerHTML = items.join("");
  }

  function escapeHtml(s) {
    return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  function toast(title, msg, kind = "") {
    const t = document.createElement("div");
    t.className = "toast " + kind;
    t.innerHTML = `<b>${escapeHtml(title)}</b><span>${escapeHtml(msg)}</span>`;
    $("#toasts").appendChild(t);
    setTimeout(() => { t.classList.add("out"); setTimeout(() => t.remove(), 400); }, 3800);
  }

  async function refresh() {
    try {
      const [stats, queue, requests, resources, allocations, events] = await Promise.all(
        [api.stats(), api.queue(), api.requests(), api.resources(), api.allocations(), api.events()]);
      renderStats(stats);
      renderQueue(queue);
      renderMap(requests, resources, allocations);
      renderDispatches(allocations);
      if (firstLoad) renderTicker(events, queue);
      firstLoad = false;
    } catch (e) {
      console.warn("refresh failed", e);
    }
  }

  // ------------------------------------------------------------------ smart match
  async function runMatch() {
    const btn = $("#matchBtn"), out = $("#console");
    if (mode === "live" && !currentUser) {          // public visitors: Smart Match is for coordinators
      location.href = "/login.html?next=" + encodeURIComponent("/#live");
      return;
    }
    btn.disabled = true;
    out.textContent = "> running smart match...\n";
    try {
      const rep = await api.match();
      // type the log out line by line for effect
      const lines = rep.log.join("\n").split("\n");
      for (const line of lines) {
        out.textContent += line + "\n";
        out.scrollTop = out.scrollHeight;
        await new Promise(r => setTimeout(r, 35));
      }
      out.textContent += `> DONE in ${rep.durationMs} ms — ${rep.headline}\n`;
      out.scrollTop = out.scrollHeight;
      toast("Smart match complete", rep.headline, rep.unmet > 0 ? "warn" : "");
      await refresh();
    } catch (e) {
      out.textContent += "> ERROR: " + e.message;
      toast("Matching failed", e.message, "err");
      if (e.status === 401) setTimeout(() => location.href = "/login.html?next=" + encodeURIComponent("/#live"), 900);
    } finally {
      btn.disabled = false;
    }
  }

  // ------------------------------------------------------------------ SOS form
  async function setupForm() {
    const meta = await api.meta();
    const opt = (v, l) => `<option value="${v}">${l}</option>`;
    $("#fDisaster").innerHTML = meta.disasterTypes.map(d => opt(d, d.replace("_", " ").toLowerCase().replace(/^\w/, c => c.toUpperCase()))).join("");
    $("#fResource").innerHTML = meta.resourceTypes.map(t => opt(t, LABEL[t])).join("");
    $("#fCity").innerHTML = `<option value="">Select city</option>` + meta.cities.map(c => opt(c, c)).join("");

    const form = $("#sosForm");
    const preview = () => {
      const f = Object.fromEntries(new FormData(form));
      const score = priority({ urgency: f.urgency, peopleAffected: +f.people || 0, vulnerable: !!f.vulnerable });
      $("#scorePreview").textContent = score.toFixed(1);
    };
    form.addEventListener("input", preview);
    preview();

    form.addEventListener("submit", async e => {
      e.preventDefault();
      const f = Object.fromEntries(new FormData(form));
      // client-side validation first (server validates again)
      const errs = [];
      form.querySelectorAll("input[required]").forEach(i => i.classList.toggle("invalid", !i.value.trim()));
      if (!f.title.trim()) errs.push("Please describe the emergency");
      if (!(+f.quantity > 0)) errs.push("Quantity must be at least 1");
      if (!(+f.people > 0)) errs.push("People affected must be at least 1");
      if (!f.city) errs.push("Please choose a city");
      const box = $("#formErrors");
      if (errs.length) { box.innerHTML = errs.map(escapeHtml).join("<br>"); box.hidden = false; return; }
      const result = await api.createRequest(f);
      if (!result.ok) { box.innerHTML = result.errors.map(escapeHtml).join("<br>"); box.hidden = false; return; }
      box.hidden = true;
      toast("SOS received", `${result.data.title} — priority ${Number(result.data.priority).toFixed(1)}`);
      form.reset();
      preview();
      showTracking(result.data);
      await refresh();
    });
  }

  // ------------------------------------------------------------------ tracking card after an SOS
  function showTracking(r) {
    const done = $("#sosDone"), form = $("#sosForm");
    if (!r.trackingCode) {                       // demo mode (no Java server): no tracking IDs
      document.querySelector("#live").scrollIntoView({ behavior: "smooth" });
      return;
    }
    const url = "/track.html?code=" + encodeURIComponent(r.trackingCode);
    done.innerHTML = `<span class="ic">✅</span>
      <div><div class="muted">SOS received — your tracking ID</div>
        <span class="code-big">${escapeHtml(r.trackingCode)}</span>
        <div class="muted small">Priority score ${Number(r.priority).toFixed(1)} · ${escapeHtml(r.city)}</div></div>
      ${r.verification === "UNVERIFIED"
        ? `<div class="notice warn">🏛️ A government officer of ${escapeHtml(r.city)} will check this SOS${r.smsSent ? " and may call you on the number you gave" : ""}. Help is dispatched as soon as it is verified.</div>`
        : `<div class="notice ok">✅ Verified — this SOS is in the dispatch queue.</div>`}
      ${r.smsSent ? "" : `<div class="notice warn">No phone number given — note this ID down to follow your request.</div>`}
      <div class="cta" style="margin:0;justify-content:center">
        <a class="btn btn-red" href="${url}">🔎 Track this SOS</a>
        <button class="btn btn-ghost" type="button" id="copyCode">Copy ID</button>
        <button class="btn btn-ghost" type="button" id="newSos">Raise another</button>
      </div>`;
    form.hidden = true;
    done.hidden = false;
    $("#copyCode").onclick = () => navigator.clipboard?.writeText(r.trackingCode).then(() => toast("Copied", r.trackingCode));
    $("#newSos").onclick = () => { done.hidden = true; form.hidden = false; };
    done.scrollIntoView({ behavior: "smooth", block: "center" });
  }

  // ------------------------------------------------------------------ session (login state)
  let currentUser = null;
  async function setupSession() {
    if (mode !== "live") { $("#authSlot").innerHTML = ""; return; }
    currentUser = await window.CC.renderAuthSlot($("#authSlot"));
    const btn = $("#matchBtn");
    if (!currentUser) {
      btn.textContent = "🔒 Coordinator login to run";
      btn.title = "Smart Match dispatches real stock, so only logged-in coordinators can run it";
      $("#console").textContent = "> Smart Match is restricted to logged-in coordinators.\n> Public visitors can watch live operations, raise an SOS and track it.";
    } else {
      $("#console").textContent = `> Logged in as ${currentUser.name} (${currentUser.roleLabel}). Press "Run Smart Match" to dispatch resources.`;
    }
  }

  // ------------------------------------------------------------------ boot
  async function boot() {
    $("#burger").addEventListener("click", () => $("#navLinks").classList.toggle("open"));
    document.querySelectorAll("#navLinks a").forEach(a => a.addEventListener("click", () => $("#navLinks").classList.remove("open")));
    $("#matchBtn").addEventListener("click", runMatch);
    await detectServer();
    await setupSession();
    await setupForm();
    await refresh();
    if (mode === "live") setInterval(refresh, 5000);     // poll the API for changes made in the desktop app
    else toast("Demo mode", "Java server not detected — the engine runs in your browser with sample data.", "warn");
  }
  boot();
})();
