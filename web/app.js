// homelab (F-024): "Nu bezig" en de live logs. De pagina werkt ook zonder dit script; dit houdt hem bij zonder herladen.
"use strict";

const POLL_LIVE = 2500;  // ms; een afgeronde run verdwijnt dus binnen een paar seconden
const POLL_LOG = 2000;   // ms; het log loopt hooguit ~2 s achter

function el(tag, attrs = {}, ...kids) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") n.className = v;
    else if (k === "text") n.textContent = v;
    else n.setAttribute(k, v);
  }
  for (const k of kids) n.append(k);
  return n;
}

function elapsed(iso) {
  const s = Math.max(0, Math.round((Date.now() - Date.parse(iso)) / 1000));
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} u ${m % 60} min`;
}

function clock(iso) {
  return new Date(iso).toLocaleTimeString("nl-NL", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

// Eén log-paneel: haalt nieuwe regels op vanaf de laatste, tot de run klaar is.
class LogView {
  constructor(key, box) {
    this.key = key;
    this.box = box;
    this.next = 0;
    this.timer = null;
    this.done = false;
  }
  start() {
    this.tick();
  }
  stop() {
    clearTimeout(this.timer);
  }
  async tick() {
    try {
      const r = await fetch(`/api/log/${this.key}?from=${this.next}`, { cache: "no-store" });
      if (r.ok) {
        const data = await r.json();
        const atBottom = this.box.scrollHeight - this.box.scrollTop - this.box.clientHeight < 24;
        for (const line of data.lines) {
          this.box.append(el("div", { class: `k-${line.kind}` },
            el("time", { datetime: line.at, text: clock(line.at) }), line.text));
        }
        if (!data.lines.length && !this.next && !this.empty) {
          this.empty = el("div", { class: "log-end", text: "Nog geen regels." });
          this.box.append(this.empty);
        }
        if (data.lines.length && this.empty) { this.empty.remove(); this.empty = null; }
        this.next = data.next;
        if (atBottom) this.box.scrollTop = this.box.scrollHeight;
        if (data.done) {
          this.done = true;
          this.box.append(el("div", { class: "log-end", text: "Klaar." }));
          return;
        }
      }
    } catch (e) { /* box even weg: volgende keer opnieuw */ }
    this.timer = setTimeout(() => this.tick(), POLL_LOG);
  }
}

// Een losse log op een taak- of run-pagina.
for (const box of document.querySelectorAll(".log[data-key]")) {
  box.textContent = "";
  new LogView(box.dataset.key, box).start();
}

// "Nu bezig" op het overzicht.
const live = document.getElementById("live");
if (live) {
  const csrf = live.dataset.csrf;
  const open = new Map();  // key -> LogView
  const list = live.querySelector(".live-list");
  const activity = document.getElementById("activity");

  function cancelForm(url) {
    return el("form", { method: "post", action: url },
      el("input", { type: "hidden", name: "csrf", value: csrf }),
      el("button", { class: "linkbtn danger", text: "Annuleren" }));
  }

  function render(items) {
    const keep = new Set(items.map((i) => i.key));
    for (const [key, view] of open) if (!keep.has(key)) { view.stop(); open.delete(key); }
    const nodes = items.map((it) => {
      const existing = list.querySelector(`[data-key="${it.key}"]`);
      if (existing) {
        existing.querySelector(".elapsed").textContent = elapsed(it.started);
        return existing;
      }
      const meta = el("div", { class: "row-meta" },
        el("span", { text: it.repo }), el("span", { text: it.model }),
        el("span", {}, "bezig sinds ", el("span", { class: "elapsed", text: elapsed(it.started) })));
      const toggle = el("button", { class: "linkbtn", type: "button", "aria-expanded": "false",
                                    text: "Log tonen" });
      const node = el("div", { class: "live-item", "data-key": it.key, "data-started": it.started },
        el("span", { class: "pulse", "aria-hidden": "true" }),
        el("div", {},
          el("div", { class: "row-title" }, el("a", { href: it.url, text: it.title }), el("span", { text: it.what })),
          meta,
          el("div", { class: "live-actions" }, toggle, cancelForm(it.cancel))));
      toggle.addEventListener("click", () => {
        const shown = open.get(it.key);
        if (shown) {
          shown.stop(); open.delete(it.key);
          node.querySelector(".log").remove();
          toggle.textContent = "Log tonen"; toggle.setAttribute("aria-expanded", "false");
        } else {
          const box = el("div", { class: "log", role: "log", "aria-live": "polite" });
          node.append(box);
          const view = new LogView(it.key, box);
          open.set(it.key, view); view.start();
          toggle.textContent = "Log verbergen"; toggle.setAttribute("aria-expanded", "true");
        }
      });
      return node;
    });
    // Only move what changed: a node taken out and put back (replaceChildren) loses the scroll position of its log.
    for (const n of [...list.children]) if (!nodes.includes(n)) n.remove();
    nodes.forEach((n, i) => { if (list.children[i] !== n) list.insertBefore(n, list.children[i] || null); });
    if (!items.length) list.append(el("p", { class: "empty", text: "Niets bezig. Taken en jobs verschijnen hier zodra ze starten." }));
  }

  async function poll() {
    if (!document.hidden) {
      try {
        const r = await fetch("/api/live", { cache: "no-store" });
        if (r.ok) {
          const data = await r.json();
          render(data.items);
          if (activity) activity.textContent = data.activity;
          // De scanner onder de statuszin loopt sneller zolang er iets draait.
          document.querySelector(".hero")?.classList.toggle("busy", data.items.length > 0);
          const q = live.querySelector(".queued");
          if (q) q.textContent = data.queued ? `${data.queued} in de rij` : "";
        }
      } catch (e) { /* even geen verbinding */ }
    }
    setTimeout(poll, POLL_LIVE);
  }
  // De server zette de eerste lijst al neer (zonder knoppen); de eerste poll vervangt hem.
  poll();
  setInterval(() => {
    for (const node of list.querySelectorAll(".live-item")) {
      node.querySelector(".elapsed").textContent = elapsed(node.dataset.started);
    }
  }, 1000);
}

// Search tab (H-012): ask, list sources, thumbs-down. Talks to the inbox only (/api/search/*).
const searchForm = document.getElementById("search-form");
if (searchForm) {
  const csrf = searchForm.dataset.csrf;
  const status = document.getElementById("sstatus");
  const answerBox = document.getElementById("sanswer");
  const resultsBox = document.getElementById("sresults");
  const buttons = searchForm.querySelectorAll("button");
  let seq = 0;  // a newer question makes an older, slower reply stale

  const safeHref = (u) => (/^(https?|obsidian):/i.test(u || "") ? u : null);
  const say = (text, bad = false) => { status.textContent = text; status.classList.toggle("error", bad); };
  const errorOf = async (r) => {
    try { return (await r.json()).error?.message || `HTTP ${r.status}`; } catch (e) { return `HTTP ${r.status}`; }
  };
  const post = (path, fields) => fetch(path, {
    method: "POST", cache: "no-store",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ csrf, ...fields }),
  });
  const filters = () => {
    const f = {};
    for (const k of ["project", "type"]) if (searchForm.elements[k].value) f[k] = searchForm.elements[k].value;
    return f;
  };

  fetch("/api/search/filters", { cache: "no-store" }).then((r) => (r.ok ? r.json() : null)).then((d) => {
    if (!d) return;
    for (const [id, list] of [["sproject", d.projects], ["stype", d.types]]) {
      for (const v of list || []) document.getElementById(id).append(el("option", { value: v, text: v }));
    }
    if (!d.chunks) say("The index is empty. Run homelab-search sync on the box.", true);
  }).catch(() => {});

  function resultRow(h) {
    const href = safeHref(h.link);
    const title = href ? el("a", { href, text: h.path }) : el("b", { text: h.path });
    return el("div", { class: "row" },
      el("div", { class: "row-title" }, title, el("span", { class: "kind", text: h.type })),
      el("div", { class: "row-meta" }, el("span", { text: h.location }), el("span", { text: h.date || "-" }),
        el("span", { text: h.project || "-" }), el("span", { text: h.source })),
      h.snippet ? el("p", { class: "snippet", text: h.snippet }) : "");
  }

  function showResults(list) {
    resultsBox.replaceChildren(el("h2", { text: `Sources (${list.length})` }),
      ...(list.length ? list.map(resultRow) : [el("p", { class: "empty", text: "No results." })]));
    resultsBox.hidden = false;
  }

  function citedText(a) {
    // [n] in the answer becomes a link to that source (or to its place in the list when it has no link).
    const links = Object.fromEntries((a.citations || []).map((c) => [c.n, safeHref(c.link)]));
    const p = el("p", { class: "answer-text" });
    for (const part of a.answer.split(/(\[\d+\])/)) {
      const m = /^\[(\d+)\]$/.exec(part);
      if (m && (a.citations || []).some((c) => c.n === +m[1])) {
        p.append(el("a", { class: "cite", href: links[m[1]] || `#cite-${m[1]}`, text: part }));
      } else p.append(part);
    }
    return p;
  }

  function showAnswer(a) {
    const kids = [el("h2", { text: "Answer" }), citedText(a)];
    const cites = a.citations || [];
    if (cites.length) {
      kids.push(el("ol", { class: "cites" }, ...cites.map((c) => {
        const href = safeHref(c.link);
        const name = `${c.path ?? c.source} · ${c.location}`;
        return el("li", { id: `cite-${c.n}`, value: c.n, class: c.cited ? "" : "uncited" },
          href ? el("a", { href, text: name }) : name, el("span", { class: "muted", text: ` ${c.date || ""} ${c.project || ""}` }));
      })));
    }
    const how = a.found ? `${a.backend || "?"} · ${a.hard ? "hard" : "easy"} tier` : "";
    const down = el("button", { type: "button", class: "quiet small", "aria-label": "Mark this answer as bad", text: "👎 Bad answer" });
    if (!a.request_id) down.hidden = true;
    down.addEventListener("click", async () => {
      down.disabled = true;
      const r = await post("/api/search/feedback", { request_id: a.request_id }).catch(() => null);
      if (r && r.ok) down.textContent = "Marked as bad";
      else { down.disabled = false; say(r ? await errorOf(r) : "Could not reach the inbox.", true); }
    });
    kids.push(el("div", { class: "answer-foot" }, down, el("span", { class: "muted", text: how })));
    answerBox.replaceChildren(...kids);
    answerBox.hidden = false;
  }

  async function run(withAnswer) {
    const q = searchForm.elements.q.value.trim();
    if (!q) return;
    const mine = ++seq;
    buttons.forEach((b) => { b.disabled = true; });
    answerBox.hidden = true;
    resultsBox.hidden = true;
    const hard = withAnswer && searchForm.elements.hard.checked;
    say(withAnswer ? (hard ? "Asking Claude…" : "Thinking…") : "Searching…");
    const qs = new URLSearchParams({ q, ...filters() });
    const listed = fetch(`/api/search/results?${qs}`, { cache: "no-store" });
    const asked = withAnswer
      ? post("/api/search/ask", { question: q, ...filters(), ...(hard ? { hard: "1" } : {}) }) : null;
    try {
      const lr = await listed;
      if (mine !== seq) return;
      if (lr.ok) showResults((await lr.json()).results || []);
      else say(await errorOf(lr), true);
      if (asked) {
        const ar = await asked;
        if (mine !== seq) return;
        if (ar.ok) { showAnswer(await ar.json()); if (lr.ok) say(""); } else say(await errorOf(ar), true);
      } else if (lr.ok) say("");
    } catch (e) {
      if (mine === seq) say("Could not reach the inbox.", true);
    } finally {
      if (mine === seq) buttons.forEach((b) => { b.disabled = false; });
    }
  }

  searchForm.addEventListener("submit", (ev) => { ev.preventDefault(); run(true); });
  document.getElementById("ssearch").addEventListener("click", () => {
    if (searchForm.reportValidity()) run(false);
  });
}
