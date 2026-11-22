/* =========================================================
   CrisisConnect web console (coordinators + administrators)
   ========================================================= */
(() => {
  "use strict";
  const { api, $, escapeHtml: h, toast, fmt, ago, when } = window.CC;
  const LABEL = { FOOD: "Food & Water", MEDICAL: "Medical Supplies", SHELTER: "Shelter Beds", VEHICLE: "Rescue Vehicles", VOLUNTEER: "Volunteers" };
  const TITLES = {
    overview: ["Overview", "Live numbers, Smart Match and the latest alerts"],
    requests: ["Emergency requests", "Re-prioritise or close SOS requests"],
    resources: ["Resources", "Stock registered by partner organizations"],
    organizations: ["Organizations", "NGOs, hospitals, government bodies and volunteer groups"],
    outbox: ["WhatsApp & e-mail outbox", "Every alert the system has sent"],
    users: ["Users", "Approve organizations and coordinators, assign roles"],
    audit: ["Audit log", "Who did what, and when"],
    account: ["My account", "Profile and password"]
  };
  let me = null, meta = null, orgs = [], timer = null;
  let reqFilter = "active", channel = "ALL";
  const ROLE_LABEL = { ORGANIZATION: "Organization", COORDINATOR: "Coordinator", ADMIN: "Administrator" };

  // ------------------------------------------------------------------ boot
  async function boot() {
    me = await window.CC.session();
    if (!me) { location.replace("/login.html?next=" + encodeURIComponent("/admin.html" + location.hash)); return; }
    $("#app").hidden = false;
    $("#meName").textContent = me.name;
    $("#meOrg").textContent = me.organization || (me.role === "ORGANIZATION" ? "No organization linked yet" : "All organizations");
    $("#avatar").textContent = me.name.charAt(0).toUpperCase();
    $("#rolePill").textContent = me.roleLabel;
    $("#rolePill").classList.add(me.role);
    if (me.role !== "ADMIN") document.querySelectorAll(".admin-only").forEach(e => e.remove());
    if (me.role === "ORGANIZATION") document.querySelectorAll(".staff-only").forEach(e => e.remove());
    else document.querySelectorAll(".org-only").forEach(e => e.remove());
    meta = await api("/api/meta");
    fillSelect($("#resType"), meta.resourceTypes.map(t => [t, LABEL[t] || t]));
    fillSelect($("#resCity"), [["", "Select city"], ...meta.cities.map(c => [c, c])]);
    fillSelect($("#orgCity"), [["", "Select city"], ...meta.cities.map(c => [c, c])]);
    fillSelect($("#orgType"), meta.organizationTypes.map(t => [t, t.charAt(0) + t.slice(1).toLowerCase()]));
    wire();
    window.addEventListener("hashchange", route);
    route();
  }

  function fillSelect(sel, pairs) {
    sel.innerHTML = pairs.map(([v, l]) => `<option value="${h(v)}">${h(l)}</option>`).join("");
  }

  function route() {
    let tab = (location.hash || "#overview").slice(1);
    if (!TITLES[tab] || (["users", "audit"].includes(tab) && me.role !== "ADMIN")
        || (["organizations", "outbox"].includes(tab) && me.role === "ORGANIZATION")) tab = "overview";
    document.querySelectorAll(".view").forEach(v => v.classList.toggle("on", v.id === "v-" + tab));
    document.querySelectorAll(".side a.tab").forEach(a => a.classList.toggle("on", a.dataset.tab === tab));
    $("#viewTitle").textContent = TITLES[tab][0];
    $("#viewSub").textContent = TITLES[tab][1];
    clearInterval(timer);
    const load = LOADERS[tab];
    load();
    if (["overview", "outbox", "requests"].includes(tab)) timer = setInterval(load, 8000);   // keep live
  }

  const LOADERS = {
    overview: loadOverview, requests: loadRequests, resources: loadResources, organizations: loadOrganizations,
    outbox: loadOutbox, users: loadUsers, audit: loadAudit, account: loadAccount
  };

  function handleError(err) {
    if (err.status === 401) { location.replace("/login.html?next=" + encodeURIComponent("/admin.html" + location.hash)); return; }
    toast("Something went wrong", err.message, "err");
  }

  // ------------------------------------------------------------------ overview
  async function loadOverview() {
    try {
      const o = await api("/api/admin/overview");
      const s = o.stats;
      $("#oActive").textContent = fmt(s.activeRequests);
      $("#oCritical").textContent = fmt(s.criticalActive);
      $("#oPeople").textContent = fmt(s.peopleAffected);
      $("#oUnits").textContent = fmt(s.unitsAvailable);
      $("#oDispatched").textContent = fmt(s.unitsDispatched);
      $("#oMessages").textContent = fmt(o.messages);
      setPending(o.pendingUsers);
      if ($("#recentMsgs")) $("#recentMsgs").innerHTML = o.recentMessages.length
        ? o.recentMessages.map(msgCard).join("")
        : `<div class="empty">No messages yet — raise an SOS or run Smart Match.</div>`;
    } catch (e) { handleError(e); }
  }

  function setPending(n) {
    const c = $("#pendingCount");
    if (c) { c.textContent = n; c.hidden = !n; }
    $("#pendingBar").hidden = !n;
    $("#pendingText").textContent = `${n} account${n === 1 ? " is" : "s are"} waiting for approval`;
  }

  async function runMatch() {
    const btn = $("#runMatch"), out = $("#matchConsole");
    btn.disabled = true;
    out.textContent = "> running smart match...\n";
    try {
      const rep = await api("/api/match", { method: "POST" });
      for (const line of rep.log.join("\n").split("\n")) {
        out.textContent += line + "\n";
        out.scrollTop = out.scrollHeight;
        await new Promise(r => setTimeout(r, 25));
      }
      out.textContent += `> DONE in ${rep.durationMs} ms — ${rep.headline}\n> WhatsApp + e-mail notifications queued in the outbox.\n`;
      out.scrollTop = out.scrollHeight;
      toast("Smart Match complete", rep.headline, rep.unmet > 0 ? "warn" : "");
      loadOverview();
    } catch (e) { out.textContent += "> ERROR: " + e.message; handleError(e); }
    finally { btn.disabled = false; }
  }

  // ------------------------------------------------------------------ requests
  async function loadRequests() {
    try {
      const list = await api("/api/admin/requests");
      const q = $("#reqSearch").value.trim().toLowerCase();
      const active = r => r.status === "OPEN" || r.status === "PARTIALLY_FULFILLED";
      const rows = list
        .filter(r => reqFilter === "all" || (reqFilter === "active" ? active(r) : r.status === reqFilter))
        .filter(r => !q || (r.title + " " + r.city + " " + r.trackingCode).toLowerCase().includes(q))
        .sort((a, b) => (active(b) - active(a)) || b.priority - a.priority);
      $("#reqBody").innerHTML = rows.length ? rows.map(r => {
        const pct = r.quantityNeeded ? Math.round(r.quantityFulfilled / r.quantityNeeded * 100) : 0;
        const staff = me.role !== "ORGANIZATION";
        const urg = active(r) && staff
          ? `<select class="inp sm" data-urg="${r.id}">${["CRITICAL", "HIGH", "MEDIUM", "LOW"].map(u => `<option ${u === r.urgency ? "selected" : ""}>${u}</option>`).join("")}</select>`
          : `<span class="badge b-${r.urgency}">${r.urgency}</span>`;
        return `<tr>
          <td><a class="mono" style="color:var(--teal)" href="/track.html?code=${h(r.trackingCode)}" target="_blank" rel="noopener">${h(r.trackingCode)}</a></td>
          <td><b>${h(r.title)}</b><div class="sub">${h(r.disasterLabel)} · ${h(LABEL[r.resourceType])} · via ${h(r.source)} · ${ago(r.createdAt)} · score ${Number(r.priority).toFixed(1)}</div></td>
          <td>${h(r.city)}</td>
          <td>${urg}</td>
          <td><div class="progress"><i style="width:${pct}%"></i></div><div class="sub">${fmt(r.quantityFulfilled)} / ${fmt(r.quantityNeeded)} ${h(r.unit)}</div></td>
          <td class="mono small">${h(r.phone || "—")}</td>
          <td><span class="pill ${r.status}">${r.status.replace("_", " ")}</span></td>
          <td>${active(r) && staff ? `<button class="btn btn-danger btn-xs" data-cancel="${r.id}" data-title="${h(r.title)}">Close</button>` : ""}</td>
        </tr>`;
      }).join("") : `<tr><td colspan="8" class="empty">No requests match this filter</td></tr>`;
    } catch (e) { handleError(e); }
  }

  // ------------------------------------------------------------------ resources
  async function loadResources() {
    try {
      const [res, o] = await Promise.all([api("/api/resources"), api("/api/organizations")]);
      orgs = o;
      const own = me.role !== "ADMIN" && me.organizationId;
      fillSelect($("#resOrg"), (own ? orgs.filter(x => x.id === me.organizationId) : [{ id: "", name: "Select organization" }, ...orgs])
        .map(x => [x.id, x.name]));
      $("#resOrg").disabled = !!own;
      $("#scopeNote").textContent = me.role === "ORGANIZATION"
        ? `Supplies of ${me.organization || "your organization"} - the CrisisConnect team decides when to dispatch them`
        : own ? `You manage resources of ${me.organization}` : "You can manage every organization's resources";
      const nameToId = Object.fromEntries(orgs.map(x => [x.name, x.id]));
      $("#resBody").innerHTML = res.length ? res.map(r => {
        const mine = !own || nameToId[r.organization] === me.organizationId;
        const pct = r.capacity ? Math.round(r.available / r.capacity * 100) : 0;
        const exp = r.expiry ? Math.ceil((new Date(r.expiry) - Date.now()) / 86400000) : null;
        return `<tr>
          <td><b>${h(r.name)}</b><div class="sub">${h(r.organization)}</div></td>
          <td>${h(LABEL[r.type])}</td><td>${h(r.city)}</td>
          <td><div class="progress"><i style="width:${pct}%"></i></div><div class="sub">${fmt(r.available)} / ${fmt(r.capacity)} ${h(r.unit)}</div></td>
          <td>${exp === null ? "—" : `<span class="${exp <= 3 ? "pill PENDING" : ""}">${h(r.expiry)}${exp <= 3 ? " · " + exp + "d" : ""}</span>`}</td>
          <td>${mine ? `<div class="actions"><input class="inp sm" type="number" min="1" placeholder="+qty" style="width:80px" data-qty="${r.id}">
              <button class="btn btn-ghost btn-xs" data-restock="${r.id}">Restock</button>
              <button class="btn btn-danger btn-xs" data-delres="${r.id}" data-title="${h(r.name)}">Remove</button></div>` : `<span class="muted small">other organization</span>`}</td>
        </tr>`;
      }).join("") : `<tr><td colspan="6" class="empty">No resources yet</td></tr>`;
    } catch (e) { handleError(e); }
  }

  // ------------------------------------------------------------------ organizations
  async function loadOrganizations() {
    try {
      const [o, res] = await Promise.all([api("/api/organizations"), api("/api/resources")]);
      const count = {};
      res.forEach(r => count[r.organization] = (count[r.organization] || 0) + 1);
      $("#orgBody").innerHTML = o.map(x => `<tr>
        <td><b>${h(x.name)}</b></td><td>${h(x.type.charAt(0) + x.type.slice(1).toLowerCase())}</td><td>${h(x.city)}</td>
        <td class="mono small">${h(x.phone || "—")}</td><td class="small">${h(x.email || "—")}</td><td>${count[x.name] || 0}</td>
        <td>${me.role === "ADMIN" ? `<button class="btn btn-danger btn-xs" data-delorg="${x.id}" data-title="${h(x.name)}">Delete</button>` : ""}</td>
      </tr>`).join("");
    } catch (e) { handleError(e); }
  }

  // ------------------------------------------------------------------ outbox
  function msgCard(m) {
    const icon = { SMS: "✉️", WHATSAPP: "🟢", EMAIL: "📧" }[m.channel];
    const real = m.status !== "SIMULATED";
    return `<div class="msg ${m.channel}${real ? " real" : ""}">
      <div class="head"><span class="ch">${icon} ${h(m.channelLabel.toUpperCase())}</span><b>${h(m.toName || "")}</b><span class="mono">${h(m.to)}</span>${real ? `<span class="real-tag">REAL</span>` : ""}</div>
      <div class="bubble" style="white-space:pre-line">${h(m.message)}</div>
      ${m.error ? `<div class="err-text">⚠ ${h(m.error)}</div>` : ""}
      <div class="foot"><span class="event">${h(m.event)}</span><span><span class="pill ${m.status}">${m.status}</span> · ${ago(m.createdAt)}</span></div>
    </div>`;
  }

  async function loadOutbox() {
    try {
      const [list, o] = await Promise.all([api("/api/admin/outbox?limit=120&channel=" + channel), api("/api/admin/overview")]);
      $("#outboxNote").textContent = `WhatsApp: ${o.whatsappMode} · e-mail: ${o.emailMode} ${o.smsMode && o.smsMode !== 'simulated' ? ' · SMS: ' + o.smsMode : ''} · ${fmt(o.messages)} messages`;
      $("#outbox").innerHTML = list.length ? list.map(msgCard).join("")
        : `<div class="empty panel">No messages yet. Raise an SOS with a phone number, or run Smart Match.</div>`;
    } catch (e) { handleError(e); }
  }

  // ------------------------------------------------------------------ users (admin)
  async function loadUsers() {
    try {
      const [users, o] = await Promise.all([api("/api/admin/users"), api("/api/organizations")]);
      orgs = o;
      const pending = users.filter(u => u.status === "PENDING" && u.emailVerified);
      setPending(pending.length);
      $("#pendingPanel").hidden = !pending.length;
      $("#pendingList").innerHTML = pending.map(u => `<div class="toolbar" style="padding:10px 0;border-bottom:1px solid #182040;margin:0">
          <div><b>${h(u.name)}</b> <span class="muted">${h(u.email)}</span><div class="sub muted small">${h(u.organization || "No organization")} · signed up ${ago(u.createdAt)}</div></div>
          <div class="actions" style="margin-left:auto"><button class="btn btn-green btn-xs" data-approve="${u.id}">✓ Approve</button>
          <button class="btn btn-danger btn-xs" data-deluser="${u.id}" data-title="${h(u.email)}">Reject</button></div></div>`).join("");
      const orgOpts = sel => `<option value="">All organizations</option>` + orgs.map(x => `<option value="${x.id}" ${x.id === sel ? "selected" : ""}>${h(x.name)}</option>`).join("");
      $("#userBody").innerHTML = users.map(u => {
        const self = u.id === me.id;
        return `<tr>
          <td><b>${h(u.name)}</b>${self ? ' <span class="muted small">(you)</span>' : ""}<div class="sub">${h(u.email)}${u.emailVerified ? "" : " · e-mail not verified"}</div></td>
          <td><select class="inp sm" data-role="${u.id}" ${self ? "disabled" : ""}>${["ORGANIZATION", "COORDINATOR", "ADMIN"].map(r => `<option value="${r}" ${r === u.role ? "selected" : ""}>${ROLE_LABEL[r]}</option>`).join("")}</select></td>
          <td><select class="inp sm" data-org="${u.id}" style="max-width:210px">${orgOpts(u.organizationId)}</select></td>
          <td><span class="pill ${u.status}">${u.status}</span></td>
          <td class="small">${u.lastLoginAt ? ago(u.lastLoginAt) : "never"}</td>
          <td><div class="actions">${self ? "" : u.status === "ACTIVE"
            ? `<button class="btn btn-ghost btn-xs" data-status="${u.id}" data-to="DISABLED">Disable</button>`
            : u.status === "DISABLED" ? `<button class="btn btn-ghost btn-xs" data-status="${u.id}" data-to="ACTIVE">Enable</button>`
            : u.emailVerified ? `<button class="btn btn-green btn-xs" data-approve="${u.id}">Approve</button>` : ""}
            ${self ? "" : `<button class="btn btn-danger btn-xs" data-deluser="${u.id}" data-title="${h(u.email)}">Delete</button>`}</div></td>
        </tr>`;
      }).join("");
    } catch (e) { handleError(e); }
  }

  // ------------------------------------------------------------------ audit (admin)
  async function loadAudit() {
    try {
      const list = await api("/api/admin/audit?limit=200");
      $("#auditBody").innerHTML = list.map(a => `<tr>
        <td class="small" title="${h(a.createdAt)}">${when(a.createdAt)}</td><td class="small">${h(a.actor)}</td>
        <td><span class="mono small" style="color:${/FAILED|LOCKED|DELETED|CANCELLED/.test(a.action) ? "#ff8e98" : "#9dc0ff"}">${h(a.action)}</span></td>
        <td class="small">${h(a.details)}</td><td class="mono small muted">${h(a.ip || "")}</td></tr>`).join("")
        || `<tr><td colspan="5" class="empty">Nothing yet</td></tr>`;
    } catch (e) { handleError(e); }
  }

  // ------------------------------------------------------------------ account
  function loadAccount() {
    $("#profile").innerHTML = `<span>Name</span><b>${h(me.name)}</b><span>E-mail</span><b>${h(me.email)}</b>
      <span>Role</span><b>${h(me.roleLabel)}</b><span>Organization</span><b>${h(me.organization || "All organizations")}</b>
      <span>Member since</span><b>${when(me.createdAt)}</b>`;
  }

  // ------------------------------------------------------------------ actions (event delegation)
  function wire() {
    if ($("#runMatch")) $("#runMatch").onclick = runMatch;
    const testBtn = $("#sendTest");
    if (testBtn) testBtn.onclick = async () => {
      testBtn.disabled = true; testBtn.textContent = "Sending…";
      try {
        const res = await api("/api/admin/notifications/test", { method: "POST" });
        const ok = res.filter(m => m.status === "SENT").length, failed = res.filter(m => m.status === "FAILED").length;
        if (!res.length) toast("Nothing to send", "Set ADMIN_WHATSAPP / WHATSAPP_ALERT_NUMBERS and ADMIN_EMAILS on the server.", "warn");
        else toast("Test sent", `${ok} delivered to the gateway · ${failed} failed · ${res.length - ok - failed} simulated`, failed ? "err" : "");
        loadOutbox();
      } catch (e) { handleError(e); }
      finally { testBtn.disabled = false; testBtn.textContent = "📲 Send test alerts"; }
    };
    $("#logout").onclick = async () => {
      try { await api("/api/auth/logout", { method: "POST" }); } catch (_) { /* ignore */ }
      location.replace("/login.html");
    };
    $("#reqFilter").onclick = e => {
      const b = e.target.closest("button"); if (!b) return;
      reqFilter = b.dataset.f;
      document.querySelectorAll("#reqFilter button").forEach(x => x.classList.toggle("on", x === b));
      loadRequests();
    };
    $("#reqSearch").oninput = loadRequests;
    $("#chFilter").onclick = e => {
      const b = e.target.closest("button"); if (!b) return;
      channel = b.dataset.c;
      document.querySelectorAll("#chFilter button").forEach(x => x.classList.toggle("on", x === b));
      loadOutbox();
    };

    document.body.addEventListener("change", async e => {
      const t = e.target;
      try {
        if (t.dataset.urg) {
          await api(`/api/admin/requests/${t.dataset.urg}/urgency`, { method: "POST", data: { urgency: t.value } });
          toast("Urgency updated", `Request re-prioritised to ${t.value}`);
          loadRequests();
        } else if (t.dataset.role) {
          await api(`/api/admin/users/${t.dataset.role}/role`, { method: "POST", data: { role: t.value } });
          toast("Role updated", "Now: " + ROLE_LABEL[t.value]);
          loadUsers();
        } else if (t.dataset.org) {
          await api(`/api/admin/users/${t.dataset.org}/organization`, { method: "POST", data: { organizationId: t.value } });
          toast("Organization updated", t.options[t.selectedIndex].text);
          loadUsers();
        }
      } catch (err) { handleError(err); LOADERS[(location.hash || "#overview").slice(1)]?.(); }
    });

    document.body.addEventListener("click", async e => {
      const b = e.target.closest("button"); if (!b || !b.dataset) return;
      const d = b.dataset;
      try {
        if (d.cancel) {
          if (!confirm(`Close "${d.title}"? The reporter is notified.`)) return;
          await api(`/api/admin/requests/${d.cancel}/cancel`, { method: "POST" });
          toast("Request closed", d.title); loadRequests();
        } else if (d.restock) {
          const qty = document.querySelector(`[data-qty="${d.restock}"]`).value;
          await api(`/api/admin/resources/${d.restock}/restock`, { method: "POST", data: { quantity: qty } });
          toast("Restocked", `+${qty} added`); loadResources();
        } else if (d.delres) {
          if (!confirm(`Remove "${d.title}"? Its dispatch history is removed too.`)) return;
          await api(`/api/admin/resources/${d.delres}`, { method: "DELETE" });
          toast("Resource removed", d.title); loadResources();
        } else if (d.delorg) {
          if (!confirm(`Delete "${d.title}" and all of its resources?`)) return;
          await api(`/api/admin/organizations/${d.delorg}`, { method: "DELETE" });
          toast("Organization deleted", d.title); loadOrganizations();
        } else if (d.approve) {
          const u = await api(`/api/admin/users/${d.approve}/approve`, { method: "POST" });
          toast("Approved", `${u.email} can log in now (e-mail sent)`); loadUsers();
        } else if (d.status) {
          await api(`/api/admin/users/${d.status}/status`, { method: "POST", data: { status: d.to } });
          toast(d.to === "ACTIVE" ? "Enabled" : "Disabled", d.to === "ACTIVE" ? "User can log in again" : "User was logged out"); loadUsers();
        } else if (d.deluser) {
          if (!confirm(`Delete the account ${d.title}?`)) return;
          await api(`/api/admin/users/${d.deluser}`, { method: "DELETE" });
          toast("Account deleted", d.title); loadUsers();
        }
      } catch (err) { handleError(err); }
    });

    $("#resForm").addEventListener("submit", async e => {
      e.preventDefault();
      const data = Object.fromEntries(new FormData(e.target));
      if ($("#resOrg").disabled) data.organizationId = me.organizationId;
      try {
        const r = await api("/api/admin/resources", { method: "POST", data });
        $("#resErr").hidden = true; e.target.reset();
        toast("Resource added", `${r.available} ${r.unit} of ${r.name}`); loadResources();
      } catch (err) { $("#resErr").innerHTML = err.errors.map(h).join("<br>"); $("#resErr").hidden = false; }
    });

    $("#orgForm").addEventListener("submit", async e => {
      e.preventDefault();
      try {
        const o = await api("/api/admin/organizations", { method: "POST", data: Object.fromEntries(new FormData(e.target)) });
        $("#orgErr").hidden = true; e.target.reset();
        toast("Organization registered", o.name); loadOrganizations();
      } catch (err) { $("#orgErr").innerHTML = err.errors.map(h).join("<br>"); $("#orgErr").hidden = false; }
    });

    $("#pwForm").addEventListener("submit", async e => {
      e.preventDefault();
      const box = $("#pwMsg");
      try {
        await api("/api/auth/password", { method: "POST", data: Object.fromEntries(new FormData(e.target)) });
        box.className = "notice ok"; box.textContent = "Password changed."; box.hidden = false; e.target.reset();
      } catch (err) { box.className = "notice err"; box.innerHTML = err.errors.map(h).join("<br>"); box.hidden = false; }
    });
  }

  boot();
})();
