// homelab (F-024): de live logs van job-runs. De pagina werkt ook zonder dit script. (Het overzicht staat in frontend/.)
"use strict";

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

// Een losse log op een run-pagina (die van een taak is TypeScript, frontend/src/log.ts).
for (const box of document.querySelectorAll(".log[data-key]")) {
  box.textContent = "";
  new LogView(box.dataset.key, box).start();
}
