// homelab (F-024): de Search-tab. (De andere pagina's, ook de live logs, staan in frontend/.)
"use strict";

// Mutating /api/* calls need the CSRF token as X-CSRF-Token; it comes from GET /api/session (fetched once).
let csrfPromise = null;
function csrfToken() {
  csrfPromise ||= fetch("/api/session", { cache: "no-store" }).then((r) => r.json()).then((d) => d.csrf_token)
    .catch((e) => { csrfPromise = null; throw e; });
  return csrfPromise;
}

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

// Search tab (H-012): ask, list sources, thumbs-down. Talks to the inbox only (/api/search/*).
const searchForm = document.getElementById("search-form");
if (searchForm) {
  const status = document.getElementById("sstatus");
  const answerBox = document.getElementById("sanswer");
  const resultsBox = document.getElementById("sresults");
  const buttons = searchForm.querySelectorAll("button");
  let seq = 0;  // a newer question makes an older, slower reply stale

  const safeHref = (u) => (/^(https?|obsidian):/i.test(u || "") ? u : null);
  const say = (text, bad = false) => { status.textContent = text; status.classList.toggle("error", bad); };
  const errorOf = async (r) => {
    try { return (await r.json()).error || `HTTP ${r.status}`; } catch (e) { return `HTTP ${r.status}`; }
  };
  const post = async (path, fields) => fetch(path, {
    method: "POST", cache: "no-store",
    headers: { "Content-Type": "application/x-www-form-urlencoded", "X-CSRF-Token": await csrfToken() },
    body: new URLSearchParams(fields),
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
