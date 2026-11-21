/* =========================================================
   CrisisConnect - shared browser helpers (all pages)
   window.CC.api(path, {method, data})  -> JSON (throws Error with .status/.code/.errors)
   ========================================================= */
(() => {
  "use strict";
  const $ = s => document.querySelector(s);

  function escapeHtml(s) {
    return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  /** Calls the Java REST API. Writes send form-encoded data plus the X-CC-Client header (CSRF guard). */
  async function api(path, { method = "GET", data = null } = {}) {
    const opts = { method, headers: { "X-CC-Client": "web" }, credentials: "same-origin", cache: "no-store" };
    if (data) {
      opts.body = new URLSearchParams(Object.entries(data).filter(([, v]) => v !== undefined && v !== null));
      opts.headers["Content-Type"] = "application/x-www-form-urlencoded";
    }
    const res = await fetch(path, opts);
    let body = null;
    try { body = await res.json(); } catch (_) { /* empty body */ }
    if (!res.ok) {
      const err = new Error((body && (body.error || (body.errors || []).join(". "))) || `Request failed (${res.status})`);
      err.status = res.status; err.code = body && body.code; err.errors = (body && body.errors) || [err.message];
      throw err;
    }
    return body;
  }

  function toast(title, msg, kind = "") {
    let host = $("#toasts");
    if (!host) { host = document.createElement("div"); host.id = "toasts"; host.className = "toast-host"; document.body.appendChild(host); }
    const t = document.createElement("div");
    t.className = "toast " + kind;
    t.innerHTML = `<b>${escapeHtml(title)}</b><span>${escapeHtml(msg)}</span>`;
    host.appendChild(t);
    setTimeout(() => { t.classList.add("out"); setTimeout(() => t.remove(), 400); }, 4200);
  }

  let sessionPromise = null;
  /** Current user or null. Cached per page load. */
  function session(refresh = false) {
    if (!sessionPromise || refresh) {
      sessionPromise = api("/api/auth/me").then(r => r.user).catch(() => null);
    }
    return sessionPromise;
  }

  /** Fills an element with "Coordinator login" or the user chip + console link. */
  async function renderAuthSlot(el) {
    if (!el) return null;
    const u = await session();
    if (!u) {
      el.innerHTML = `<a class="btn btn-ghost btn-xs" href="/login.html">🔐 Coordinator login</a>`;
    } else {
      el.innerHTML = `<a class="user-chip" href="/admin.html" title="Open the console">
          <span class="avatar">${escapeHtml(u.name.charAt(0).toUpperCase())}</span>
          <span><b>${escapeHtml(u.name.split(" ")[0])}</b><small>${escapeHtml(u.roleLabel)}</small></span></a>`;
    }
    return u;
  }

  const fmt = n => Number(n || 0).toLocaleString("en-IN");
  const ago = iso => {
    const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
    if (s < 60) return "just now";
    if (s < 3600) return Math.floor(s / 60) + " min ago";
    if (s < 86400) return Math.floor(s / 3600) + " h ago";
    return new Date(iso).toLocaleDateString("en-IN", { day: "numeric", month: "short" });
  };
  const when = iso => new Date(iso).toLocaleString("en-IN", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });

  window.CC = { api, toast, session, renderAuthSlot, escapeHtml, fmt, ago, when, $ };
})();
