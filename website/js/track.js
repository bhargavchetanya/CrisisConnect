/* Public SOS tracking page: /track.html?code=CC-XXXXXX */
(() => {
  "use strict";
  const { api, $, escapeHtml: h, fmt, when, ago } = window.CC;
  const STATUS = {
    OPEN: ["Waiting for resources", "#4c8dff"], PARTIALLY_FULFILLED: ["Partly covered — help is on the way", "#ff8c1a"],
    FULFILLED: ["Fully covered", "#2ed573"], CANCELLED: ["Closed", "#8e9ab8"]
  };
  let current = null, timer = null;

  window.CC.renderAuthSlot($("#authSlot"));

  async function load(code, quiet = false) {
    const box = $("#result");
    if (!quiet) box.innerHTML = `<div class="panel empty">Looking up ${h(code)}…</div>`;
    try {
      const t = await api("/api/track/" + encodeURIComponent(code));
      current = t.trackingCode;
      history.replaceState(null, "", "/track.html?code=" + current);
      render(t);
      clearInterval(timer);
      if (t.status === "OPEN" || t.status === "PARTIALLY_FULFILLED") timer = setInterval(() => load(current, true), 10000);
    } catch (e) {
      clearInterval(timer);
      box.innerHTML = `<div class="notice err">${e.status === 404
        ? `No SOS found with the tracking ID <b>${h(code.toUpperCase())}</b>. Check the code in your SMS.`
        : h(e.message)}</div>`;
    }
  }

  function render(t) {
    let [label, color] = STATUS[t.status] || [t.status, "#8e9ab8"];
    if (t.verification === "UNVERIFIED" && t.status === "OPEN") [label, color] = ["Waiting for verification", "#f5c518"];
    if (t.verification === "REJECTED") [label, color] = ["Could not be verified", "#ff4757"];
    const queue = t.queuePosition ? `#${t.queuePosition} of ${t.queueLength}` : "—";
    $("#result").innerHTML = `<div class="track-card">
      <div class="panel">
        <div class="track-head">
          <div><span class="code-big">${h(t.trackingCode)}</span><h2>${h(t.title)}</h2>
            <div class="muted">${h(t.disasterLabel)} · ${h(t.city)} · raised ${ago(t.createdAt)} · <span class="badge b-${t.urgency}">${t.urgency}</span></div></div>
          <span class="status-big" style="background:${color}22;color:${color};border:1px solid ${color}66">${h(label)}</span>
        </div>
        <div style="margin:18px 0 6px" class="meter"><i style="width:${t.percent}%"></i></div>
        <div class="muted small">${fmt(t.quantityFulfilled)} of ${fmt(t.quantityNeeded)} ${h(t.unit)} of ${h(t.resourceLabel)} dispatched (${t.percent}%)</div>
        <div class="facts" style="margin-top:18px">
          <div><b>${Number(t.priority).toFixed(1)}</b><span>priority score</span></div>
          <div><b>${queue}</b><span>place in priority queue</span></div>
          <div><b>${fmt(t.peopleAffected)}</b><span>people affected</span></div>
          <div><b>${t.dispatches.length}</b><span>dispatches</span></div>
        </div>
      </div>
      <div class="grid-2c">
        <div class="panel"><div class="panel-head"><h3>Timeline</h3><span class="muted small">updates every 10 s</span></div>
          <ul class="timeline">${t.timeline.map(s => `<li class="${s.kind}"><b>${h(s.title)}</b><span>${h(s.text)}</span><br><time>${when(s.time)}</time></li>`).join("")}</ul>
        </div>
        <div class="panel"><div class="panel-head"><h3>Help on the way</h3><span class="muted small mono">reporter ${h(t.phone || "-")}</span></div>
          <div class="stack" style="gap:10px">${t.dispatches.length ? t.dispatches.map(d => `<div class="msg WHATSAPP">
              <div class="head"><span class="ch">🚚 ${fmt(d.quantity)} ${h(t.unit)}</span><b>${h(d.resource)}</b></div>
              <div class="bubble">From ${h(d.organization)} · ${d.distanceKm < 1 ? "same city" : Number(d.distanceKm).toFixed(1) + " km"} · ETA ~${d.etaMinutes} min</div>
              <div class="foot"><span class="event">DISPATCHED</span><span>${ago(d.createdAt)}</span></div></div>`).join("")
            : `<div class="empty">${t.verification === "UNVERIFIED" ? "Nothing is dispatched until a local officer verifies this SOS."
                : t.status === "CANCELLED" ? "This SOS is closed." : "Waiting for the next Smart Match run."}</div>`}</div>
        </div>
      </div>
    </div>`;
  }

  $("#trackForm").addEventListener("submit", e => {
    e.preventDefault();
    const code = $("#codeInput").value.trim();
    if (code) load(code);
  });

  const initial = new URLSearchParams(location.search).get("code");
  if (initial) { $("#codeInput").value = initial.toUpperCase(); load(initial); }
  else $("#codeInput").focus();
})();
