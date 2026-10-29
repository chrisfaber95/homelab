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
    list.replaceChildren(...nodes);
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
