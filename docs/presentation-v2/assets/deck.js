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

  try { const t = localStorage.getItem("ltv2-theme"); if (t) document.documentElement.dataset.theme = t; } catch (e) {}
  { const m = location.search.match(/[?&]theme=(dark|light)/); if (m) document.documentElement.dataset.theme = m[1]; }
  function toggleTheme() {
    const d = document.documentElement;
    d.dataset.theme = d.dataset.theme === "dark" ? "light" : "dark";
    try { localStorage.setItem("ltv2-theme", d.dataset.theme); } catch (e) {}
    go(idx, step);
  }

  const isSkipped = (i) => skipDraft && slides[i].dataset.draft === "1";
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
    li.textContent = s.dataset.title + (s.dataset.draft ? " (черновик)" : "");
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
    else if (k === "d" || k === "в") { skipDraft = !skipDraft; toast(skipDraft ? "Черновой слайд пропускается" : "Черновой слайд включён"); }
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
      const r = rng(5), hot = new Set([3, 10, 19, 26]);
      for (let i = 0; i < 30; i++) {
        const d = document.createElement("div");
        d.className = "th" + (hot.has(i) ? " hot" : "");
        let y = 18 + r() * 8; const pts = [];
        for (let x = 0; x <= 100; x += 4) { y += (r() - .5) * 6 - (hot.has(i) && x > 50 ? 1.1 : 0); y = clamp(y, 3, 33); pts.push(`${x} ${y.toFixed(1)}`); }
        d.innerHTML = `<svg viewBox="0 0 100 40" preserveAspectRatio="none"><path class="ax" d="M0 39H100"/><path class="ln" d="M${pts.join(" L")}"/></svg>`;
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
      const names = ["Подготовка", "Разработка скриптов", "Запуск и отладка", "Анализ"];
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
        p.animate([{ fill: css("--surface-2") }, { fill: css("--blue") }], { duration: D(400), delay: D(1500 + i * 450), fill: "both" });
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
      const ag = [["Qwen Code", "внешний агент", 0], ["Claude Code", "внешний агент", 0], ["GigaCode", "внешний агент", 0], ["RCA-агент", "наш инструмент", 1], ["Агент скриптов", "наш инструмент", 1]];
      const AH = 84, GAP = 35;
      ag.forEach(([n, sub, mine], i) => {
        const y = i * (AH + GAP), cy = y + AH / 2;
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

  /* ================= Общая разметка графика ================= */
  function frame(s, o) {
    // o: {x0,x1,y0,y1, xl, yl, yt:[[v,label]], xt:[[u,label]]}
    const g = el("g", {}, s);
    (o.yt || []).forEach(([y, l]) => { path(g, `M${o.x0} ${y} H${o.x1}`, { class: "g-grid" }); if (l) txt(g, o.x0 - 8, y + 5, l, "g-tick", { "text-anchor": "end" }); });
    path(g, `M${o.x0} ${o.y0 - 6} V${o.y1} H${o.x1 + 6}`, { class: "g-ax" });
    (o.xt || []).forEach(([x, l]) => { path(g, `M${x} ${o.y1} V${o.y1 + 5}`, { class: "g-ax" }); txt(g, x, o.y1 + 22, l, "g-tick", { "text-anchor": "middle" }); });
    if (o.yl) txt(g, o.x0 + 6, o.y0 + 4, o.yl, "g-tick");
    if (o.xl) txt(g, o.x1, o.y1 + 24, o.xl, "g-tick", { "text-anchor": "end" });
    appear(g, 0, 400);
    return g;
  }

  /* ================= 13. Статистика: измерение ================= */
  hooks["s-stat1"] = {
    enter() {
      // А: окно устойчивого состояния
      let s = clear($("#st1a"));
      const x0 = 50, x1 = 500, y0 = 40, y1 = 260;
      const a = 130, b = 420;
      const win = rect(s, a, y0, b - a, y1 - y0, { class: "g-win" });
      frame(s, { x0, x1, y0, y1, xl: "время" });
      grow(win, "x", 1500, 800);
      const load = path(s, `M${x0} ${y1 - 10} L${a} 110 L${b} 110 L480 ${y1 - 10}`, { class: "g-main" });
      drawIn(load, 300, 1100);
      const r = rng(9); let d = `M${x0} 210`;
      for (let x = x0 + 6; x <= 480; x += 6) {
        let v = 200;
        if (x < a) v = 200 - 150 * Math.sin(Math.PI * (x - x0) / (a - x0)) + (r() - .5) * 10;
        else if (x <= b) v = 168 + (r() - .5) * 12;
        else v = 168 + (x - b) * .3 + (r() - .5) * 10;
        d += ` L${x} ${v.toFixed(1)}`;
      }
      drawIn(path(s, d, { class: "g-sec", "stroke-width": 2 }), 800, 1300);
      [a, b].forEach((x) => appear(path(s, `M${x} ${y0} V${y1}`, { stroke: css("--blue"), "stroke-dasharray": "4 4", "stroke-width": 1.5 }), 1500));
      appear(txt(s, (a + b) / 2, 26, "окно оценки", "g-lbl-b", { "text-anchor": "middle", fill: css("--blue"), "font-size": 18 }), 2100);
      appear(txt(s, (x0 + a) / 2, 26, "разгон", "g-lbl", { "text-anchor": "middle", "font-size": 16 }), 2100);
      appear(txt(s, 465, 26, "спад", "g-lbl", { "text-anchor": "middle", "font-size": 16 }), 2100);
      const lg = el("g", {}, s);
      path(lg, "M300 286 H322", { class: "g-main" }); txt(lg, 328, 291, "нагрузка", "g-tick");
      path(lg, "M430 286 H452", { class: "g-sec", "stroke-width": 2 }); txt(lg, 458, 291, "p95", "g-tick");
      lg.setAttribute("transform", "translate(-250 0)");
      appear(lg, 2400);

      // Б: перцентиль по гистограмме
      s = clear($("#st1b"));
      const bx0 = 50, bx1 = 500, by0 = 40, by1 = 250;
      frame(s, { x0: bx0, x1: bx1, y0: by0, y1: by1, xl: "время отклика" });
      const nb = 22, bw = (bx1 - bx0 - 20) / nb;
      const hist = [];
      for (let i = 0; i < nb; i++) { const xx = (i + .5) / nb * 4.2; hist.push(Math.pow(xx, 1.6) * Math.exp(-xx * 1.55)); }
      const mx = Math.max(...hist);
      hist.forEach((h, i) => {
        const hh = h / mx * 125;
        const rr = rect(s, bx0 + 10 + i * bw, by1 - hh, bw - 3, hh, { fill: i >= 16 ? css("--blue") : css("--blue-2"), opacity: i >= 16 ? 1 : .55 });
        grow(rr, "y", 300 + i * 45, 500);
      });
      const p95x = bx0 + 10 + 16 * bw;
      const avgx = bx0 + 10 + 12.6 * bw;
      appear(path(s, `M${p95x} ${by0 + 10} V${by1}`, { stroke: css("--blue"), "stroke-width": 2.5 }), 1700);
      appear(lines(s, p95x + 8, by0 + 22, ["p95 по всем", "замерам"], "g-lbl-b", 19, { "font-size": 16, fill: css("--blue") }), 1700);
      appear(path(s, `M${avgx} ${by0 + 50} V${by1}`, { stroke: css("--mute"), "stroke-width": 2, "stroke-dasharray": "5 4" }), 2400);
      appear(lines(s, avgx - 8, by0 + 44, ["среднее p95", "по подам"], "g-lbl", 19, { "font-size": 15, "text-anchor": "end", fill: css("--mute") }), 2400);
      const br = path(s, `M${avgx} ${by0 + 76} H${p95x - 2}`, { stroke: css("--fail"), "stroke-width": 2, "marker-end": arrowDefs(s, "b1F", css("--fail")) });
      appear(br, 2900);
      appear(txt(s, p95x + 8, by0 + 82, "занижение", "g-lbl-b", { "font-size": 15, fill: css("--fail") }), 2900);
      const cnt = txt(s, bx0 + 8, 22, "n = 0 замеров", "g-tick");
      loop((t) => { const v = Math.round(1240 * ease(clamp((t - .3) / 1.4))); cnt.textContent = `n = ${v.toLocaleString("ru-RU")} замеров`; return t < 2; });

      // В: достаточность выборки
      s = clear($("#st1c"));
      const cx0 = 30, cx1 = 490, N = 140, sx = (v) => cx0 + v / N * (cx1 - cx0);
      const zones = [[0, 20, css("--fail-soft"), css("--fail"), "NO_VERDICT"], [20, 100, css("--warn-soft"), css("--warn"), "SMALL_SAMPLE"], [100, 140, css("--pass-soft"), css("--pass"), "полный"]];
      zones.forEach(([a1, b1, bg, fg, l], i) => {
        const g = el("g", {}, s);
        rect(g, sx(a1), 90, sx(b1) - sx(a1), 56, { fill: bg });
        rect(g, sx(a1), 90, sx(b1) - sx(a1), 5, { fill: fg });
        txt(g, i === 0 ? sx(a1) : (sx(a1) + sx(b1)) / 2, 80, l, "", { "font-family": "IBM Plex Mono", "font-size": 15, "font-weight": 600, fill: fg, "text-anchor": i === 0 ? "start" : "middle" });
        grow(g, "x", 200 + i * 250, 500);
      });
      [0, 20, 100, 140].forEach((v) => { path(s, `M${sx(v)} 146 V156`, { class: "g-ax" }); txt(s, sx(v), 176, String(v), "g-tick", { "text-anchor": "middle" }); });
      txt(s, cx1, 200, "число замеров в окне", "g-tick", { "text-anchor": "end" });
      const cur = el("g", {}, s);
      path(cur, "M0 96 V150", { stroke: css("--navy"), "stroke-width": 3 });
      path(cur, "M-9 48 L9 48 L0 60 Z", { fill: css("--band") });
      const nl = txt(cur, 0, 40, "n = 0", "g-lbl-b", { "text-anchor": "middle", "font-size": 17 });
      const pill = el("g", {}, s);
      const pr = rect(pill, 30, 226, 460, 52, { rx: 3 });
      const pt = txt(pill, 260, 259, "", "g-lbl-b", { "text-anchor": "middle", "font-size": 19 });
      loop((t) => {
        const n = Math.round(N * ease(clamp((t - 1) / 3.2)));
        cur.setAttribute("transform", `translate(${sx(n)} 0)`);
        nl.textContent = `n = ${n}`;
        const z = n < 20 ? 0 : n < 100 ? 1 : 2;
        const lab = ["NO_VERDICT: замеров недостаточно", "вердикт с пометкой «малая выборка»", "полный вердикт"][z];
        pr.setAttribute("fill", zones[z][2]); pr.setAttribute("stroke", zones[z][3]); pt.setAttribute("fill", zones[z][3]); pt.textContent = lab;
        return t < 4.4;
      });
    },
  };

  /* ================= 14. Статистика: диагностика ================= */
  hooks["s-stat2"] = {
    enter() {
      // Г: сопоставимость
      let s = clear($("#st2a"));
      const checks = ["Эталон со статусом PASS", "Политика совпадает", "Профиль условий совпадает", "Эталон ≠ текущий прогон"];
      checks.forEach((c, i) => {
        const g = el("g", {}, s), y = 34 + i * 34;
        rect(g, 24, y - 16, 22, 22, { fill: css("--pass-soft"), stroke: css("--pass"), rx: 2 });
        const ck = path(g, `M29 ${y - 5} l5 5 l8 -10`, { stroke: css("--pass"), "stroke-width": 2.5, fill: "none" });
        txt(g, 58, y + 1, c, "g-lbl", { "font-size": 17 });
        appear(g, 200 + i * 350, 300);
        drawIn(ck, 400 + i * 350, 300);
      });
      const base = 280, gx0 = 30, gx1 = 500;
      path(s, `M${gx0} ${base} H${gx1}`, { class: "g-ax" });
      const dens = (mu, sd, sc) => { let d = ""; for (let x = gx0; x <= gx1; x += 5) { const u = (x - gx0) / (gx1 - gx0) * 10; const y = base - sc * Math.exp(-((u - mu) ** 2) / (2 * sd * sd)); d += (d ? " L" : "M") + `${x} ${y.toFixed(1)}`; } return d; };
      const pb = path(s, dens(4.2, 1.05, 100), { stroke: css("--mute"), "stroke-width": 2.5, fill: "none" });
      const pc = path(s, dens(4.9, 1.1, 96), { class: "g-main" });
      drawIn(pb, 1800, 900); drawIn(pc, 2200, 900);
      appear(txt(s, 150, 192, "эталон", "g-lbl", { "font-size": 16, fill: css("--mute") }), 2400);
      appear(txt(s, 300, 186, "текущий прогон", "g-lbl-b", { "font-size": 16, fill: css("--blue") }), 2700);
      const ok = el("g", {}, s);
      rect(ok, 330, 14, 170, 34, { fill: css("--blue"), rx: 3 });
      txt(ok, 415, 37, "сравнение разрешено", "", { fill: "#fff", "font-size": 15, "font-weight": 600, "text-anchor": "middle" });
      pop(ok, 1700);

      // Д: тренд
      s = clear($("#st2b"));
      const x0 = 50, x1 = 500, y0 = 30, y1 = 260;
      frame(s, { x0, x1, y0, y1, yl: "память, % лимита", xl: "время" });
      appear(path(s, `M${x0} 62 H${x1}`, { class: "g-thr" }), 200);
      appear(txt(s, x1, 54, "порог SLA", "g-lbl", { "font-size": 15, "text-anchor": "end", fill: css("--fail") }), 200);
      const r = rng(31); let d = ""; const pts = [];
      for (let i = 0; i <= 60; i++) { const x = x0 + 10 + i * 7.3; const y = 220 - i * 1.25 + (r() - .5) * 26; pts.push([x, y]); d += (d ? " L" : "M") + `${x.toFixed(1)} ${y.toFixed(1)}`; }
      drawIn(path(s, d, { stroke: css("--mute"), "stroke-width": 1.6, fill: "none" }), 400, 1200);
      const m1 = pts.slice(0, 30).reduce((a, p) => a + p[1], 0) / 30, m2 = pts.slice(31).reduce((a, p) => a + p[1], 0) / 30;
      const hx = pts[30][0];
      const h1 = path(s, `M${pts[0][0]} ${m1} H${hx}`, { stroke: css("--teal"), "stroke-width": 4 });
      const h2 = path(s, `M${hx} ${m2} H${pts[60][0]}`, { stroke: css("--teal"), "stroke-width": 4 });
      grow(h1, "x", 1800, 500); grow(h2, "x", 2100, 500);
      const sh = path(s, `M${hx + 14} ${m1} V${m2 + 6}`, { stroke: css("--teal"), "stroke-width": 2, "marker-end": arrowDefs(s, "trT", css("--teal")) });
      appear(sh, 2600);
      appear(txt(s, hx + 22, (m1 + m2) / 2 + 6, "сдвиг половин", "g-lbl-b", { "font-size": 15, fill: css("--teal") }), 2600);
      drawIn(path(s, `M${pts[0][0]} ${pts[0][1] + 4} L${pts[60][0]} ${pts[60][1] - 4}`, { stroke: css("--blue"), "stroke-width": 2.5, "stroke-dasharray": "none" }), 3000, 800);
      appear(txt(s, x0 + 14, 96, "наклон > 0 и сдвиг > 0: рост в пределах SLA", "g-lbl-b", { "font-size": 15, fill: css("--blue") }), 3700);

      // Е: ёмкость
      s = clear($("#st2c"));
      const cx0 = 60, cx1 = 500, cy0 = 30, cy1 = 250;
      const steps = [20, 40, 60, 80, 100], sw = (cx1 - cx0) / steps.length;
      frame(s, { x0: cx0, x1: cx1, y0: cy0, y1: cy1, yl: "p95", xl: "", xt: steps.map((v, i) => [cx0 + sw * (i + .5), String(v)]) });
      txt(s, cx1, cy1 + 44, "ступень нагрузки, RPS", "g-tick", { "text-anchor": "end" });
      appear(path(s, `M${cx0} 92 H${cx1}`, { class: "g-thr" }), 200);
      appear(txt(s, cx0 + 8, 84, "порог SLA", "g-lbl", { "font-size": 15, fill: css("--fail") }), 200);
      const vals = [212, 206, 190, 64];
      const P = vals.map((y, i) => [cx0 + sw * (i + .5), y]);
      P.forEach(([x, y], i) => {
        const bar = rect(s, x - 26, y, 52, cy1 - y, { fill: y < 92 ? css("--fail") : css("--blue-2"), opacity: y < 92 ? .9 : .6 });
        grow(bar, "y", 400 + i * 350, 500);
        pop(el("circle", { cx: x, cy: y, r: 6, fill: y < 92 ? css("--fail") : css("--blue") }, s), 700 + i * 350);
      });
      const xi = cx0 + sw * 4.5;
      const ind = el("g", {}, s);
      const pat = el("pattern", { id: "hatch", width: 8, height: 8, patternUnits: "userSpaceOnUse", patternTransform: "rotate(45)" }, el("defs", {}, s));
      rect(pat, 0, 0, 3, 8, { fill: css("--warn"), opacity: .5 });
      rect(ind, xi - 26, 120, 52, cy1 - 120, { fill: "url(#hatch)", stroke: css("--warn"), "stroke-dasharray": "4 3" });
      lines(ind, xi, 84, ["генератор", "не дал"], "g-lbl", 17, { "font-size": 14, "text-anchor": "middle", fill: css("--warn") });
      txt(ind, xi - 20, 30, "INDETERMINATE", "", { "font-family": "IBM Plex Mono", "font-size": 13, "font-weight": 600, "text-anchor": "middle", fill: css("--warn") });
      appear(ind, 2000);
      // колено: две прямые
      drawIn(path(s, `M${P[0][0]} ${P[0][1] + 2} L${P[2][0]} ${P[2][1]} L${P[3][0] + 14} ${P[3][1] - 22}`, { stroke: css("--navy"), "stroke-width": 2, fill: "none", "stroke-dasharray": "none" }), 2400, 900);
      const kn = el("g", {}, s);
      el("circle", { cx: P[2][0], cy: P[2][1], r: 11, fill: "none", stroke: css("--navy"), "stroke-width": 2 }, kn);
      txt(kn, P[2][0] - 16, P[2][1] + 34, "колено", "g-lbl-b", { "font-size": 15, "text-anchor": "middle", fill: css("--band") });
      appear(kn, 3200);
      const cap = el("g", {}, s);
      path(cap, `M${P[2][0] + 30} ${cy1 - 6} V112`, { stroke: css("--pass"), "stroke-width": 2, "stroke-dasharray": "4 3" });
      txt(cap, P[2][0] + 22, 128, "граница ёмкости", "g-lbl-b", { "font-size": 15, fill: css("--pass"), "text-anchor": "end" });
      appear(cap, 3600);
    },
  };

  /* ================= 15. Корреляции ================= */
  hooks["s-corr"] = {
    enter() {
      // 1. ряды
      const s = clear($("#cSeries"));
      const W = 1040, H = 200, n = 150, dx = (W - 40) / (n - 1), LP = 12, Lpx = LP * dx;
      const r = rng(21), raw = []; let v = .4;
      for (let i = -LP; i < n; i++) { v += (r() - .5) * .14; if (r() < .05) v += (r() - .35) * .45; v = clamp(v, .08, .92); raw.push(v); }
      const B = raw.slice(LP), A = raw.slice(0, n).map((x) => clamp(x + (r() - .5) * .05, .05, .95));
      const yy = (q) => H - 16 - q * (H - 36);
      const pth = (arr) => "M" + arr.map((q, i) => `${(20 + i * dx).toFixed(1)} ${yy(q).toFixed(1)}`).join(" L");
      const defs = el("defs", {}, s); rect(el("clipPath", { id: "cclip" }, defs), 20, 0, W - 40, H);
      [.25, .5, .75].forEach((q) => path(s, `M20 ${yy(q)} H${W - 20}`, { class: "g-grid" }));
      const g = el("g", { "clip-path": "url(#cclip)" }, s);
      drawIn(path(g, pth(A), { stroke: css("--warn"), "stroke-width": 2.5, fill: "none" }), 100, 1000);
      const gb = el("g", {}, g);
      drawIn(path(gb, pth(B), { class: "g-main", "stroke-width": 2.5 }), 300, 1000);
      const lg = el("g", {}, s);
      path(lg, `M${W - 380} 18 H${W - 356}`, { class: "g-main" }); txt(lg, W - 350, 24, "пул соединений БД", "g-tick");
      path(lg, `M${W - 170} 18 H${W - 146}`, { stroke: css("--warn"), "stroke-width": 3 }); txt(lg, W - 140, 24, "p95", "g-tick");
      const read = $("#lagRead");
      read.textContent = "сдвиг 0 с";
      loop((t) => {
        const m = ease(clamp((t - 1.3) / 2.2)), sft = Lpx * m;
        gb.setAttribute("transform", `translate(${sft.toFixed(1)} 0)`);
        read.textContent = m >= 1 ? "сдвиг +30 с: ряды совпали" : `сдвиг +${Math.round(m * 30)} с`;
        return t < 3.6;
      });
      // 2. CCF
      const c = clear($("#cCcf"));
      const cx0 = 70, cx1 = 990, cy0 = 20, cy1 = 220, zero = 150, sc = 140;
      const lags = []; for (let l = -60; l <= 60; l += 5) lags.push(l);
      const xs = (l) => cx0 + (l + 60) / 120 * (cx1 - cx0);
      const yv = (v) => zero - v * sc;
      const band = rect(c, cx0, yv(.27), cx1 - cx0, yv(-.27) - yv(.27), { fill: css("--surface-2") });
      [.5, -.25].forEach((v) => { path(c, `M${cx0} ${yv(v)} H${cx1}`, { class: "g-grid" }); txt(c, cx0 - 8, yv(v) + 5, String(v).replace(".", ","), "g-tick", { "text-anchor": "end" }); });
      path(c, `M${cx0} ${zero} H${cx1}`, { class: "g-ax" });
      path(c, `M${cx0} ${cy0} V${cy1}`, { class: "g-ax" });
      txt(c, cx0 - 8, zero + 5, "0", "g-tick", { "text-anchor": "end" });
      [-60, -30, 0, 30, 60].forEach((l) => txt(c, xs(l), cy1 + 24, (l > 0 ? "+" : "") + l + " с", "g-tick", { "text-anchor": "middle" }));
      const rr = rng(44);
      const val = (l) => (l === 30 ? .82 : Math.abs(l - 30) === 5 ? .48 : Math.abs(l - 30) === 10 ? .2 : (rr() - .5) * .3);
      const bars = lags.map((l, i) => {
        const v = val(l), y = v >= 0 ? yv(v) : zero;
        const bb = rect(c, xs(l) - 13, y, 26, Math.abs(v) * sc, { fill: css("--mute"), opacity: .55 });
        bb.style.transformBox = "fill-box"; bb.style.transformOrigin = v >= 0 ? "50% 100%" : "50% 0%";
        bb.animate([{ transform: "scaleY(0)" }, { transform: "none" }], { duration: D(400), delay: D(3700 + i * 40), fill: "both", easing: E });
        return [l, bb];
      });
      // реплики bootstrap
      band.style.opacity = 0;
      const reps = el("g", {}, c);
      for (let k = 0; k < 24; k++) {
        let d = ""; lags.forEach((l) => { const y = yv((rr() - .5) * .5); d += (d ? " L" : "M") + `${xs(l)} ${y.toFixed(1)}`; });
        const p = path(reps, d, { stroke: css("--mute"), "stroke-width": 1, fill: "none", opacity: 0 });
        p.animate([{ opacity: 0 }, { opacity: .5 }, { opacity: 0 }], { duration: D(500), delay: D(5000 + k * 60), fill: "both" });
      }
      appear(band, 6400, 600);
      appear(txt(c, cx1 - 6, yv(-.27) + 18, "нулевое распределение (шум)", "g-tick", { "text-anchor": "end" }), 6600);
      const thr = path(c, `M${cx0} ${yv(.4)} H${cx1}`, { stroke: css("--fail"), "stroke-width": 2, "stroke-dasharray": "8 6", fill: "none" });
      appear(thr, 7000);
      appear(txt(c, cx0 + 8, yv(.4) - 8, "порог с поправкой Holm", "g-tick", { fill: css("--fail") }), 7000);
      after(7600, () => {
        bars.forEach(([l, bb]) => { if (l === 30) { bb.setAttribute("fill", css("--blue")); bb.setAttribute("opacity", 1); } });
      });
      const call = el("g", {}, c);
      rect(call, xs(30) - 364, yv(.82) - 6, 340, 50, { fill: css("--blue"), rx: 3 });
      txt(call, xs(30) - 350, yv(.82) + 25, "гипотеза: пул БД → p95, лаг +30 с", "", { fill: "#fff", "font-size": 17, "font-weight": 600 });
      appear(call, 7900);
      $$("#s-corr .prot li").forEach((li, i) => li.animate([{ opacity: 0, transform: "translateX(14px)" }, { opacity: 1, transform: "none" }], { duration: D(450), delay: D(300 + i * 160), fill: "both", easing: E }));
    },
  };

  /* ================= 16. Честные цифры ================= */
  hooks["s-honest"] = {
    enter() {
      const s = clear($("#honestSvg"));
      const x0 = 330, x1 = 950;
      const lx = (p) => x0 + (Math.log10(p) - 0) / 2 * (x1 - x0); // 1%..100%
      txt(s, 0, 24, "Доля ложных находок (логарифмическая шкала)", "g-lbl-b", { "font-size": 20 });
      const ticks = [1, 2, 5, 10, 20, 50, 100];
      ticks.forEach((t) => { path(s, `M${lx(t)} 50 V290`, { class: "g-grid" }); txt(s, lx(t), 314, t + "%", "g-tick", { "text-anchor": "middle" }); });
      path(s, `M${x0} 290 H${x1}`, { class: "g-ax" });
      const tg = el("g", {}, s);
      path(tg, `M${lx(5)} 44 V290`, { stroke: css("--warn"), "stroke-width": 2.5, "stroke-dasharray": "6 5" });
      txt(tg, lx(5) + 8, 60, "цель ≤ 5%", "g-lbl-b", { "font-size": 16, fill: css("--warn") });
      appear(tg, 300);
      const rows = [
        ["Сильный дрейф метрик", 120, [100, 100], [3.6, 3.6]],
        ["Независимые ряды", 230, [7.7, 13.2], [2.6, 3.7]],
      ];
      rows.forEach(([name, y, v1, v2], i) => {
        const base = 600 + i * 900;
        lines(s, 0, y - 4, name === "Независимые ряды" ? ["Независимые ряды", "с инерцией"] : [name], "g-lbl-b", 24, { "font-size": 19 });
        const a = el("g", {}, s);
        if (v1[0] !== v1[1]) path(a, `M${lx(v1[0])} ${y} H${lx(v1[1])}`, { stroke: css("--fail"), "stroke-width": 12, "stroke-linecap": "round", opacity: .8 });
        el("circle", { cx: lx(v1[1]), cy: y, r: 9, fill: css("--fail") }, a);
        txt(a, lx(v1[1]) + (v1[0] === v1[1] ? 10 : 0), y - 18, v1[0] === v1[1] ? "v1: 100%" : "v1: 7,7–13,2%", "g-tick", { "text-anchor": v1[0] === v1[1] ? "end" : "middle", fill: css("--fail") });
        appear(a, base);
        const ar = path(s, `M${lx(v1[0]) - 14} ${y} H${lx(v2[1]) + 16}`, { stroke: css("--ink-2"), "stroke-width": 2, "marker-end": arrowDefs(s, "hA" + i, css("--ink-2")) });
        drawIn(ar, base + 400, 800);
        const b = el("g", {}, s);
        if (v2[0] !== v2[1]) path(b, `M${lx(v2[0])} ${y} H${lx(v2[1])}`, { stroke: css("--blue"), "stroke-width": 12, "stroke-linecap": "round" });
        el("circle", { cx: lx(v2[0]), cy: y, r: 9, fill: css("--blue") }, b);
        txt(b, lx(v2[0]), y + 32, v2[0] === v2[1] ? "v2: 3,6%" : "v2: 2,6–3,7%", "g-tick", { "text-anchor": "middle", fill: css("--blue") });
        pop(b, base + 1200);
      });
      const all = el("g", {}, s);
      txt(all, 0, 314, "v2, все 22 сценария: 0–4,4%", "g-lbl-b", { "font-size": 18, fill: css("--blue") });
      appear(all, 2600);
      // обнаружение
      txt(s, 0, 400, "Находит заложенные связи (метод v2)", "g-lbl-b", { "font-size": 20 });
      const tr = rect(s, 0, 424, 950, 50, { fill: css("--surface") });
      const fl = rect(s, 0, 424, 950 * .998, 50, { fill: css("--pass") });
      grow(fl, "x", 2800, 1200);
      const ft = txt(s, 18, 456, "99,8–99,9%", "", { fill: "#fff", "font-family": "IBM Plex Mono", "font-size": 20, "font-weight": 600 });
      appear(ft, 3600);
      txt(s, 0, 506, "линейные связи с разным сдвигом, 1 000 отчётов на сценарий", "g-tick");
      void tr;
    },
  };

  /* ================= 17. Пилот ИИ ================= */
  hooks["s-field"] = {
    enter() {
      const s = clear($("#fieldSvg"));
      const x0 = 150, x1 = 800, sx = (v) => x0 + v / 100 * (x1 - x0);
      [0, 25, 50, 75, 100].forEach((v) => { path(s, `M${sx(v)} 10 V290`, { class: "g-grid" }); txt(s, sx(v), 314, v + "%", "g-tick", { "text-anchor": "middle" }); });
      const rows = [["Рецензент A", 87, 61, 30], ["Рецензент B", 84, 43, 160]];
      rows.forEach(([n, a, b, y], i) => {
        txt(s, 0, y + 50, n, "g-lbl-b", { "font-size": 19 });
        const r1 = rect(s, x0, y, sx(a) - x0, 40, { fill: css("--fail"), opacity: .75 });
        const r2 = rect(s, x0, y + 52, sx(b) - x0, 40, { fill: css("--blue") });
        grow(r1, "x", 300 + i * 500, 700); grow(r2, "x", 600 + i * 500, 700);
        appear(txt(s, x0 + 12, y + 27, `промпт v1: ${a}%`, "", { fill: "#fff", "font-size": 17, "font-weight": 600 }), 900 + i * 500);
        appear(txt(s, x0 + 12, y + 79, `промпт v2: ${b}%`, "", { fill: "#fff", "font-size": 17, "font-weight": 600 }), 1200 + i * 500);
        appear(txt(s, sx(a) + 12, y + 79, `−${a - b} п. п.`, "g-lbl-b", { fill: css("--pass"), "font-size": 19 }), 1600 + i * 500);
      });
    },
  };

  /* ================= 20. Целевая картина ================= */
  hooks["s-target"] = {
    enter() {
      const s = clear($("#targetSvg"));
      const A = arrowDefs(s, "tA", css("--ink-2")), AW = arrowDefs(s, "tW", css("--warn")), AP = arrowDefs(s, "tP", css("--pass"));
      const lanes = [["Триггер", 0, 110], ["Автономный флоу на сервере", 110, 330], ["Инструменты через MCP", 330, 500], ["Человек", 500, 690]];
      lanes.forEach(([n, a, b], i) => {
        const g = el("g", {}, s);
        rect(g, 0, a, 1680, b - a, { fill: i % 2 ? css("--surface") : css("--bg") });
        rect(g, 0, a, 220, b - a, { fill: i === 3 ? css("--pass-soft") : css("--surface-2") });
        lines(g, 20, a + (b - a) / 2 - (n.length > 18 ? 4 : -6), n.length > 18 ? n.split(" ").reduce((acc, w) => { const l = acc[acc.length - 1]; if ((l + " " + w).trim().length <= 16) acc[acc.length - 1] = (l + " " + w).trim(); else acc.push(w); return acc; }, [""]) : [n], "g-lbl-b", 24, { "font-size": 19 });
        path(g, `M0 ${b} H1680`, { stroke: css("--line"), "stroke-width": 1 });
        appear(g, i * 120);
      });
      const box = (x, y, w, h, t, o = {}) => {
        const g = el("g", {}, s);
        rect(g, x, y, w, h, { fill: o.fill || css("--bg"), stroke: o.stroke || css("--navy"), "stroke-width": o.sw || 1.5, rx: 3 });
        const ls = Array.isArray(t) ? t : [t];
        lines(g, x + w / 2, y + h / 2 + 7 - (ls.length - 1) * 11, ls, o.cls || "g-lbl-b", 23, { "text-anchor": "middle", "font-size": o.fs || 18, fill: o.color });
        return g;
      };
      const trig = box(270, 22, 260, 62, ["Задача или изменение", "в продукте"]);
      appear(trig, 500);
      const stepsX = [300, 540, 780, 1020, 1260], SW = 200;
      const names = [["Разработка", "скриптов"], ["Отладка"], ["Запуск теста"], ["Проверка логов", "и метрик"], ["Вердикт", "по прогону"]];
      const sb = stepsX.map((x, i) => { const g = box(x, 175, SW, 80, names[i], { stroke: css("--blue"), sw: 2 }); appear(g, 700 + i * 180); return g; });
      stepsX.slice(0, -1).forEach((x, i) => drawIn(path(s, `M${x + SW} 215 H${stepsX[i + 1] - 4}`, { class: "g-conn", "marker-end": A }), 1000 + i * 180, 300));
      const rep = box(1500, 175, 160, 80, ["Отчёт для", "человека"], { fill: css("--blue-soft"), stroke: css("--blue"), sw: 2 });
      appear(rep, 1600);
      drawIn(path(s, `M${1260 + SW} 215 H1496`, { class: "g-conn", "marker-end": A }), 1700, 300);
      drawIn(path(s, "M400 84 V171", { class: "g-conn", "marker-end": A }), 800, 500);
      const hl = path(s, "M1120 175 C1120 124, 640 124, 640 171", { stroke: css("--warn"), "stroke-width": 2.5, "stroke-dasharray": "7 5", fill: "none", "marker-end": AW });
      appear(hl, 2000);
      appear(txt(s, 880, 128, "self-healing: нашли причину, поправили, повторили", "g-lbl", { "font-size": 16, "text-anchor": "middle", fill: css("--warn") }), 2000);
      const tools = [["Скиллы Gatling, JMeter", "база знаний"], ["БД и код продукта", "сверка ошибок"], ["MCP SSH", "генераторы нагрузки"], ["RCA-агент", "логи OpenSearch"], ["Вердикт", "через MCP"]];
      tools.forEach((t, i) => {
        const x = stepsX[i];
        const g = box(x, 370, SW, 84, t, { fill: css("--teal-soft"), stroke: css("--teal"), cls: "g-lbl", fs: 17 });
        appear(g, 2200 + i * 160);
        const p = path(s, `M${x + SW / 2} 259 V366`, { stroke: css("--teal"), "stroke-width": 2, "stroke-dasharray": "4 4", fill: "none" });
        appear(p, 2300 + i * 160);
        packet(s, `M${x + SW / 2 - 8} 259 V366`, css("--teal"), 1.2, 3000 + i * 300, { r: 5 });
        packet(s, `M${x + SW / 2 + 8} 259 V366`, css("--blue"), 1.2, 3600 + i * 300, { r: 5, rev: true });
      });
      const gate = box(1260, 560, 230, 76, ["Одобрение", "отчёта"], { fill: css("--pass-soft"), stroke: css("--pass"), sw: 3, color: css("--pass") });
      pop(gate, 2900);
      const dec = box(1520, 560, 150, 76, ["Решение", "по релизу"]);
      appear(dec, 3200);
      drawIn(path(s, "M1580 259 V520 H1375 V556", { class: "g-conn", "marker-end": A }), 2700, 600);
      drawIn(path(s, "M1490 598 H1516", { stroke: css("--pass"), "stroke-width": 2.5, fill: "none", "marker-end": AP }), 3200, 300);
      const rej = path(s, "M1260 598 H760 V300 H600 V259", { stroke: css("--warn"), "stroke-width": 2.5, "stroke-dasharray": "7 5", fill: "none", "marker-end": AW });
      appear(rej, 3500);
      appear(txt(s, 1010, 588, "не одобрено: флоу возвращается к доработке", "g-lbl", { "font-size": 16, "text-anchor": "middle", fill: css("--warn") }), 3500);
      packet(s, "M400 84 V215 H1580 V520 H1375 V598 H1520", css("--blue"), 7, 1800, { r: 8 });
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
