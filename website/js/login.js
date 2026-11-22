/* Coordinator login / sign-up / e-mail verification */
(() => {
  "use strict";
  const { api, $, escapeHtml, toast } = window.CC;
  const next = new URLSearchParams(location.search).get("next") || "/admin.html";
  const safeNext = next.startsWith("/") && !next.startsWith("//") ? next : "/admin.html";   // no open redirects
  const views = ["loginView", "registerView", "verifyView", "doneView"];

  function show(id) {
    views.forEach(v => $("#" + v).hidden = v !== id);
    $("#tabLogin").classList.toggle("on", id === "loginView");
    $("#tabRegister").classList.toggle("on", id !== "loginView");
  }
  function msg(el, text, kind = "err") {
    el.className = "notice " + kind;
    el.innerHTML = text;
    el.hidden = !text;
  }
  const formData = f => Object.fromEntries(new FormData(f));

  $("#tabLogin").onclick = () => show("loginView");
  $("#tabRegister").onclick = () => show("registerView");
  $("#backToLogin").onclick = () => show("loginView");
  $("#fillDemo").onclick = () => {
    $("#loginForm").email.value = "coordinator@demo.crisisconnect.in";
    $("#loginForm").password.value = "DemoPass#2026";
  };

  // already logged in? go straight to the console
  window.CC.session().then(u => { if (u) location.replace(safeNext); });

  api("/api/organizations").then(orgs => {
    if (!orgs.length) return;
    $("#orgSelect").insertAdjacentHTML("afterbegin", `<optgroup label="Staff of an existing organization">${
      orgs.map(o => `<option value="${o.id}">${escapeHtml(o.name)} · ${escapeHtml(o.city)}</option>`).join("")}</optgroup>`);
    $("#orgSelect").value = "new";
  }).catch(() => {});
  api("/api/meta").then(m => {
    const opts = (list, label) => list.map(v => `<option value="${escapeHtml(v)}">${escapeHtml(label(v))}</option>`).join("");
    $("#newOrgType").innerHTML = opts(m.organizationTypes, t => t.charAt(0) + t.slice(1).toLowerCase());
    $("#newOrgCity").innerHTML = `<option value="">Select city</option>` + opts(m.cities, c => c);
  }).catch(() => {});
  const syncOrgFields = () => { $("#newOrgFields").hidden = $("#orgSelect").value !== "new"; };
  $("#orgSelect").addEventListener("change", syncOrgFields);
  syncOrgFields();

  $("#loginForm").addEventListener("submit", async e => {
    e.preventDefault();
    const f = formData(e.target), box = $("#loginMsg"), btn = e.target.querySelector("button[type=submit]");
    if (!f.email || !f.password) return msg(box, "Enter your e-mail and password.");
    btn.disabled = true;
    try {
      await api("/api/auth/login", { method: "POST", data: f });
      location.replace(safeNext);
    } catch (err) {
      if (err.code === "EMAIL_NOT_VERIFIED") {
        startVerify(f.email, null);
        msg($("#verifyMsg"), escapeHtml(err.message), "warn");
      } else {
        msg(box, escapeHtml(err.message), err.code === "PENDING_APPROVAL" ? "warn" : "err");
      }
    } finally { btn.disabled = false; }
  });

  $("#registerForm").addEventListener("submit", async e => {
    e.preventDefault();
    const f = formData(e.target), box = $("#registerMsg"), btn = e.target.querySelector("button[type=submit]");
    const errs = [];
    if (!f.name.trim()) errs.push("Enter your name");
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(f.email)) errs.push("Enter a valid e-mail");
    if (f.password.length < 8 || !/[A-Za-z]/.test(f.password) || !/[0-9]/.test(f.password)) errs.push("Password needs 8+ characters with a letter and a number");
    if (f.organizationId === "new") {
      if (!f.newOrgName.trim()) errs.push("Enter your organization's name");
      if (!f.newOrgCity) errs.push("Choose your organization's city");
      f.organizationId = "";
    } else {
      delete f.newOrgName; delete f.newOrgType; delete f.newOrgCity; delete f.newOrgPhone;
    }
    if (errs.length) return msg(box, errs.map(escapeHtml).join("<br>"));
    btn.disabled = true;
    try {
      const r = await api("/api/auth/register", { method: "POST", data: f });
      msg(box, "");
      if (r.needsCode === false) { $("#doneMsg").textContent = r.message; show("doneView"); }
      else startVerify(r.email, r.devCode);
    } catch (err) {
      msg(box, err.errors.map(escapeHtml).join("<br>"));
    } finally { btn.disabled = false; }
  });

  function startVerify(email, devCode) {
    $("#verifyForm").email.value = email;
    $("#verifySub").innerHTML = `Enter the 6-digit code we sent to <b>${escapeHtml(email)}</b>. It is valid for 10 minutes.`;
    showDev(devCode);
    msg($("#verifyMsg"), "");
    show("verifyView");
    setTimeout(() => $("#verifyForm").code.focus(), 50);
  }
  function showDev(code) {
    const d = $("#devCode");
    if (code) {
      d.innerHTML = `Demo mode — no e-mail provider is configured, so here is your code: <code>${escapeHtml(code)}</code>`;
      d.hidden = false;
    } else d.hidden = true;
  }

  $("#verifyForm").addEventListener("submit", async e => {
    e.preventDefault();
    const f = formData(e.target), box = $("#verifyMsg");
    if (!/^\d{6}$/.test((f.code || "").trim())) return msg(box, "Enter the 6-digit code.");
    try {
      const r = await api("/api/auth/verify", { method: "POST", data: f });
      $("#doneMsg").textContent = r.message;
      show("doneView");
    } catch (err) { msg(box, escapeHtml(err.message)); }
  });

  $("#resend").onclick = async () => {
    try {
      const r = await api("/api/auth/resend", { method: "POST", data: { email: $("#verifyForm").email.value } });
      showDev(r.devCode);
      toast("Code sent", r.message);
    } catch (err) { msg($("#verifyMsg"), escapeHtml(err.message), "warn"); }
  };

  if (location.hash === "#register") show("registerView");

  // "Continue with Google" (only shown when GOOGLE_CLIENT_ID / SECRET are configured on the server)
  api("/api/auth/providers").then(p => {
    if (!p.google) return;
    document.querySelectorAll(".google-btn, .google-or").forEach(el => el.hidden = false);
    document.querySelectorAll(".google-btn").forEach(a => a.href = "/api/auth/google?next=" + encodeURIComponent(safeNext));
  }).catch(() => {});

  // messages coming back from the Google callback
  const qs = new URLSearchParams(location.search);
  const flash = $("#flash");
  if (qs.get("pending")) { flash.className = "notice warn"; flash.textContent = qs.get("pending"); flash.hidden = false; }
  else if (qs.get("error")) { flash.className = "notice err"; flash.textContent = qs.get("error"); flash.hidden = false; }
})();
