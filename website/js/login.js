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
    $("#orgSelect").insertAdjacentHTML("beforeend",
      orgs.map(o => `<option value="${o.id}">${escapeHtml(o.name)} · ${escapeHtml(o.city)}</option>`).join(""));
  }).catch(() => {});

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
    if (errs.length) return msg(box, errs.map(escapeHtml).join("<br>"));
    btn.disabled = true;
    try {
      const r = await api("/api/auth/register", { method: "POST", data: f });
      msg(box, "");
      startVerify(r.email, r.devCode);
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
})();
