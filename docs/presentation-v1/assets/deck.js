(() => {
  "use strict";
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const NS = "http://www.w3.org/2000/svg";
  const svgEl = (name, attrs = {}, parent) => {
    const e = document.createElementNS(NS, name);
    for (const k in attrs) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  };
  const clamp = (v, a = 0, b = 1) => Math.min(b, Math.max(a, v));
  const ease = (t) => (t < .5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2);
  const smooth = (a, b, x) => { const t = clamp((x - a) / (b - a)); return t * t * (3 - 2 * t); };
  const rng = (seed) => () => { seed |= 0; seed = (seed + 0x6d2b79f5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;

  const stage = $("#stage");
  const slides = $$(".slide");
  let idx = 0, step = 0, skipDraft = false;
  const hooks = {};

  /* ---------- Масштаб ---------- */
  function fit() {
    const s = Math.min(innerWidth / 1920, innerHeight / 1080);
    stage.style.transform = `scale(${s})`;
  }
  addEventListener("resize", fit);
  fit();

  /* ---------- Тема ---------- */
  try { const t = localStorage.getItem("ltv-theme"); if (t) document.documentElement.dataset.theme = t; } catch (e) {}
  function toggleTheme() {
    const d = document.documentElement;
    d.dataset.theme = d.dataset.theme === "light" ? "dark" : "light";
    try { localStorage.setItem("ltv-theme", d.dataset.theme); } catch (e) {}
  }

  /* ---------- Навигация ---------- */
  const isSkipped = (i) => skipDraft && slides[i].dataset.draft === "1";
  const maxStep = (i) => +slides[i].dataset.steps || 0;

  function setStep(n) {
    step = n;
    slides[idx].dataset.step = String(n);
  }

  function go(i, st = 0) {
    i = clamp(i, 0, slides.length - 1);
    const prev = slides[idx];
    if (hooks[prev.id] && hooks[prev.id].leave) hooks[prev.id].leave(prev);
    prev.classList.remove("on");
    idx = i;
    const cur = slides[idx];
    cur.dataset.step = "0";
    cur.classList.add("on");
    setStep(st);
    if (hooks[cur.id] && hooks[cur.id].enter) hooks[cur.id].enter(cur);
    $("#progress").style.width = ((idx + 1) / slides.length * 100) + "%";
    $("#counter").textContent = (idx + 1) + " / " + slides.length;
    const n = $(".notes", cur);
    $("#notes").innerHTML = n ? n.innerHTML : "";
    $$("#tocList li").forEach((li, k) => li.classList.toggle("cur", k === idx));
    history.replaceState(null, "", "#" + (idx + 1));
  }

  function next() {
    if (step < maxStep(idx)) { setStep(step + 1); return; }
    let j = idx + 1;
    while (j < slides.length && isSkipped(j)) j++;
    if (j < slides.length) go(j, 0);
  }
  function prev() {
    if (step > 0) { setStep(step - 1); return; }
    let j = idx - 1;
    while (j >= 0 && isSkipped(j)) j--;
    if (j >= 0) go(j, maxStep(j));
  }

  function toast(text) {
    let t = $("#toast");
    if (!t) {
      t = document.createElement("div"); t.id = "toast";
      t.style.cssText = "position:fixed;left:50%;top:18px;transform:translateX(-50%);background:rgba(10,18,27,.92);color:#e6eef5;border:1px solid #355068;padding:10px 20px;border-radius:10px;font:500 18px Onest,sans-serif;z-index:60;transition:opacity .3s;";
      document.body.appendChild(t);
    }
    t.textContent = text; t.style.opacity = 1;
    clearTimeout(toast.h); toast.h = setTimeout(() => (t.style.opacity = 0), 1800);
  }

  function toggleFull() {
    if (document.fullscreenElement) document.exitFullscreen();
    else document.documentElement.requestFullscreen && document.documentElement.requestFullscreen();
  }
  const toggleNotes = () => document.body.classList.toggle("show-notes");
  const toggleToc = () => document.body.classList.toggle("show-toc");

  // Оглавление
  const tocList = $("#tocList");
  slides.forEach((s, i) => {
    const li = document.createElement("li");
    li.textContent = s.dataset.title + (s.dataset.draft ? " (черновик)" : "");
    li.addEventListener("click", () => { document.body.classList.remove("show-toc"); go(i, 0); });
    tocList.appendChild(li);
  });

  addEventListener("keydown", (e) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    switch (e.key) {
      case "ArrowRight": case "PageDown": case " ": case "Enter":
        if (e.target.closest && e.target.closest("#hud button, a")) return;
        e.preventDefault(); next(); break;
      case "ArrowLeft": case "PageUp": case "Backspace": e.preventDefault(); prev(); break;
      case "Home": go(0, 0); break;
      case "End": go(slides.length - 1, 0); break;
      case "f": case "F": case "а": case "А": toggleFull(); break;
      case "t": case "T": case "е": case "Е": toggleTheme(); break;
      case "n": case "N": case "т": case "Т": toggleNotes(); break;
      case "o": case "O": case "щ": case "Щ": toggleToc(); break;
      case "d": case "D": case "в": case "В":
        skipDraft = !skipDraft;
        toast(skipDraft ? "Черновой слайд пропускается" : "Черновой слайд включён");
        break;
      case "Escape": document.body.classList.remove("show-toc", "show-notes"); break;
    }
  });

  stage.addEventListener("click", (e) => {
    if (e.target.closest("a, button")) return;
    const r = stage.getBoundingClientRect();
    if ((e.clientX - r.left) / r.width < .3) prev(); else next();
  });

  // HUD
  let hudT;
  addEventListener("mousemove", () => {
    document.body.classList.add("hud");
    clearTimeout(hudT); hudT = setTimeout(() => document.body.classList.remove("hud"), 2500);
  });
  $("#hud").addEventListener("click", (e) => {
    const b = e.target.closest("button"); if (!b) return;
    ({ prev, next, toc: toggleToc, notes: toggleNotes, theme: toggleTheme, full: toggleFull })[b.dataset.act]();
    b.blur();
  });

  /* ======================================================
     Слайд 1. Холст: графики стягиваются в вердикт
     ====================================================== */
  const hero = { raf: 0 };
  hooks.s1 = {
    enter() {
      const cv = $("#heroCanvas"), ctx = cv.getContext("2d");
      const N = 46, r = rng(11), X0 = 112, X1 = 1808;
      const lines = Array.from({ length: N }, () => ({
        off: (r() - .5) * 150,
        k: [0, 1, 2].map((q) => ({ a: (q === 0 ? 40 + r() * 110 : 14 + r() * 40), f: .004 + q * .006 + r() * .006, p: r() * 6.28, s: .4 + r() * .9 })),
      }));
      const base = (x) => { const u = (x - X0) / (X1 - X0); return 900 - 250 * smooth(0, .3, u) + 30 * smooth(.78, 1, u) - 14 * Math.sin(u * 7); };
      const noise = (L, x, t) => L.k.reduce((s, q) => s + q.a * Math.sin(q.f * x + q.p + q.s * t), 0);
      const T = 10;
      const t0 = performance.now();
      function frame(now) {
        const t = reduced ? 7.5 : (now - t0) / 1000;
        const p = (t % T) / T;
        const accent = css("--accent"), mute = css("--mute"), pass = css("--pass");
        ctx.clearRect(0, 0, 1920, 1080);
        const reveal = ease(clamp(p / .12));
        const xe = X0 + (X1 - X0) * reveal;
        const c = ease(clamp((p - .3) / .28));
        const out = clamp((p - .93) / .07);
        ctx.globalAlpha = 1 - out;
        ctx.lineJoin = "round";
        // шумные ряды
        for (let i = 1; i < N; i++) {
          const L = lines[i];
          ctx.beginPath();
          for (let x = X0; x <= xe; x += 8) {
            const y = base(x) + (1 - c) * (L.off + noise(L, x, t));
            x === X0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
          }
          ctx.strokeStyle = mute; ctx.globalAlpha = (1 - out) * (.5 * (1 - c) + .06); ctx.lineWidth = 1.6; ctx.stroke();
        }
        // главная линия
        ctx.globalAlpha = 1 - out;
        ctx.beginPath();
        let ye = 0;
        for (let x = X0; x <= xe; x += 6) {
          const y = base(x) + (1 - c) * .2 * noise(lines[0], x, t);
          ye = y; x === X0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
        }
        ctx.strokeStyle = accent; ctx.lineWidth = 3 + 4 * c; ctx.shadowColor = accent; ctx.shadowBlur = 16 * c; ctx.stroke(); ctx.shadowBlur = 0;
        // подписи
        ctx.font = "500 30px Onest, sans-serif"; ctx.textBaseline = "alphabetic";
        ctx.fillStyle = mute; ctx.globalAlpha = (1 - out) * (1 - c) * .95; ctx.fillText("50–200 графиков", X0, 1010);
        ctx.fillStyle = accent; ctx.globalAlpha = (1 - out) * c; ctx.fillText("один вердикт с доказательствами", X0, 1010);
        // штамп
        const sp = clamp((p - .62) / .09);
        if (sp > 0) {
          const k = sp < 1 ? 1 + .35 * Math.pow(1 - sp, 2) * Math.sin(sp * 3) : 1;
          const sx = 1640, sy = ye - 150;
          ctx.save(); ctx.translate(sx, sy); ctx.scale(k, k);
          ctx.globalAlpha = (1 - out) * sp; ctx.strokeStyle = pass; ctx.fillStyle = pass; ctx.lineWidth = 5;
          ctx.beginPath(); ctx.roundRect(-110, -44, 220, 88, 14); ctx.stroke();
          ctx.font = "600 56px 'IBM Plex Mono', monospace"; ctx.textAlign = "center"; ctx.textBaseline = "middle";
          ctx.fillText("PASS", 0, 4); ctx.restore();
          ctx.textAlign = "left";
        }
        ctx.globalAlpha = 1;
        if (!reduced) hero.raf = requestAnimationFrame(frame);
      }
      hero.raf = requestAnimationFrame(frame);
    },
    leave() { cancelAnimationFrame(hero.raf); },
  };

  /* ======================================================
     Слайд 2. Плитки-спарклайны
     ====================================================== */
  hooks.s2 = {
    enter() {
      const box = $("#tiles");
      if (box.childElementCount) return;
      const r = rng(5), hot = new Set([5, 14, 22, 33]);
      for (let i = 0; i < 40; i++) {
        const d = document.createElement("div");
        d.className = "tile" + (hot.has(i) ? " hot" : "");
        d.style.animationDelay = (i * 22) + "ms";
        let y = 20 + r() * 8, pts = [];
        const trend = hot.has(i) ? 1.2 : 0;
        for (let x = 0; x <= 100; x += 5) { y += (r() - .5) * 7 - trend * (x > 55 ? .9 : 0); y = clamp(y, 4, 36); pts.push(`${x} ${y.toFixed(1)}`); }
        d.innerHTML = `<svg viewBox="0 0 100 40" preserveAspectRatio="none"><path d="M${pts.join(" L")}"/></svg>`;
        box.appendChild(d);
      }
    },
  };

  /* ======================================================
     Слайд 9. MCP: потоки между агентами и Вердиктом
     ====================================================== */
  hooks.s9 = {
    enter() {
      const svg = $("#mcpSvg");
      if (svg.childElementCount) return;
      svg.setAttribute("viewBox", "0 0 756 560");
      svg.removeAttribute("preserveAspectRatio");
      const ys = [48, 164, 280, 396, 512], hubX = 378, hubY = 280;
      const common = `M${hubX} ${hubY} L756 ${hubY}`;
      const paths = ys.map((y) => `M0 ${y} C 230 ${y}, 150 ${hubY}, ${hubX} ${hubY}`);
      paths.forEach((d) => svgEl("path", { d, fill: "none", stroke: css("--line"), "stroke-width": 2.5 }, svg));
      svgEl("path", { d: common, fill: "none", stroke: css("--line"), "stroke-width": 2.5 }, svg);
      const pkt = (d, color, begin, rev, dur = "3.4s") => {
        const c = svgEl("circle", { r: 8, fill: color }, svg);
        const a = svgEl("animateMotion", { dur, begin: begin + "s", repeatCount: "indefinite", path: d }, c);
        if (rev) { a.setAttribute("keyPoints", "1;0"); a.setAttribute("keyTimes", "0;1"); a.setAttribute("calcMode", "linear"); }
        svgEl("animate", { attributeName: "opacity", values: "0;1;1;0", keyTimes: "0;.1;.9;1", dur, begin: begin + "s", repeatCount: "indefinite" }, c);
      };
      paths.forEach((d, i) => {
        pkt(d, css("--accent"), (i * .7).toFixed(1), false, "2.6s");
        pkt(d, css("--pass"), (i * .7 + 3.6).toFixed(1), true, "2.6s");
      });
      [0, 1.1, 2.2].forEach((b) => pkt(common, css("--accent"), b, false, "2.2s"));
      [3.3, 4.4].forEach((b) => pkt(common, css("--pass"), b, true, "2.2s"));
    },
  };

  /* ======================================================
     Слайд 10. Галерея макета
     ====================================================== */
  const gal = { timer: 0, i: 0, manual: false };
  const SCREENS = [
    ["01-overview", "Обзор прогона"], ["02-deep-trend", "Глубокий анализ: тренд"], ["03-deep-pods-two-shoulders", "Поды и два плеча"],
    ["04-new-analysis", "Новый анализ"], ["05-tables-trends", "Таблицы: тренды"], ["06-tables-services", "Таблицы: сервисы"],
    ["07-rules", "Правила"], ["08-history", "История релизов"], ["09-ai", "ИИ-разбор"], ["10-compare", "Сравнение прогонов"],
  ];
  function showShot(i) {
    gal.i = i;
    $$("#tabs button").forEach((b, k) => b.setAttribute("aria-selected", String(k === i)));
    $$("#view img").forEach((im, k) => {
      if (k === i) {
        const v = $("#view");
        const apply = () => {
          const h = v.clientWidth * im.naturalHeight / im.naturalWidth;
          im.style.setProperty("--shift", -Math.max(0, Math.min(h - v.clientHeight, 1100)) + "px");
          im.style.setProperty("--dur", "9s");
        };
        im.classList.remove("on"); void im.offsetWidth;
        im.complete && im.naturalWidth ? apply() : im.addEventListener("load", apply, { once: true });
        im.classList.add("on");
      } else im.classList.remove("on");
    });
  }
  hooks.s10 = {
    enter() {
      const tabs = $("#tabs"), view = $("#view");
      if (!tabs.childElementCount) {
        SCREENS.forEach(([f, label], i) => {
          const b = document.createElement("button");
          b.textContent = label; b.setAttribute("role", "tab");
          b.addEventListener("click", () => { gal.manual = true; clearInterval(gal.timer); showShot(i); });
          tabs.appendChild(b);
          const im = document.createElement("img");
          im.src = `assets/screens/${f}.jpg`; im.alt = label; im.loading = "eager";
          view.appendChild(im);
        });
      }
      gal.manual = false; showShot(0);
      clearInterval(gal.timer);
      gal.timer = setInterval(() => { if (!gal.manual) showShot((gal.i + 1) % SCREENS.length); }, 9500);
    },
    leave() { clearInterval(gal.timer); },
  };

  /* ======================================================
     Слайд 12. Сдвиг рядов: поиск лага
     ====================================================== */
  const corr = { raf: 0 };
  hooks.s12 = {
    enter() {
      const svg = $("#corrSvg");
      svg.innerHTML = "";
      const W = 700, H = 380, n = 120, dx = W / (n - 1), LP = 12, Lpx = LP * dx;
      const r = rng(21);
      // ведущий ряд: «ступенчатая» нагрузка с всплесками
      const raw = [];
      let v = .3;
      for (let i = -LP; i < n; i++) { v += (r() - .5) * .16; if (r() < .06) v += (r() - .3) * .5; v = clamp(v, .06, .94); raw.push(v); }
      const B = raw.slice(LP), A = raw.slice(0, n).map((x) => clamp(x + (r() - .5) * .05, .04, .96));
      const y = (q) => H - 30 - q * (H - 70);
      const pathOf = (arr) => "M" + arr.map((q, i) => `${(i * dx).toFixed(1)} ${y(q).toFixed(1)}`).join(" L");
      const defs = svgEl("defs", {}, svg);
      const cp = svgEl("clipPath", { id: "cclip" }, defs); svgEl("rect", { x: 0, y: 0, width: W, height: H }, cp);
      for (let k = 0; k < 5; k++) svgEl("line", { x1: 0, x2: W, y1: 20 + k * 80, y2: 20 + k * 80, stroke: css("--line"), "stroke-width": 1, opacity: .5 }, svg);
      const g = svgEl("g", { "clip-path": "url(#cclip)" }, svg);
      svgEl("path", { d: pathOf(A), fill: "none", stroke: css("--warn"), "stroke-width": 4, "stroke-linejoin": "round" }, g);
      const gb = svgEl("g", {}, g);
      svgEl("path", { d: pathOf(B), fill: "none", stroke: css("--accent"), "stroke-width": 4, "stroke-linejoin": "round" }, gb);
      const readEl = $("#corrRead"), meter = $("#corrMeter");
      meter.style.animation = "none";
      const T = 9, t0 = performance.now();
      function frame(now) {
        const t = reduced ? 7 : ((now - t0) / 1000) % T;
        const m = ease(clamp((t - 1.2) / 4.6));
        const s = Lpx * m;
        gb.setAttribute("transform", `translate(${s.toFixed(1)} 0)`);
        const near = 1 - clamp(Math.abs(Lpx - s) / (Lpx * .7));
        const found = t > 5.8;
        const lvl = found ? .94 : .12 + .8 * near * near;
        meter.style.width = (lvl * 100).toFixed(0) + "%";
        const sec = Math.round(s / Lpx * 30);
        readEl.textContent = found ? "сдвиг 30 с: связь" : `пробуем сдвиг ${sec} с`;
        if (!reduced) corr.raf = requestAnimationFrame(frame);
      }
      corr.raf = requestAnimationFrame(frame);
    },
    leave() { cancelAnimationFrame(corr.raf); },
  };

  /* ======================================================
     Слайд 16. Стрелки целевой схемы
     ====================================================== */
  hooks.s16 = {
    enter() {
      const svg = $("#mapArrows");
      if (svg.childElementCount) return;
      const defs = svgEl("defs", {}, svg);
      const mk = (id, color) => {
        const m = svgEl("marker", { id, viewBox: "0 0 10 10", refX: 8, refY: 5, markerWidth: 8, markerHeight: 8, orient: "auto-start-reverse" }, defs);
        svgEl("path", { d: "M0 0L10 5L0 10z", fill: color }, m);
      };
      mk("ah", css("--accent")); mk("ahw", css("--warn"));
      const L = (d, color, dash, mid) => svgEl("path", { d, fill: "none", stroke: color, "stroke-width": 3, "stroke-dasharray": dash || "", "marker-end": `url(#${mid})` }, svg);
      L("M200 234 H246", css("--accent"), "", "ah");
      L("M1368 92 H1442", css("--accent"), "", "ah");
      L("M1571 134 V226", css("--accent"), "", "ah");
      L("M1571 336 V406", css("--accent"), "", "ah");
      L("M1442 268 H1370", css("--warn"), "7 6", "ahw");
      // бегущая точка
      const c = svgEl("circle", { r: 9, fill: css("--accent") }, svg);
      svgEl("animateMotion", { dur: "7s", repeatCount: "indefinite", path: "M200 234 H236 V98 H1380 V92 H1571 V300" }, c);
      svgEl("animate", { attributeName: "opacity", values: "0;1;1;0", keyTimes: "0;.06;.94;1", dur: "7s", repeatCount: "indefinite" }, c);
    },
  };

  /* ---------- Старт ---------- */
  const h = parseInt(location.hash.slice(1), 10);
  const start = h >= 1 && h <= slides.length ? h - 1 : 0;
  go(start, 0);
  document.body.classList.add("hud");
  setTimeout(() => document.body.classList.remove("hud"), 3000);
})();
