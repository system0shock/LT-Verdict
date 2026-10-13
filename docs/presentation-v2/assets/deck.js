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
