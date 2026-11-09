(() => {
  "use strict";
  /* ================= Утилиты ================= */
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const NS = "http://www.w3.org/2000/svg";
  const clamp = (v, a = 0, b = 1) => Math.min(b, Math.max(a, v));
  const ease = (t) => (t < .5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2);
  const rng = (seed) => () => { seed |= 0; seed = (seed + 0x6d2b79f5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  const css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
  const FINAL = /[?&]final(&|$)/.test(location.search);
  if (FINAL) document.documentElement.classList.add("final");
  const reduced = FINAL || matchMedia("(prefers-reduced-motion: reduce)").matches;
  const E = "cubic-bezier(.2,.7,.2,1)";

  const el = (name, attrs = {}, parent) => {
    const e = document.createElementNS(NS, name);
    for (const k in attrs) if (attrs[k] !== undefined) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  };
  const STY = { fill: "fill", "font-size": "fontSize", "font-weight": "fontWeight", "font-family": "fontFamily" };
  const txt = (p, x, y, s, cls, a = {}) => {
    const rest = {}, st = {};
    for (const k in a) (STY[k] ? (st[STY[k]] = typeof a[k] === "number" && k === "font-size" ? a[k] + "px" : a[k]) : (rest[k] = a[k]));
    const t = el("text", Object.assign({ x, y, class: cls || undefined }, rest), p);
    Object.assign(t.style, st);
    t.textContent = s; return t;
  };
  const lines = (p, x, y, arr, cls, lh, a = {}) => { const g = el("g", {}, p); arr.forEach((s, i) => txt(g, x, y + i * lh, s, cls, a)); return g; };
  const rect = (p, x, y, w, h, a = {}) => el("rect", Object.assign({ x, y, width: w, height: h }, a), p);
  const path = (p, d, a = {}) => el("path", Object.assign({ d }, a), p);
  function arrowDefs(svg, id, color) {
    const defs = svg.querySelector("defs") || el("defs", {}, svg);
    const m = el("marker", { id, viewBox: "0 0 10 10", refX: 8.5, refY: 5, markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse" }, defs);
    el("path", { d: "M0 0L10 5L0 10z", fill: color }, m);
    return `url(#${id})`;
  }

  /* Контекст анимаций текущего слайда */
  let ctx = { dead: true, t: [] };
  const newCtx = () => { ctx.dead = true; ctx.t.forEach(clearTimeout); ctx = { dead: false, t: [] }; return ctx; };
  const after = (ms, fn) => { const c = ctx; c.t.push(setTimeout(() => { if (!c.dead) fn(); }, reduced ? 0 : ms)); };
  const loop = (fn) => { if (reduced) { fn(100); return; } const c = ctx, t0 = performance.now(); const f = (now) => { if (c.dead) return; if (fn((now - t0) / 1000) !== false) requestAnimationFrame(f); }; requestAnimationFrame(f); };

  const D = (ms) => (reduced ? 0 : ms);
  const appear = (e, delay = 0, dur = 500) => e.animate([{ opacity: 0 }, { opacity: 1 }], { duration: D(dur), delay: D(delay), fill: "both", easing: "ease-out" });
  const drawIn = (e, delay = 0, dur = 900) => {
    const L = e.getTotalLength();
    e.style.strokeDasharray = `${L} ${L}`;
    return e.animate([{ strokeDashoffset: L }, { strokeDashoffset: 0 }], { duration: D(dur), delay: D(delay), fill: "both", easing: E });
  };
  const grow = (e, axis, delay = 0, dur = 600, origin) => {
    e.style.transformBox = "fill-box";
    e.style.transformOrigin = origin || (axis === "y" ? "50% 100%" : "0% 50%");
    const from = axis === "y" ? "scaleY(0)" : "scaleX(0)";
    return e.animate([{ transform: from }, { transform: "none" }], { duration: D(dur), delay: D(delay), fill: "both", easing: E });
  };
  const pop = (e, delay = 0, dur = 450) => { e.style.transformBox = "fill-box"; e.style.transformOrigin = "50% 50%"; return e.animate([{ transform: "scale(.6)", opacity: 0 }, { transform: "none", opacity: 1 }], { duration: D(dur), delay: D(delay), fill: "both", easing: "cubic-bezier(.3,1.4,.5,1)" }); };
  function packet(svg, d, color, dur, delay, opts = {}) {
    const c = el("circle", { r: opts.r || 7, fill: color, opacity: 0 }, svg);
    const a = el("animateMotion", { dur: dur + "s", begin: "indefinite", repeatCount: opts.once ? 1 : "indefinite", path: d, fill: opts.once ? "freeze" : "remove" }, c);
    if (opts.rev) { a.setAttribute("keyPoints", "1;0"); a.setAttribute("keyTimes", "0;1"); a.setAttribute("calcMode", "linear"); }
    after(delay, () => { c.setAttribute("opacity", 1); a.beginElement(); });
    return c;
  }
  const clear = (svg) => { svg.innerHTML = ""; return svg; };

  /* ================= Навигация ================= */
  const stage = $("#stage");
  const slides = $$(".slide");
  let idx = 0, step = 0, skipDraft = false;
  const hooks = {};

  // Колонтитулы
  slides.forEach((s, i) => {
    if (!s.dataset.sec) return;
    const ch = document.createElement("div");
    ch.className = "chrome";
    ch.innerHTML = `<span class="chnum">${s.dataset.num}</span><span class="secname">${s.dataset.sec}</span><span class="brand">LT Verdict</span>`;
    s.prepend(ch);
    const ft = document.createElement("div");
    ft.className = "foot";
    ft.innerHTML = `<span class="src">${s.dataset.src || ""}</span><span class="conf">Для внутреннего использования</span><span class="pg">${i + 1}</span>`;
    s.appendChild(ft);
  });

  function fit() { stage.style.transform = `scale(${Math.min(innerWidth / 1920, innerHeight / 1080)})`; }
  addEventListener("resize", fit); fit();

  try { const t = localStorage.getItem("ltv3wow-theme"); if (t) document.documentElement.dataset.theme = t; } catch (e) {}
  { const m = location.search.match(/[?&]theme=(dark|light)/); if (m) document.documentElement.dataset.theme = m[1]; }
  function toggleTheme() {
    const d = document.documentElement;
    d.dataset.theme = d.dataset.theme === "dark" ? "light" : "dark";
    try { localStorage.setItem("ltv3wow-theme", d.dataset.theme); } catch (e) {}
    go(idx, step);
  }

  const isSkipped = (i) => skipDraft && (slides[i].dataset.draft === "1" || slides[i].dataset.appendix === "1");
  const maxStep = (i) => +slides[i].dataset.steps || 0;
  function setStep(n) {
    step = n;
    const cur = slides[idx];
    cur.dataset.step = String(n);
    const h = hooks[cur.id];
    if (h && h.step) h.step(cur, n);
  }
  function go(i, st = 0) {
    i = clamp(i, 0, slides.length - 1);
    slides[idx].classList.remove("on");
    idx = i;
    const cur = slides[idx];
    newCtx();
    cur.dataset.step = "0";
    void cur.offsetWidth;
    cur.classList.add("on");
    const h = hooks[cur.id];
    if (h && h.enter) h.enter(cur);
    setStep(st);
    $("#progress").style.width = ((idx + 1) / slides.length * 100) + "%";
    const n = $(".notes", cur);
    $("#notes").innerHTML = n ? n.innerHTML : "";
    $$("#tocList li").forEach((li, k) => li.classList.toggle("cur", k === idx));
    history.replaceState(null, "", "#" + (idx + 1));
  }
  function next() {
    if (step < maxStep(idx)) { setStep(step + 1); return; }
    let j = idx + 1; while (j < slides.length && isSkipped(j)) j++;
    if (j < slides.length) go(j, 0);
  }
  function prev() {
    if (step > 0) { setStep(step - 1); return; }
    let j = idx - 1; while (j >= 0 && isSkipped(j)) j--;
    if (j >= 0) go(j, maxStep(j));
  }
  function toast(text) {
    let t = $("#toast");
    if (!t) { t = document.createElement("div"); t.id = "toast"; t.style.cssText = "position:fixed;left:50%;top:18px;transform:translateX(-50%);background:rgba(14,27,43,.94);color:#e6eef5;padding:10px 20px;border-radius:4px;font:500 17px 'IBM Plex Sans',sans-serif;z-index:60;transition:opacity .3s;"; document.body.appendChild(t); }
    t.textContent = text; t.style.opacity = 1; clearTimeout(toast.h); toast.h = setTimeout(() => (t.style.opacity = 0), 1800);
  }
  const toggleFull = () => (document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen && document.documentElement.requestFullscreen());
  const toggleNotes = () => document.body.classList.toggle("show-notes");
  const toggleToc = () => document.body.classList.toggle("show-toc");

  slides.forEach((s, i) => {
    const li = document.createElement("li");
    li.textContent = s.dataset.title + (s.dataset.draft ? " (черновик)" : s.dataset.appendix ? " (приложение)" : "");
    li.addEventListener("click", () => { document.body.classList.remove("show-toc"); go(i, 0); });
    $("#tocList").appendChild(li);
  });

  addEventListener("keydown", (e) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    const k = e.key.toLowerCase();
    if (["arrowright", "pagedown", " ", "enter"].includes(k)) { if (e.target.closest && e.target.closest("#hud button, a, #tabs button")) return; e.preventDefault(); next(); }
    else if (["arrowleft", "pageup", "backspace"].includes(k)) { e.preventDefault(); prev(); }
    else if (k === "home") go(0, 0);
    else if (k === "end") go(slides.length - 1, 0);
    else if (k === "f" || k === "а") toggleFull();
    else if (k === "t" || k === "е") toggleTheme();
    else if (k === "n" || k === "т") toggleNotes();
    else if (k === "o" || k === "щ") toggleToc();
    else if (k === "d" || k === "в") { skipDraft = !skipDraft; toast(skipDraft ? "Приложение и черновики пропускаются" : "Приложение и черновики включены"); }
    else if (k === "escape") document.body.classList.remove("show-toc", "show-notes");
  });
  stage.addEventListener("click", (e) => {
    if (e.target.closest("a, button")) return;
    const r = stage.getBoundingClientRect();
    if ((e.clientX - r.left) / r.width < .25) prev(); else next();
  });
  let hudT;
  addEventListener("mousemove", () => { document.body.classList.add("hud"); clearTimeout(hudT); hudT = setTimeout(() => document.body.classList.remove("hud"), 2500); });
  $("#hud").addEventListener("click", (e) => { const b = e.target.closest("button"); if (!b) return; ({ prev, next, toc: toggleToc, notes: toggleNotes, theme: toggleTheme, full: toggleFull })[b.dataset.act](); b.blur(); });

  /* ================= 1. Титул ================= */
  hooks["s-title"] = {
    enter() {
      const c0 = ctx; document.fonts.ready.then(() => { if (!c0.dead) heroDraw(); });
    },
  };
  function heroDraw() {
    {
      const cv = $("#heroCanvas"), c = cv.getContext("2d");
      const W = 860, H = 760, X0 = 70, X1 = 830, Y0 = 70, Y1 = 660;
      const r = rng(17), N = 26;
      const nz = []; { let a = 0; const rr = rng(7); for (let k = 0; k <= 200; k++) { a = a * .85 + (rr() - .5) * .02; nz.push(a); } }
      const base = (u) => .08 + .56 * ease(clamp(u / .22)) - .5 * ease(clamp((u - .9) / .1)) + nz[Math.round(u * 200)];
      const ghosts = Array.from({ length: N }, () => ({ off: (r() - .5) * .5, a: .05 + r() * .12, f: 6 + r() * 14, p: r() * 6 }));
      const vy = (v) => Y1 - v * (Y1 - Y0);
      const ux = (u) => X0 + u * (X1 - X0);
      loop((t) => {
        if (reduced) t = 10;
        const col = { ink: css("--ink-2"), mute: css("--mute"), line: css("--line-soft"), blue: css("--blue"), win: css("--blue-soft"), fail: css("--fail"), pass: css("--pass"), passS: css("--pass-soft"), navy: css("--navy") };
        c.setTransform(2, 0, 0, 2, 0, 0);
        c.clearRect(0, 0, W, H);
        // сетка и оси
        c.strokeStyle = col.line; c.lineWidth = 1;
        for (let k = 0; k <= 5; k++) { const y = Y0 + k * (Y1 - Y0) / 5; c.beginPath(); c.moveTo(X0, y); c.lineTo(X1, y); c.stroke(); }
        c.strokeStyle = col.ink; c.lineWidth = 1.5; c.beginPath(); c.moveTo(X0, Y0 - 10); c.lineTo(X0, Y1); c.lineTo(X1 + 10, Y1); c.stroke();
        c.fillStyle = col.mute; c.font = "16px 'IBM Plex Mono'"; c.fillText("время", X1 - 50, Y1 + 30); c.fillText("метрики", X0 + 10, Y0 - 18);
        const reveal = ease(clamp(t / 1.4)), conv = ease(clamp((t - 1.6) / 1.4));
        const win = ease(clamp((t - 3.0) / .7)), ver = clamp((t - 3.9) / .5);
        // окно оценки
        if (win > 0) {
          c.fillStyle = col.win; c.globalAlpha = win; c.fillRect(ux(.24), Y0, (ux(.88) - ux(.24)) * win, Y1 - Y0);
          c.fillStyle = col.blue; c.font = "500 17px 'IBM Plex Sans'"; c.fillText("окно оценки", ux(.24) + 12, Y0 + 26); c.globalAlpha = 1;
          // порог
          c.strokeStyle = col.fail; c.setLineDash([8, 6]); c.lineWidth = 2; c.beginPath(); c.moveTo(X0, vy(.82)); c.lineTo(X0 + (X1 - X0) * win, vy(.82)); c.stroke(); c.setLineDash([]);
          c.fillStyle = col.fail; c.globalAlpha = win; c.fillText("порог SLA", X0 + 12, vy(.82) - 10); c.globalAlpha = 1;
        }
        // ряды
        const xe = X0 + (X1 - X0) * reveal;
        ghosts.forEach((g) => {
          c.beginPath();
          for (let x = X0; x <= xe; x += 6) {
            const u = (x - X0) / (X1 - X0);
            const v = base(u) + (1 - conv) * (g.off * base(u) + g.a * Math.sin(u * g.f + g.p + t * .6));
            x === X0 ? c.moveTo(x, vy(v)) : c.lineTo(x, vy(v));
          }
          c.strokeStyle = col.mute; c.globalAlpha = .45 * (1 - conv) + .06; c.lineWidth = 1.2; c.stroke();
        });
        c.globalAlpha = 1;
        c.beginPath();
        for (let x = X0; x <= xe; x += 4) { const u = (x - X0) / (X1 - X0); const v = base(u); x === X0 ? c.moveTo(x, vy(v)) : c.lineTo(x, vy(v)); }
        c.strokeStyle = col.blue; c.lineWidth = 1.5 + 2 * conv; c.stroke();
        // вердикт
        if (ver > 0) {
          const bx = ux(.42), by = vy(.64) + 60;
          c.globalAlpha = ver; c.fillStyle = col.passS; c.strokeStyle = col.pass; c.lineWidth = 2;
          c.beginPath(); c.roundRect(bx, by, 290, 84, 4); c.fill(); c.stroke();
          c.fillStyle = col.pass; c.font = "600 30px 'IBM Plex Mono'"; c.fillText("PASS", bx + 18, by + 40);
          c.font = "16px 'IBM Plex Sans'"; c.fillStyle = col.ink; c.fillText("p95 в окне оценки ниже порога", bx + 18, by + 66);
          c.globalAlpha = 1;
        }
        return t < 5;
      });
    }
  }

  /* ================= 2. Проблема ================= */
  hooks["s-problem"] = {
    enter() {
      const box = $("#thumbs"); box.innerHTML = "";
      const r = rng(5), hot = new Set([3, 10, 19, 26, 14, 22]), seen = new Set([3, 14, 19]);
      for (let i = 0; i < 30; i++) {
        const d = document.createElement("div");
        const bad = hot.has(i);
        d.className = "th " + (bad ? (seen.has(i) ? "bad seen hot" : "bad miss hot") : "ok");
        // ровные плавные ряды: базовый уровень, пара медленных волн, у проблемных — плавный сдвиг во второй половине
        const base = 18 + r() * 6, a1 = 1 + r() * 1.5, f1 = 1 + r() * 1.5, p1 = r() * 6, a2 = .4 + r() * .6, f2 = 3 + r() * 2, p2 = r() * 6;
        const pts = [];
        for (let x = 0; x <= 100; x += 4) {
          const u = x / 100, ramp = bad ? 11 / (1 + Math.exp(-(u - .6) * 14)) : 0;
          const y = clamp(base + a1 * Math.sin(u * f1 * 6.28 + p1) + a2 * Math.sin(u * f2 * 6.28 + p2) - ramp, 3, 33);
          pts.push(`${x} ${y.toFixed(1)}`);
        }
        d.innerHTML = `<svg viewBox="0 0 100 40" preserveAspectRatio="none"><path class="ax" d="M0 39H100"/><path class="ln" d="M${pts.join(" L")}"/></svg>` + (bad ? `<i class="tag">${seen.has(i) ? "замечено" : "пропущено"}</i>` : "");
        box.appendChild(d);
        d.animate([{ opacity: 0, transform: "translateY(8px)" }, { opacity: 1, transform: "none" }], { duration: D(400), delay: D(i * 25), fill: "backwards", easing: E });
      }
      $$(".pain", $("#s-problem")).forEach((p, k) => p.animate([{ opacity: 0 }, { opacity: 1 }], { duration: D(500), delay: D(700 + k * 200), fill: "both" }));
    },
  };

  /* ================= 4. Экосистема: этапы ================= */
  hooks["s-eco"] = {
    enter() {
      const svg = clear($("#ecoRail"));
      const names = ["Спека и знания: propose", "Скрипты: apply", "Запуск и отладка: apply", "Анализ: verify"];
      const W = 420, h = 56;
      const chev = (i) => { const x = i * W; const a = i === 0 ? 0 : 18; return `M${x} 0 H${x + W - 18} L${x + W} ${h / 2} L${x + W - 18} ${h} H${x} L${x + a} ${h / 2} Z`; };
      const gA = el("g", {}, svg), gB = el("g", {}, svg);
      const cp = el("clipPath", { id: "ecoClip" }, el("defs", {}, svg));
      const cr = rect(cp, 0, 0, 0, h);
      names.forEach((n, i) => {
        path(gA, chev(i), { fill: css("--surface-2"), stroke: css("--bg"), "stroke-width": 4 });
        txt(gA, i * W + 36, 36, n, "g-lbl-b", { fill: css("--ink-2") });
        path(gB, chev(i), { fill: css("--band"), stroke: css("--bg"), "stroke-width": 4 });
        txt(gB, i * W + 36, 36, n, "g-lbl-b", { fill: "#fff" });
      });
      gB.setAttribute("clip-path", "url(#ecoClip)");
      cr.animate([{ width: "0px" }, { width: "1680px" }], { duration: D(1900), delay: D(200), fill: "both", easing: "cubic-bezier(.5,0,.3,1)" });
    },
  };

  /* ================= 5. RCA ================= */
  hooks["s-rca"] = {
    enter() {
      const st = $$("#rcaSteps .stp"), sec = $$("#rcaReport .sec");
      st.forEach((s) => s.classList.remove("act", "done")); sec.forEach((s) => s.classList.remove("on"));
      st.forEach((s, i) => after(400 + i * 1300, () => {
        st.forEach((x, k) => { x.classList.toggle("act", k === i); x.classList.toggle("done", k < i); });
        sec[i] && sec[i].classList.add("on");
        if (i === st.length - 1) after(1400, () => st.forEach((x) => { x.classList.remove("act"); x.classList.add("done"); }));
      }));
    },
  };

  /* ================= 6. Профиль Gatling ================= */
  hooks["s-scripts"] = {
    enter() {
      const svg = clear($("#profSvg"));
      const gx = 40, gy = 100, gw = 400, gh = 80;
      path(svg, `M${gx} ${gy - gh - 6} V${gy} H${gx + gw + 6}`, { stroke: "#4b647c", "stroke-width": 1.2, fill: "none" });
      txt(svg, gx - 6, gy - gh + 4, "100", "", { fill: "#6b8499", "font-size": 13, "text-anchor": "end", "font-family": "IBM Plex Mono" });
      txt(svg, gx - 6, gy, "10", "", { fill: "#6b8499", "font-size": 13, "text-anchor": "end", "font-family": "IBM Plex Mono" });
      txt(svg, gx + gw, gy + 16, "15 мин", "", { fill: "#6b8499", "font-size": 13, "text-anchor": "end", "font-family": "IBM Plex Mono" });
      const exp = path(svg, `M${gx} ${gy - 8} L${gx + gw} ${gy - gh}`, { stroke: "#3fb8b0", "stroke-width": 2, "stroke-dasharray": "6 5", fill: "none", opacity: 0 });
      appear(exp, 1900, 400);
      const r = rng(3); let d = `M${gx} ${gy - 8}`;
      for (let i = 1; i <= 40; i++) { const u = i / 40; d += ` L${gx + gw * u} ${(gy - 8 - (gh - 8) * u + (r() - .5) * 4).toFixed(1)}`; }
      drawIn(path(svg, d, { stroke: "#8ab8ee", "stroke-width": 2.4, fill: "none" }), 2200, 1200);
      const ok = txt(svg, gx + 8, 18, "✓ профиль совпадает с заявленным", "", { fill: "#4cc38a", "font-size": 16, "font-weight": 600, "font-family": "IBM Plex Mono" });
      appear(ok, 3500, 400);
    },
  };

  /* ================= 7. База знаний и плейбук ================= */
  hooks["s-know"] = {
    enter() {
      // ---- база знаний
      const s = clear($("#kbSvg"));
      const A = arrowDefs(s, "kbA", css("--ink-2"));
      const srcs = ["МЕТА", "Jira", "Confluence", "Репозитории"];
      const cys = [60, 160, 260, 360];
      srcs.forEach((n, i) => {
        const g = el("g", {}, s);
        rect(g, 0, cys[i] - 28, 180, 56, { fill: css("--surface"), stroke: css("--line"), rx: 3 });
        txt(g, 20, cys[i] + 7, n, "g-lbl-b");
        appear(g, 200 + i * 120);
        const p = path(s, `M180 ${cys[i]} H240 V210 H292`, { class: "g-conn-soft" });
        drawIn(p, 600 + i * 120, 600);
        packet(s, `M180 ${cys[i]} H240 V210 H300`, css("--blue"), 1.6, 1500 + i * 400, { r: 6 });
      });
      // цилиндр
      const g = el("g", {}, s);
      const cx = 390, rx = 95, top = 130, bot = 290;
      path(g, `M${cx - rx} ${top} V${bot} A${rx} 22 0 0 0 ${cx + rx} ${bot} V${top}`, { fill: css("--blue-soft"), stroke: css("--blue"), "stroke-width": 2 });
      el("ellipse", { cx, cy: top, rx, ry: 22, fill: css("--bg"), stroke: css("--blue"), "stroke-width": 2 }, g);
      lines(g, cx, 205, ["База знаний", "о системе"], "g-lbl-b", 26, { "text-anchor": "middle" });
      pop(g, 400);
      // потребители
      const cons = [["Агент", 80], ["Субагент-эксперт", 210], ["Инженер", 370]];
      cons.forEach(([n, y], i) => {
        const gg = el("g", {}, s);
        rect(gg, 600, y - 30, 220, 60, { fill: i === 1 ? css("--teal-soft") : css("--surface"), stroke: i === 1 ? css("--teal") : css("--line"), rx: 3 });
        txt(gg, 616, y + 7, n, "g-lbl-b");
        appear(gg, 1100 + i * 150);
      });
      [[`M485 210 H540 V80 H596`], [`M485 210 H596`], [`M485 210 H540 V370 H596`]].forEach(([d], i) => drawIn(path(s, d, { class: "g-conn", "marker-end": A }), 1200 + i * 150, 600));
      const ex = path(s, "M710 180 V114", { class: "g-conn", stroke: css("--teal"), "stroke-dasharray": "5 4", "marker-end": arrowDefs(s, "kbT", css("--teal")) });
      appear(ex, 1900);
      appear(txt(s, 700, 152, "отвечает агенту", "g-lbl", { fill: css("--teal"), "font-size": 16, "text-anchor": "end" }), 1900);
      packet(s, "M485 210 H596", css("--teal"), 1.4, 2600, { r: 6 });

      // ---- плейбук
      const L = clear($("#loopSvg"));
      const cx2 = 410, cy2 = 230, RX = 300, RY = 165;
      const ell = `M${cx2} ${cy2 - RY} A${RX} ${RY} 0 0 1 ${cx2 + RX} ${cy2} A${RX} ${RY} 0 0 1 ${cx2} ${cy2 + RY} A${RX} ${RY} 0 0 1 ${cx2 - RX} ${cy2} A${RX} ${RY} 0 0 1 ${cx2} ${cy2 - RY}`;
      drawIn(path(L, ell, { stroke: css("--blue"), "stroke-width": 2.5, fill: "none" }), 200, 1400);
      [-45, 45, 135, 225].forEach((deg, i) => {
        const th = deg * Math.PI / 180, x = cx2 + RX * Math.cos(th), y = cy2 + RY * Math.sin(th);
        const ang = Math.atan2(RY * Math.cos(th), -RX * Math.sin(th)) * 180 / Math.PI;
        const tri = path(L, "M-9 -8 L9 0 L-9 8 Z", { fill: css("--blue"), transform: `translate(${x.toFixed(1)} ${y.toFixed(1)}) rotate(${ang.toFixed(1)})` });
        appear(tri, 900 + i * 200, 300);
      });
      const nodes = [["Написать", cx2, cy2 - RY], ["Отладить", cx2 + RX, cy2], ["Запустить", cx2, cy2 + RY], ["Проверить", cx2 - RX, cy2]];
      nodes.forEach(([n, x, y], i) => {
        const gg = el("g", {}, L);
        rect(gg, x - 95, y - 28, 190, 56, { fill: css("--bg"), stroke: css("--navy"), "stroke-width": 2, rx: 3 });
        txt(gg, x, y + 8, n, "g-lbl-b", { "text-anchor": "middle" });
        appear(gg, 300 + i * 250);
      });
      const heal = path(L, `M${cx2 - RX + 95} ${cy2 + 6} H${cx2 + RX - 100}`, { stroke: css("--fail"), "stroke-width": 2.5, "stroke-dasharray": "8 6", fill: "none", "marker-end": arrowDefs(L, "lpF", css("--fail")) });
      appear(heal, 2000);
      appear(lines(L, cx2, cy2 - 46, ["сбой проверки:", "self-healing возвращает к отладке"], "g-lbl", 22, { "text-anchor": "middle", fill: css("--fail") }), 2000);
      packet(L, ell, css("--blue"), 6, 1600, { r: 8 });
      packet(L, `M${cx2 - RX + 95} ${cy2 + 6} H${cx2 + RX - 100}`, css("--fail"), 1.6, 3200, { r: 7 });
    },
  };

  /* ================= 10. Конвейер Вердикта ================= */
  hooks["s-verdict"] = {
    enter() {
      const s = clear($("#verdictSvg"));
      const A = arrowDefs(s, "vA", css("--ink-2"));
      const inputs = [["Результаты теста", ["JMeter JTL, Gatling", "simulation.log"], 30, 100], ["Политика", ["пороги, например", "p95 ≤ 500 мс"], 160, 100], ["Метрики системы", ["Prometheus, VictoriaMetrics,", "InfluxDB, OpenSearch, PostgreSQL"], 290, 120]];
      inputs.forEach(([t, sub, y, hh], i) => {
        const g = el("g", {}, s);
        rect(g, 0, y, 320, hh, { fill: css("--surface"), stroke: css("--line"), rx: 3 });
        rect(g, 0, y, 5, hh, { fill: i === 2 ? css("--mute") : css("--blue") });
        txt(g, 22, y + 34, t, "g-lbl-b");
        lines(g, 22, y + 62, sub, "g-lbl", 24, { "font-size": 17, fill: css("--mute") });
        if (i === 2) txt(g, 318, y + 34, "по желанию", "k", { "text-anchor": "end", "font-size": 15, fill: css("--mute") }).setAttribute("dx", -12);
        appear(g, 100 + i * 150);
        const cy = y + hh / 2;
        drawIn(path(s, `M320 ${cy} H350 V220 H376`, { class: "g-conn", "marker-end": A }), 500 + i * 150, 500);
      });
      // ядро
      const core = el("g", {}, s);
      rect(core, 380, 30, 760, 400, { fill: "none", stroke: css("--navy"), "stroke-width": 2, rx: 4 });
      rect(core, 380, 30, 760, 52, { fill: css("--band"), rx: 4 });
      txt(core, 404, 64, "Ядро Вердикта", "g-lbl-b", { fill: "#fff" });
      appear(core, 300);
      const stg = [["Валидность", "прогона"], ["Метрики по", "всем замерам"], ["Применение", "политики"], ["Диагностика:", "тренды, связи"]];
      const W = 178;
      stg.forEach((ln, i) => {
        const x = 402 + i * (W + 4);
        const g = el("g", {}, s);
        const d = `M${x} 170 H${x + W - 16} L${x + W} 220 L${x + W - 16} 270 H${x} ${i ? `L${x + 16} 220` : ""} Z`;
        const p = path(g, d, { fill: css("--surface-2") });
        const tt = lines(g, x + (i ? 30 : 16), 214, ln, "g-lbl-b", 24, { "font-size": 18 });
        appear(g, 700 + i * 120);
        p.animate([{ fill: css("--surface-2") }, { fill: document.documentElement.dataset.theme === "dark" ? css("--band") : css("--blue") }], { duration: D(400), delay: D(1500 + i * 450), fill: "both" });
        [...tt.children].forEach((c) => c.animate([{ fill: css("--ink") }, { fill: "#ffffff" }], { duration: D(400), delay: D(1500 + i * 450), fill: "both" }));
      });
      lines(s, 404, 330, ["Без правки скриптов: штатные результаты JMeter и Gatling.", "Каждый шаг пишет доказательства для отчёта."], "g-lbl", 28, { fill: css("--mute") });
      // вердикт
      const vs = [["PASS", css("--pass"), css("--pass-soft"), 110], ["FAIL", css("--fail"), css("--fail-soft"), 200], ["NO_VERDICT", css("--warn"), css("--warn-soft"), 290]];
      drawIn(path(s, "M1140 220 H1170 V142 M1170 220 V322 M1170 142 H1196 M1170 232 H1196 M1170 322 H1196", { class: "g-conn" }), 3300, 600);
      vs.forEach(([t, c, bg, y], i) => {
        const g = el("g", {}, s);
        rect(g, 1200, y, 230, 64, { fill: bg, stroke: c, "stroke-width": 2, rx: 3 });
        txt(g, 1315, y + 41, t, "", { fill: c, "font-family": "IBM Plex Mono", "font-size": 24, "font-weight": 600, "text-anchor": "middle" });
        pop(g, 3600 + i * 180);
      });
      const ai = el("g", {}, s);
      rect(ai, 1200, 380, 230, 50, { fill: "none", stroke: css("--line"), "stroke-dasharray": "5 4", rx: 3 });
      txt(ai, 1315, 411, "ИИ-совет: отдельно", "g-lbl", { "text-anchor": "middle", "font-size": 17 });
      appear(ai, 4300);
      // выходы
      drawIn(path(s, "M1430 232 H1476", { class: "g-conn", "marker-end": A }), 4200, 400);
      const out = el("g", {}, s);
      rect(out, 1480, 110, 200, 290, { fill: css("--surface"), stroke: css("--line"), rx: 3 });
      txt(out, 1500, 146, "Отчёты", "g-lbl-b");
      ["JSON, HTML", "AsciiDoc", "Confluence", "SVG-графики", "Коды выхода CI"].forEach((n, i) => txt(out, 1500, 186 + i * 40, n, "g-lbl", { "font-size": 18 }));
      appear(out, 4400);
      // поток пакетов
      inputs.forEach(([, , y, hh], i) => packet(s, `M320 ${y + hh / 2} H350 V220 H1140`, css("--blue"), 3.2, 1300 + i * 500, { r: 6 }));
      packet(s, "M1430 232 H1480", css("--teal"), 1.2, 4800, { r: 6 });
    },
  };

  /* ================= 11. MCP ================= */
  hooks["s-mcp"] = {
    enter() {
      const s = clear($("#mcpSvg"));
      const ag = [["GigaCode", "ИИ-агент", 0]];
      const AH = 84, GAP = 35;
      ag.forEach(([n, sub, mine], i) => {
        const y = 280 - AH / 2 + i * (AH + GAP), cy = y + AH / 2;
        const g = el("g", {}, s);
        rect(g, 0, y, 330, AH, { fill: css("--surface"), stroke: css("--line"), rx: 3 });
        rect(g, 0, y, 6, AH, { fill: mine ? css("--teal") : css("--blue") });
        txt(g, 26, y + 38, n, "g-lbl-b", { "font-size": 22 });
        txt(g, 26, y + 66, sub, "g-lbl", { "font-size": 17, fill: css("--mute") });
        appear(g, 100 + i * 120);
        const p1 = path(s, `M330 ${cy - 9} H640`, { class: "g-conn-soft" }), p2 = path(s, `M330 ${cy + 9} H640`, { class: "g-conn-soft" });
        drawIn(p1, 600 + i * 100, 500); drawIn(p2, 650 + i * 100, 500);
        packet(s, `M330 ${cy - 9} H640`, css("--blue"), 1.8, 1500 + i * 600, { r: 6 });
        packet(s, `M330 ${cy + 9} H640`, css("--teal"), 1.8, 2400 + i * 600, { r: 6, rev: true });
      });
      // шина MCP
      const bus = el("g", {}, s);
      rect(bus, 640, 10, 90, 540, { fill: css("--band"), rx: 4 });
      txt(bus, 685, 280, "MCP", "", { fill: "#fff", "font-family": "IBM Plex Mono", "font-size": 30, "font-weight": 600, "text-anchor": "middle", transform: "rotate(-90 685 280)", dy: 10 });
      grow(bus, "y", 300, 700, "50% 50%");
      // Вердикт
      const v = el("g", {}, s);
      rect(v, 1030, 40, 650, 480, { fill: css("--bg"), stroke: css("--navy"), "stroke-width": 2, rx: 4 });
      rect(v, 1030, 40, 650, 60, { fill: css("--band"), rx: 4 });
      txt(v, 1056, 80, "Вердикт: что сможет запросить агент", "g-lbl-b", { fill: "#fff", "font-size": 22 });
      appear(v, 500);
      const tools = [["Вердикт по прогону", "PASS, FAIL или NO_VERDICT по политике"], ["Доказательства", "метрики, окна и ссылки на данные"], ["Сравнение с эталоном", "только при подтверждённых условиях"], ["Связи между метриками", "гипотезы с лагом и проверкой шума"]];
      tools.forEach(([t, sub], i) => {
        const y = 130 + i * 96, cy = y + 38;
        const g = el("g", {}, s);
        if (i) path(g, `M1056 ${y - 10} H1656`, { class: "g-grid" });
        txt(g, 1056, y + 30, t, "g-lbl-b", { "font-size": 21 });
        txt(g, 1056, y + 60, sub, "g-lbl", { "font-size": 18, fill: css("--mute") });
        appear(g, 900 + i * 180);
        const p1 = path(s, `M730 ${cy - 8} H1030`, { class: "g-conn-soft" }), p2 = path(s, `M730 ${cy + 8} H1030`, { class: "g-conn-soft" });
        drawIn(p1, 1000 + i * 120, 500); drawIn(p2, 1050 + i * 120, 500);
        packet(s, `M730 ${cy - 8} H1030`, css("--blue"), 1.6, 1900 + i * 650, { r: 6 });
        packet(s, `M730 ${cy + 8} H1030`, css("--teal"), 1.6, 2800 + i * 650, { r: 6, rev: true });
      });
      const lg = el("g", {}, s);
      el("circle", { cx: 760, cy: 536, r: 6, fill: css("--blue") }, lg); txt(lg, 774, 542, "запрос", "g-lbl", { "font-size": 16 });
      el("circle", { cx: 860, cy: 536, r: 6, fill: css("--teal") }, lg); txt(lg, 874, 542, "ответ с доказательствами", "g-lbl", { "font-size": 16 });
      appear(lg, 2000);
    },
  };

  /* ================= 12. Галерея ================= */
  const gal = { timer: 0, i: 0 };
  const SCREENS = [["01-overview", "Обзор прогона"], ["02-deep-trend", "Глубокий анализ"], ["03-deep-pods-two-shoulders", "Поды и два плеча"], ["04-new-analysis", "Новый анализ"], ["05-tables-trends", "Тренды"], ["06-tables-services", "Сервисы"], ["07-rules", "Правила"], ["08-history", "История релизов"], ["09-ai", "ИИ-разбор"], ["10-compare", "Сравнение прогонов"]];
  function showShot(i) {
    gal.i = i;
    $$("#tabs button").forEach((b, k) => { b.setAttribute("aria-selected", String(k === i)); const tm = $(".tm", b); tm.style.animation = "none"; void tm.offsetWidth; tm.style.animation = ""; });
    $$("#view img").forEach((im, k) => {
      if (k !== i) { im.classList.remove("on"); return; }
      const v = $("#view");
      const apply = () => { const h = v.clientWidth * im.naturalHeight / im.naturalWidth; im.style.setProperty("--shift", -Math.max(0, Math.min(h - v.clientHeight, 1000)) + "px"); };
      im.classList.remove("on"); void im.offsetWidth;
      im.complete && im.naturalWidth ? apply() : im.addEventListener("load", apply, { once: true });
      im.classList.add("on");
    });
  }
  hooks["s-ui"] = {
    enter() {
      const tabs = $("#tabs"), view = $("#view");
      if (!tabs.childElementCount) {
        SCREENS.forEach(([f, label], i) => {
          const b = document.createElement("button");
          b.innerHTML = `${label}<span class="tm"></span>`; b.setAttribute("role", "tab");
          b.addEventListener("click", () => { tabs.classList.add("manual"); clearInterval(gal.timer); showShot(i); });
          tabs.appendChild(b);
          const im = document.createElement("img"); im.src = `assets/screens/${f}.jpg`; im.alt = label; view.appendChild(im);
        });
      }
      tabs.classList.remove("manual");
      showShot(0);
      clearInterval(gal.timer);
      const c = ctx;
      gal.timer = setInterval(() => { if (c.dead) { clearInterval(gal.timer); return; } if (!tabs.classList.contains("manual")) showShot((gal.i + 1) % SCREENS.length); }, 9500);
    },
  };

  /* ================= 20. Целевая картина ================= */
  let tg = { items: [], tag: null, legend: null };
  hooks["s-target"] = {
    step(cur, n) {
      if (!tg.items.length) return;
      const LV = { 1: css("--lvl1"), 2: css("--lvl2"), 3: css("--lvl3") };
      const CAP = { 1: "Уровень 1 · сейчас: инструменты есть, но по отдельности; инженер ведёт каждый шаг сам", 2: "Уровень 2 · полуавтономные воркфлоу: инструменты собраны в один флоу по спеке", 3: "Уровень 3 · автономное НТ с одобрением человека" };
      const ST = {
        1: ["Сейчас инженер ведёт процесс сам: запускает каждый инструмент,", "переносит результаты между шагами и принимает все решения"],
        2: ["Воркфлоу на сервере соединяет инструменты по спеке;", "инженер контролирует результат на ключевых точках"],
        3: ["Человек только одобряет отчёт и принимает решение по релизу;", "остальное делает флоу"],
      };
      // стрелки: шаг 1 серый пунктир, шаг 2 сплошной синий, иначе исходный вид
      tg.arrows.forEach((a) => {
        const p = a.p;
        if (n === 1) { p.style.stroke = css("--mute"); p.style.strokeWidth = ""; p.style.strokeDasharray = "4 4"; p.setAttribute("marker-end", tg.mk.mute); }
        else if (n === 2) { p.style.stroke = LV[2]; p.style.strokeWidth = "2.5px"; p.style.strokeDasharray = a.da; p.setAttribute("marker-end", tg.mk.lvl2); }
        else { p.style.stroke = ""; p.style.strokeWidth = ""; p.style.strokeDasharray = a.da; p.setAttribute("marker-end", tg.mk.orig); }
      });
      tg.man.style.opacity = n === 1 ? 1 : 0;
      tg.wf.style.opacity = n >= 2 ? 1 : 0;
      tg.sh.style.opacity = n === 1 ? 0 : 1;
      tg.st.style.opacity = n >= 1 ? 1 : 0;
      if (n >= 1) {
        tg.st.rect.style.stroke = LV[n];
        tg.st.t.forEach((t, i) => { t.textContent = ST[n][i]; t.style.fill = LV[n]; });
      }
      tg.items.forEach((it) => {
        const r = it.r, texts = it.g.querySelectorAll("text");
        if (n === 0) {
          r.setAttribute("fill", it.fill); r.setAttribute("stroke", it.stroke);
          if (it.dash) r.setAttribute("stroke-dasharray", it.dash); else r.removeAttribute("stroke-dasharray");
          texts.forEach((t) => (t.style.fill = it.color || ""));
        } else if (it.level <= n) {
          const c = LV[it.level];
          r.setAttribute("fill", c); r.setAttribute("stroke", c); r.removeAttribute("stroke-dasharray");
          texts.forEach((t) => (t.style.fill = "#fff"));
        } else {
          r.setAttribute("fill", css("--surface-2")); r.setAttribute("stroke", css("--mute")); r.setAttribute("stroke-dasharray", "6 4");
          texts.forEach((t) => (t.style.fill = css("--mute")));
        }
      });
      tg.tag.style.opacity = n >= 2 ? 1 : 0;
      if (n >= 1) { tg.cap.textContent = CAP[n]; tg.cap.style.fill = LV[n]; }
      tg.cap.style.opacity = n >= 1 ? 1 : 0;
      // легенда: строки по порядку, пунктир скрывается на шаге 3
      let row = 0;
      tg.rows.forEach((rw) => {
        const on = rw.key === "future" ? n >= 1 && n < 3 : n >= rw.key;
        rw.g.style.opacity = on ? 1 : 0;
        rw.g.style.transform = `translateY(${on ? row * 20 : 0}px)`;
        if (on) row++;
      });
    },
    enter() {
      const s = clear($("#targetSvg"));
      tg = { items: [], tag: null, cap: null, rows: [], arrows: [], mk: {} };
      const reg = (g, level, color) => {
        const r = g.querySelector("rect");
        r.style.transition = "fill .3s, stroke .3s";
        tg.items.push({ g, r, level, color, fill: r.getAttribute("fill"), stroke: r.getAttribute("stroke"), dash: r.getAttribute("stroke-dasharray") });
      };
      const A = arrowDefs(s, "tA", css("--ink-2")), AW = arrowDefs(s, "tW", css("--warn")), AP = arrowDefs(s, "tP", css("--pass"));
      tg.mk = { orig: A, mute: arrowDefs(s, "tM", css("--mute")), lvl2: arrowDefs(s, "tL", css("--lvl2")) };
      const flowArrow = (d, delay, dur) => { const p = path(s, d, { class: "g-conn", "marker-end": A }); drawIn(p, delay, dur); tg.arrows.push({ p, da: p.style.strokeDasharray }); return p; };
      const lanes = [["Триггер", 0, 110], ["Автономный флоу на сервере", 110, 330], ["Инструменты через MCP", 330, 500], ["Человек", 500, 690]];
      lanes.forEach(([n, a, b], i) => {
        const g = el("g", {}, s);
        rect(g, 0, a, 1680, b - a, { fill: i % 2 ? css("--surface") : css("--bg") });
        rect(g, 0, a, 220, b - a, { fill: i === 3 ? css("--pass-soft") : css("--surface-2") });
        lines(g, 20, a + (b - a) / 2 - (n.length > 18 ? 4 : -6), n.length > 18 ? n.split(" ").reduce((acc, w) => { const l = acc[acc.length - 1]; if ((l + " " + w).trim().length <= 16) acc[acc.length - 1] = (l + " " + w).trim(); else acc.push(w); return acc; }, [""]) : [n], "g-lbl-b", 24, { "font-size": 19 });
        path(g, `M0 ${b} H1680`, { stroke: css("--line"), "stroke-width": 1 });
        appear(g, i * 120);
        if (i === 1) tg.tag = lines(g, 20, a + (b - a) / 2 + 52, ["серверный запуск:", "уровень 2"], "g-lbl", 18, { "font-size": 14, fill: css("--lvl2") });
      });
      const box = (x, y, w, h, t, o = {}) => {
        const g = el("g", {}, s);
        rect(g, x, y, w, h, { fill: o.fill || css("--bg"), stroke: o.stroke || css("--navy"), "stroke-width": o.sw || 1.5, rx: 3 });
        const ls = Array.isArray(t) ? t : [t];
        lines(g, x + w / 2, y + h / 2 + 7 - (ls.length - 1) * 11, ls, o.cls || "g-lbl-b", 23, { "text-anchor": "middle", "font-size": o.fs || 18, fill: o.color });
        return g;
      };
      const trig = box(270, 22, 260, 62, ["OpenSpec change:", "спека и задачи"]);
      appear(trig, 500); reg(trig, 2);
      const stepsX = [300, 540, 780, 1020, 1260], SW = 200;
      const names = [["Разработка", "скриптов"], ["Отладка"], ["Запуск теста"], ["Проверка логов", "и метрик"], ["Вердикт", "по прогону"]];
      const slv = [1, 1, 1, 2, 1];
      const sb = stepsX.map((x, i) => { const g = box(x, 175, SW, 80, names[i], { stroke: css("--blue"), sw: 2 }); appear(g, 700 + i * 180); reg(g, slv[i]); return g; });
      stepsX.slice(0, -1).forEach((x, i) => flowArrow(`M${x + SW} 215 H${stepsX[i + 1] - 4}`, 1000 + i * 180, 300));
      const rep = box(1500, 175, 160, 80, ["Отчёт и", "вердикт"], { fill: css("--blue-soft"), stroke: css("--blue"), sw: 2 });
      appear(rep, 1600); reg(rep, 2);
      flowArrow(`M${1260 + SW} 215 H1496`, 1700, 300);
      flowArrow("M400 84 V171", 800, 500);
      // подписи уровней над рядом шагов
      const lblT = (x, y, t, a) => { const e = txt(s, x, y, t, "g-lbl", Object.assign({ "text-anchor": "middle" }, a)); return e; };
      tg.man = el("g", {}, s);
      stepsX.slice(0, -1).forEach((x) => tg.man.appendChild(lblT(x + SW + 20, 168, "вручную", { "font-size": 13, fill: css("--mute") })));
      tg.wf = el("g", {}, s);
      tg.wf.appendChild(lblT(880, 166, "один воркфлоу на сервере", { "font-size": 14, "font-weight": 700, fill: css("--lvl2") }));
      [tg.man, tg.wf].forEach((g) => { g.style.opacity = 0; g.style.transition = "opacity .3s"; });
      tg.sh = el("g", {}, s);
      tg.sh.style.transition = "opacity .3s";
      const hl = path(tg.sh, "M1120 175 C1120 124, 640 124, 640 171", { stroke: css("--warn"), "stroke-width": 2.5, "stroke-dasharray": "7 5", fill: "none", "marker-end": AW });
      appear(hl, 2000);
      appear(txt(tg.sh, 880, 128, "self-healing: нашли причину, поправили, повторили", "g-lbl", { "font-size": 16, "text-anchor": "middle", fill: css("--warn") }), 2000);
      const tools = [["Скиллы Gatling, JMeter", "база знаний"], ["БД и код продукта", "сверка ошибок"], ["MCP SSH", "генераторы нагрузки"], ["RCA-агент", "логи OpenSearch"], ["Вердикт", "через MCP"]];
      tools.forEach((t, i) => {
        const x = stepsX[i];
        const g = box(x, 370, SW, 84, t, { fill: css("--teal-soft"), stroke: css("--teal"), cls: "g-lbl", fs: 17 });
        appear(g, 2200 + i * 160); reg(g, [1, 3, 1, 1, 2][i]);
        const p = path(s, `M${x + SW / 2} 259 V366`, { stroke: css("--teal"), "stroke-width": 2, "stroke-dasharray": "4 4", fill: "none" });
        appear(p, 2300 + i * 160);
        packet(s, `M${x + SW / 2 - 8} 259 V366`, css("--teal"), 1.2, 3000 + i * 300, { r: 5 });
        packet(s, `M${x + SW / 2 + 8} 259 V366`, css("--blue"), 1.2, 3600 + i * 300, { r: 5, rev: true });
      });
      const gate = box(1260, 560, 230, 76, ["Одобрение", "отчёта"], { fill: css("--pass-soft"), stroke: css("--pass"), sw: 3, color: css("--pass") });
      pop(gate, 2900); reg(gate, 3, css("--pass"));
      const dec = box(1520, 560, 150, 76, ["Решение", "по релизу"]);
      appear(dec, 3200); reg(dec, 3);
      drawIn(path(s, "M1580 259 V520 H1375 V556", { class: "g-conn", "marker-end": A }), 2700, 600);
      drawIn(path(s, "M1490 598 H1516", { stroke: css("--pass"), "stroke-width": 2.5, fill: "none", "marker-end": AP }), 3200, 300);
      const rej = path(s, "M1260 598 H760 V300 H600 V259", { stroke: css("--warn"), "stroke-width": 2.5, "stroke-dasharray": "7 5", fill: "none", "marker-end": AW });
      appear(rej, 3500);
      appear(txt(s, 1010, 588, "не одобрено: флоу возвращается к доработке", "g-lbl", { "font-size": 16, "text-anchor": "middle", fill: css("--warn") }), 3500);
      packet(s, "M400 84 V215 H1580 V520 H1375 V598 H1520", css("--blue"), 7, 1800, { r: 8 });
      // статус роли человека
      tg.st = el("g", {}, s);
      tg.st.style.opacity = 0; tg.st.style.transition = "opacity .3s";
      tg.st.rect = rect(tg.st, 310, 608, 920, 76, { fill: "none", "stroke-width": 2, rx: 6 });
      tg.st.rect.style.transition = "stroke .3s";
      tg.st.t = [0, 1].map((i) => { const t = txt(tg.st, 770, 638 + i * 24, "", "g-lbl", { "text-anchor": "middle", "font-size": 17 }); t.style.transition = "fill .3s"; return t; });
      // подпись уровня и легенда
      tg.cap = txt(s, 1660, 60, "", "g-lbl-b", { "font-size": 20, "text-anchor": "end" });
      tg.cap.style.transition = "opacity .3s, fill .3s";
      tg.cap.style.opacity = 0;
      [[1, "Уровень 1: есть по отдельности", css("--lvl1"), css("--lvl1")], [2, "Уровень 2: собрано в один воркфлоу", css("--lvl2"), css("--lvl2")], [3, "Уровень 3: цель", css("--lvl3"), css("--lvl3")], ["future", "ещё не достигнуто", css("--surface-2"), css("--mute"), "6 4"]].forEach(([key, l, f, st, d]) => {
        const g = el("g", {}, s);
        rect(g, 16, 618, 16, 16, { fill: f, stroke: st, "stroke-width": 1.5, rx: 2, "stroke-dasharray": d || undefined });
        txt(g, 40, 631, l, "g-lbl", { "font-size": 14 });
        g.style.opacity = 0; g.style.transition = "opacity .3s, transform .3s";
        tg.rows.push({ key, g });
      });
      tg.tag.style.opacity = 0; tg.tag.style.transition = "opacity .3s";
    },
  };

  /* ================= 4. НТ в OpenSpec ================= */
  hooks["s-sdd"] = {
    enter() {
      const s = clear($("#sddSvg"));
      const A = arrowDefs(s, "sdA", css("--ink-2")), AT = arrowDefs(s, "sdT", css("--teal")), AP = arrowDefs(s, "sdP", css("--pass"));
      const X0 = 160, CW = 260, H = 64, LW = 1200;
      const steps = [["explore", "исследовать"], ["propose", "предложить"], ["apply", "реализовать"], ["archive", "зафиксировать"]];
      const cx = (i) => X0 + i * CW + CW / 2;
      // шаги OpenSpec
      steps.forEach(([n, sub], i) => {
        const x = X0 + i * CW, a = i ? 18 : 0;
        const g = el("g", {}, s);
        path(g, `M${x} 0 H${x + CW - 18} L${x + CW} ${H / 2} L${x + CW - 18} ${H} H${x} L${x + a} ${H / 2} Z`, { fill: css("--band"), stroke: css("--bg"), "stroke-width": 4 });
        txt(g, x + 34, 28, "/opsx:" + n, "", { fill: "#fff", "font-family": "IBM Plex Mono", "font-size": 19, "font-weight": 600 });
        txt(g, x + 34, 52, sub, "", { fill: "#c8d6e6", "font-size": 15 });
        appear(g, 100 + i * 150);
      });
      txt(s, 0, 40, "OpenSpec", "g-lbl-b", { "font-size": 18, fill: css("--mute") });
      // дорожки
      const lanes = [[["Код продукта:", "команда", "и агенты"], 84, 234, css("--surface")], [["НТ: готовит", "ИИ-агент"], 252, 442, css("--teal-soft")], [["Люди: ревью", "и утверждение"], 460, 600, css("--pass-soft")]];
      lanes.forEach(([n, a, b, bg], i) => {
        const g = el("g", {}, s);
        rect(g, 0, a, LW, b - a, { fill: i === 1 ? css("--surface") : css("--bg") });
        rect(g, 0, a, 140, b - a, { fill: bg });
        lines(g, 10, a + (b - a) / 2 + 5 - (n.length - 1) * 10, n, "g-lbl-b", 20, { "font-size": 16 });
        path(g, `M0 ${b} H${LW}`, { stroke: css("--line"), "stroke-width": 1 });
        appear(g, 500 + i * 120);
      });
      const box = (i, y, h, t, o = {}) => {
        const x = X0 + i * CW + 10, w = CW - 20;
        const g = el("g", {}, s);
        rect(g, x, y, w, h, { fill: o.fill || css("--bg"), stroke: o.stroke || css("--navy"), "stroke-width": o.sw || 1.5, rx: 3, "stroke-dasharray": o.dash });
        const y0 = o.top ? y + o.top : y + h / 2 + 6 - (t.length - 1) * 11;
        lines(g, x + w / 2, y0, t, o.cls || "g-lbl", o.lh || 22, { "text-anchor": "middle", "font-size": o.fs || 16, fill: o.color });
        return g;
      };
      const harr = (i, y, delay, mk = A) => drawIn(path(s, `M${X0 + i * CW + CW - 10} ${y} H${X0 + (i + 1) * CW + 6}`, { class: "g-conn", "marker-end": mk }), delay, 300);
      // код продукта
      const cy1 = 159;
      const cb = [["варианты", "и план"], ["proposal, specs,", "design, tasks"], ["код по tasks.md,", "ревью PR"], ["specs обновлены:", "источник истины"]];
      cb.forEach((t, i) => { appear(box(i, 119, 80, t, i === 0 ? { stroke: css("--line"), color: css("--mute") } : { cls: "g-lbl-b", fs: 16 }), 800 + i * 150); if (i < 3) harr(i, cy1, 950 + i * 150); });
      // НТ: готовит ИИ-агент
      const NY = 282, NH = 130, cy2 = NY + NH / 2;
      const nt = [null,
        [["сценарий производительности", "в той же спеке:", "WHEN нагрузка → THEN p95"], "черновик: ИИ-агент"],
        [["скрипты и профиль из спеки,", "автономный прогон на main,", "вердикт как артефакт change"], "ИИ-агент + Вердикт"],
        [["CI-гейт на archive", "по вердикту,", "обновление baseline"], "детерминированно, без ИИ"]];
      nt.forEach((d, i) => {
        if (!d) return;
        const g = box(i, NY, NH, d[0], { fill: css("--bg"), stroke: css("--teal"), sw: 2, cls: "g-lbl-b", fs: 14, top: 30, lh: 21 });
        txt(g, X0 + i * CW + CW / 2, NY + NH - 14, d[1], "", { "font-size": 13, fill: css("--teal"), "text-anchor": "middle" });
        appear(g, 1500 + i * 220);
        if (i < 3) harr(i, cy2, 1700 + i * 220, AT);
      });
      lines(s, cx(0), cy2 - 2, ["НТ ещё нет:", "спека не утверждена"], "g-lbl", 20, { "text-anchor": "middle", "font-size": 14, fill: css("--mute") });
      // люди: роль и действие
      const PY = 485, PH = 90;
      const hb = [null,
        ["Владелец продукта утверждает", ["требования и пороги;", "аналитик и архитектор ревьюят"]],
        ["Инженер НТ или разработчик", ["ревьюит скрипты, профиль", "и пороги агента"]],
        ["Владелец продукта", ["одобряет отчёт,", "решает по релизу"]]];
      hb.forEach((d, i) => {
        if (!d) return;
        const g = box(i, PY, PH, [], { fill: css("--pass-soft"), stroke: css("--pass"), sw: 2 });
        const mx = X0 + i * CW + CW / 2;
        txt(g, mx, PY + 28, d[0], "", { "font-size": 15, "font-weight": 600, fill: css("--pass"), "text-anchor": "middle" });
        lines(g, mx, PY + 50, d[1], "", 18, { "font-size": 14, fill: css("--ink-2"), "text-anchor": "middle" });
        appear(g, 2600 + i * 200);
      });
      // связи между дорожками
      const v = (i, y1, y2, delay, mk, lbl, color) => {
        const x = cx(i);
        drawIn(path(s, `M${x} ${y1} V${y2}`, { stroke: color, "stroke-width": 2, "stroke-dasharray": "5 4", fill: "none", "marker-end": mk }), delay, 400);
        if (lbl) appear(txt(s, x + 10, (y1 + y2) / 2 + 5, lbl, "g-lbl", { "font-size": 14, fill: color }), delay + 200);
      };
      v(1, 199, NY - 4, 1400, AT, "одна спека", css("--teal"));
      v(3, NY - 4, 203, 2300, A, "гейт", css("--ink-2"));
      v(1, PY - 2, NY + NH + 4, 2900, AP, null, css("--pass"));
      v(2, PY - 2, NY + NH + 4, 3100, AP, null, css("--pass"));
      v(3, PY - 2, NY + NH + 4, 3300, AP, null, css("--pass"));
      // поток
      packet(s, `M${cx(1)} 199 V${NY - 4}`, css("--teal"), 1.4, 2200, { r: 7 });
      packet(s, `M${cx(3)} ${NY} V203`, css("--blue"), 1.2, 4200, { r: 6 });
      // легенда
      appear(txt(s, 0, 630, "ИИ-агент готовит любой артефакт · люди ревьюят и утверждают · вердикт считается детерминированно", "g-lbl", { "font-size": 15, fill: css("--mute") }), 3600);
    },
  };

  /* ================= 6. Гейты: лестница ================= */
  hooks["s-gates"] = {
    enter() {
      const st = $$("#gateSteps .rung");
      st.forEach((s) => s.classList.remove("act", "done"));
      st.forEach((s, i) => after(300 + i * 700, () => {
        st.forEach((x, k) => { x.classList.toggle("act", k === i); x.classList.toggle("done", k < i); });
        if (i === st.length - 1) after(900, () => st.forEach((x) => { x.classList.remove("act"); x.classList.add("done"); }));
      }));
    },
  };


  /* ================= v3-wow: граф экосистемы (упрощённый) ================= */
  let arch = null;
  hooks["s-arch"] = {
    enter() {
      const svg = clear($("#archSvg"));
      const A = { blue: css("--blue"), teal: css("--teal"), pass: css("--pass"), warn: css("--warn"), mute: css("--mute"), ink: css("--ink"), ink2: css("--ink-2"), bg: css("--bg"), surf: css("--surface"), bsoft: css("--blue-soft"), psoft: css("--pass-soft"), tsoft: css("--teal-soft"), navy: css("--navy") };
      const MK = { ink2: arrowDefs(svg, "aI", A.ink2), blue: arrowDefs(svg, "aB", A.blue), warn: arrowDefs(svg, "aW", A.warn), pass: arrowDefs(svg, "aP", A.pass) };
      const g = [0, 1, 2].map(() => el("g", { style: "display:none" }, svg));
      const nodes = {}, edges = [];
      const dim = (id) => {
        const nb = new Set([id]);
        edges.forEach((e) => { if (e.a === id || e.b === id) { nb.add(e.a); nb.add(e.b); } });
        Object.keys(nodes).forEach((k) => { nodes[k].style.opacity = nb.has(k) ? 1 : .2; });
        edges.forEach((e) => { e.el.style.opacity = (e.a === id || e.b === id) ? 1 : .08; });
      };
      const undim = () => { Object.keys(nodes).forEach((k) => { nodes[k].style.opacity = 1; }); edges.forEach((e) => { e.el.style.opacity = 1; }); };
      const node = (p, id, cx, cy, w, h, ls, o = {}) => {
        const outer = el("g", { style: "cursor:pointer;transition:opacity .25s" }, p);
        const inner = el("g", {}, outer);
        rect(inner, cx - w / 2, cy - h / 2, w, h, { rx: 6, fill: o.fill || A.bg, stroke: o.stroke || A.blue, "stroke-width": o.sw || 2 });
        const n = ls.length, lh = 24, y0 = cy - ((n - 1) * lh) / 2 + 7;
        ls.forEach((s, i) => txt(inner, cx, y0 + i * lh, s, i === 0 ? "g-lbl-b" : "g-lbl", { "text-anchor": "middle", "font-size": i === 0 ? 21 : 16, fill: i === 0 ? (o.color || A.ink) : A.mute }));
        outer.addEventListener("mouseenter", () => dim(id));
        outer.addEventListener("mouseleave", undim);
        nodes[id] = outer;
        return inner;
      };
      // линия рисуется, наконечник появляется только после неё
      const edge = (p, a, b, d, o, delay, dur) => {
        const e = path(p, d, { fill: "none", stroke: o.color, "stroke-width": o.w || 2.2, "stroke-dasharray": o.dash, style: "transition:opacity .25s" });
        edges.push({ a, b, el: e });
        if (o.dash) { appear(e, delay, dur).finished.then(() => e.setAttribute("marker-end", o.mk)).catch(() => {}); }
        else { drawIn(e, delay, dur).finished.then(() => { e.style.strokeDasharray = ""; e.setAttribute("marker-end", o.mk); }).catch(() => {}); }
        return e;
      };
      arch = { g, A, MK, node, edge, built: [false, false, false] };
    },
    step(cur, n) {
      const a = arch; if (!a) return;
      const { g, A, MK, node, edge } = a;
      // x-центры инструментов; системы стоят строго под ними
      const X = { kb: 125, sc: 385, pb: 645, ssh: 905, rca: 1150 };
      const stage = [
        // 0. харнесс и шаги процесса
        () => {
          const p = g[0];
          appear(rect(p, 0, 70, 1275, 250, { rx: 8, fill: A.bsoft, stroke: A.blue, "stroke-width": 2 }), 0, 400);
          appear(txt(p, 24, 106, "НТ-харнесс", "g-lbl-b", { fill: A.navy, "font-size": 22 }), 100);
          appear(txt(p, 160, 106, "бутстрап репозитория: скиллы, MCP, субагенты, плейбуки", "g-lbl", { "font-size": 17, fill: A.ink2 }), 150);
          const flow = `M${X.kb} 210 H${X.rca}`;
          const T = [["kb", ["База знаний"]], ["sc", ["Скрипты", "Gatling · JMeter · GigaMeter"]], ["pb", ["Плейбуки", "self-healing"]], ["ssh", ["MCP SSH"]], ["rca", ["RCA-агент"]]];
          const order = Object.keys(X);
          order.slice(0, -1).forEach((k, i) => edge(p, k, order[i + 1], `M${X[k] + 112} 210 H${X[order[i + 1]] - 114}`, { color: A.ink2, mk: MK.ink2 }, 700 + i * 120, 300));
          if (!reduced) packet(p, flow, A.blue, 4, 1400, { r: 7 });
          T.forEach(([id, ls], i) => pop(node(p, id, X[id], 210, 220, 84, ls), 250 + i * 90));
        },
        // 1. код продукта и инфраструктура
        () => {
          const p = g[1];
          appear(rect(p, 0, 352, 1275, 48, { rx: 6, fill: A.bsoft, stroke: A.blue, "stroke-width": 2.5 }), 0, 400);
          appear(txt(p, 170, 383, "Сервер воркфлоу и MCP — для пилота", "g-lbl-b", { "font-size": 19, fill: A.blue }), 100);
          const S = [["know", "kb", X.kb, ["Знания", "МЕТА · Jira · Confluence"], "live"], ["db", "pb", X.pb, ["БД и стенд", "нужно для пилота"], "ask"], ["gen", "ssh", X.ssh, ["Генераторы нагрузки", "по SSH"], "live"], ["logs", "rca", X.rca, ["Логи бэкенда", "OpenSearch"], "live"], ["code", "rca", 1430, ["Код продукта", "актуальная ветка"], "live"]];
          S.forEach(([id, t, cx, ls, kind], i) => {
            const ask = kind === "ask";
            pop(node(p, id, cx, 540, 230, 84, ls, ask ? { fill: A.bsoft, stroke: A.blue, sw: 3 } : { stroke: A.pass }), 200 + i * 100);
            const d = cx === X[t] ? `M${cx} 252 V494` : `M${X[t] + 60} 252 C${X[t] + 60} 400, ${cx} 380, ${cx} 494`;
            edge(p, t, id, d, { color: ask ? A.blue : A.pass, mk: ask ? MK.blue : MK.pass, w: 2.5 }, 400 + i * 100, 500);
          });
          const L = el("g", {}, p);
          [[0, A.pass, "работает"], [210, A.blue, "нужно для пилота"]].forEach(([x, c, s]) => { path(L, `M${x} 690 H${x + 44}`, { stroke: c, "stroke-width": 3, fill: "none" }); txt(L, x + 56, 696, s, "g-lbl", { "font-size": 17, fill: A.ink2 }); });
          appear(L, 1100);
        },
        // 2. Вердикт и человек
        () => {
          const p = g[2];
          const ver = node(p, "ver", 1500, 210, 290, 96, ["Вердикт", "PASS · FAIL · NO_VERDICT"], { fill: A.tsoft, stroke: A.teal, sw: 3 });
          pop(ver, 0);
          if (!reduced) ver.firstChild.animate([{ filter: "drop-shadow(0 0 2px rgba(69,214,198,.2))" }, { filter: "drop-shadow(0 0 22px rgba(69,214,198,.75))" }], { duration: 1600, delay: 700, iterations: Infinity, direction: "alternate", easing: "ease-in-out" });
          edge(p, "rca", "ver", "M1275 210 H1352", { color: A.warn, dash: "8 6", mk: MK.warn, w: 2.6 }, 250, 300);
          appear(txt(p, 1313, 192, "MCP", "g-lbl-b", { "text-anchor": "middle", "font-size": 16, fill: A.warn }), 350);
          pop(node(p, "hum", 1500, 345, 290, 64, ["Человек одобряет отчёт"], { fill: A.psoft, stroke: A.pass, sw: 3, color: A.pass }), 450);
          edge(p, "ver", "hum", "M1500 258 V309", { color: A.pass, mk: MK.pass, w: 2.6 }, 650, 250);
          const L = el("g", {}, p);
          path(L, "M440 690 H484", { stroke: A.warn, "stroke-width": 3, "stroke-dasharray": "8 6", fill: "none" }); txt(L, 496, 696, "в разработке", "g-lbl", { "font-size": 17, fill: A.ink2 });
          appear(L, 500);
        },
      ];
      for (let k = 0; k <= 2; k++) {
        if (n >= k && !a.built[k]) { a.built[k] = true; stage[k](); }
        g[k].style.display = n >= k ? "" : "none";
      }
    },
  };

  /* ================= v3-wow: Зелёный коридор ================= */
  let zk = { tok: 0 };
  hooks["s-zk"] = {
    enter() {
      zk.tok++;
      const boxes = $$("#s-zk .cyc > div"), lp = $("#s-zk .loop");
      boxes.forEach((b) => b.classList.remove("hot", "done"));
      lp.classList.remove("on");
      boxes.forEach((b, i) => b.animate([{ opacity: 0, transform: "translateY(10px)" }, { opacity: 1, transform: "none" }], { duration: D(450), delay: D(150 + i * 130), fill: "backwards", easing: E }));
      if (reduced) { boxes[5].classList.add("done"); return; }
      let k = 0, cycles = 0;
      const tick = () => {
        boxes.forEach((b) => b.classList.remove("hot"));
        const i = k % 6; boxes[i].classList.add("hot");
        lp.classList.toggle("on", i === 5 && cycles < 3);
        if (i === 5) { if (cycles >= 3) { boxes[5].classList.remove("hot"); boxes[5].classList.add("done"); lp.classList.remove("on"); return; } cycles++; }
        k++; after(i === 5 ? 1400 : 800, tick);
      };
      after(1300, tick);
    },
    step(cur, n) {
      const svg = clear($("#zkSvg"));
      const tok = ++zk.tok;
      if (n < 1) return;
      const at = (ms, fn) => after(ms, () => { if (tok === zk.tok) fn(); });
      const col = { pass: css("--pass"), fail: css("--fail"), blue: css("--blue"), warn: css("--warn"), mute: css("--mute"), ink: css("--ink"), ink2: css("--ink-2"), line: css("--line"), navy: css("--navy"), psoft: css("--pass-soft") };
      const MA = arrowDefs(svg, "zA", col.pass);
      const X0 = 90, W = 1380, Y0 = 350, H = 320;
      const px = (u) => X0 + u * W, py = (v) => Y0 - v * H;
      const val = (u) => .18 + .78 * Math.pow(1 - u, 3);
      const THR = .42;
      path(svg, `M${X0} 20 V${Y0} H${X0 + W + 30}`, { stroke: col.ink2, "stroke-width": 1.8, fill: "none" });
      txt(svg, 30, 190, "p95", "g-lbl", { "font-size": 17, fill: col.ink2, transform: "rotate(-90 30 190)", "text-anchor": "middle" });
      txt(svg, X0, 402, "ресурсы контейнера и БД (CPU, RAM) →", "g-lbl", { "text-anchor": "start", "font-size": 17, fill: col.ink2 });
      const thr = path(svg, `M${X0} ${py(THR)} H${X0 + W + 30}`, { stroke: col.warn, "stroke-width": 2, "stroke-dasharray": "8 6", fill: "none" }); appear(thr, 300);
      appear(txt(svg, X0 + 10, py(THR) - 10, "порог из политики", "g-lbl", { "font-size": 17, fill: col.warn }), 400);
      let d = ""; for (let i = 2; i <= 100; i += 2) { const u = i / 100; d += (i === 2 ? "M" : "L") + px(u).toFixed(1) + " " + py(val(u)).toFixed(1); }
      drawIn(path(svg, d, { stroke: col.blue, "stroke-width": 3.5, fill: "none", "stroke-linecap": "round" }), 500, 1100);
      appear(txt(svg, px(.5), 30, "иллюстрация, данные условные", "g-lbl", { "text-anchor": "middle", "font-size": 15, fill: col.mute }), 600);
      // пробные прогоны
      const P = [.95, .78, .62, .48, .38, .28];
      let prev = null;
      P.forEach((u, i) => {
        at(1600 + i * 750, () => {
          const v = val(u), ok = v <= THR, g2 = el("g", {}, svg);
          const c = el("circle", { cx: px(u), cy: py(v), r: 15, fill: ok ? col.pass : col.fail }, g2);
          txt(g2, px(u), py(v) + 6, String(i + 1), "g-lbl-b", { "text-anchor": "middle", "font-size": 16, fill: "#fff" });
          pop(g2, 0, 350);
          if (i === P.length - 1) {
            // возврат на последний PASS и оптимум
            const u2 = P[i - 1];
            at(700, () => {
              path(svg, `M${px(u) + 18} ${py(val(u)) - 4} Q${(px(u) + px(u2)) / 2} ${py(val(u)) - 60} ${px(u2) - 4} ${py(val(u2)) - 22}`, { stroke: col.fail, "stroke-width": 2.2, "stroke-dasharray": "6 5", fill: "none", "marker-end": arrowDefs(svg, "zF", col.fail) });
              at(600, () => {
                const fl = el("g", {}, svg);
                path(fl, `M${px(u2)} ${py(val(u2)) + 18} V${Y0}`, { stroke: col.pass, "stroke-width": 2.5, "stroke-dasharray": "5 4", fill: "none" });
                const lbl = el("g", {}, fl);
                path(lbl, `M${px(u2)} ${py(val(u2)) - 18} V${py(val(u2)) - 105}`, { stroke: col.pass, "stroke-width": 2, fill: "none" });
                rect(lbl, px(u2) - 10, py(val(u2)) - 145, 350, 40, { rx: 4, fill: col.psoft, stroke: col.pass, "stroke-width": 2 });
                txt(lbl, px(u2) + 6, py(val(u2)) - 118, "оптимум: минимум ресурсов с PASS", "g-lbl-b", { "font-size": 18, fill: col.pass });
                pop(fl, 0, 450);
                at(500, () => {
                  const b = el("g", {}, svg), by = 376;
                  path(b, `M${px(.95)} ${by} H${px(u2) + 4}`, { stroke: col.pass, "stroke-width": 2.5, fill: "none", "marker-end": MA });
                  txt(b, (px(.95) + px(u2)) / 2, by + 24, "освобождаемые ресурсы", "g-lbl-b", { "text-anchor": "middle", "font-size": 18, fill: col.pass });
                  appear(b, 0, 500);
                });
              });
            });
          }
          prev = u;
        });
      });
    },
  };

  /* ---------- Старт ---------- */
  const [hs, hst] = location.hash.slice(1).split(".");
  const h = parseInt(hs, 10);
  go(h >= 1 && h <= slides.length ? h - 1 : 0, 0);
  if (hst) setStep(Math.min(+hst || 0, maxStep(idx)));
  if (!FINAL) document.body.classList.add("hud");
  setTimeout(() => document.body.classList.remove("hud"), 3000);
})();
