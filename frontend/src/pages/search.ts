// The Search page ("/search", H-012): the question box, filters, cited answer, raw results and the thumbs-down.
// Talks to the inbox only: GET /api/search/filters and /api/search/results, POST /api/search/ask and /feedback.
import { getJson, postForm } from "../api/client";
import { el, meta } from "../dom";

interface Filters { projects?: string[]; types?: string[]; chunks?: number }
interface Hit { path: string; type: string; location: string; date?: string; project?: string; source: string; link?: string; snippet?: string }
interface Citation { n: number; path?: string; source?: string; location: string; link?: string; date?: string; project?: string; cited?: boolean }
interface Answer { found?: boolean; answer: string; citations?: Citation[]; request_id?: string; backend?: string; hard?: boolean }

/** Only web and Obsidian links are followed; anything else (javascript:, data:) is shown as plain text. */
export const safeHref = (u: string | undefined): string | null => (/^(https?|obsidian):/i.test(u ?? "") ? (u as string) : null);

async function errorOf(r: Response): Promise<string> {
  try { return ((await r.json()) as { error?: string }).error || `HTTP ${r.status}`; } catch { return `HTTP ${r.status}`; }
}

function resultRow(h: Hit): HTMLElement {
  const href = safeHref(h.link);
  const title = href ? el("a", { href, text: h.path }) : el("b", { text: h.path });
  return el("div", { class: "row" },
    el("div", { class: "row-title" }, title, el("span", { class: "kind", text: h.type })),
    meta(h.location, h.date || "-", h.project || "-", h.source),
    h.snippet ? el("p", { class: "snippet", text: h.snippet }) : null);
}

/** The answer split into text and `[n]` markers; a marker is a `cite` only when that citation exists. */
export function citeParts(a: Answer): { text: string; cite?: number; href?: string }[] {
  const cites = a.citations ?? [];
  return a.answer.split(/(\[\d+\])/).filter(Boolean).map((text) => {
    const n = /^\[(\d+)\]$/.exec(text)?.[1];
    const c = n === undefined ? undefined : cites.find((x) => x.n === +n);
    return c ? { text, cite: c.n, href: safeHref(c.link) || `#cite-${c.n}` } : { text };
  });
}

/** [n] in the answer becomes a link to that source (or to its place in the list when it has no link). */
function citedText(a: Answer): HTMLElement {
  const p = el("p", { class: "answer-text" });
  for (const part of citeParts(a)) p.append(part.cite === undefined ? part.text : el("a", { class: "cite", href: part.href as string, text: part.text }));
  return p;
}

export async function renderSearch(main: HTMLElement): Promise<void> {
  const q = el("textarea", { id: "sq", name: "q", rows: "2", maxlength: "2000", required: "", placeholder: "Where did we decide on sqlite?" }) as HTMLTextAreaElement;
  const project = el("select", { id: "sproject", name: "project" }, el("option", { value: "", text: "All projects" })) as HTMLSelectElement;
  const type = el("select", { id: "stype", name: "type" }, el("option", { value: "", text: "All types" })) as HTMLSelectElement;
  const hard = el("input", { type: "checkbox", id: "shard", name: "hard", value: "1" }) as HTMLInputElement;
  const ask = el("button", { type: "submit", id: "sask", text: "Ask" }) as HTMLButtonElement;
  const list = el("button", { type: "button", class: "quiet", id: "ssearch", text: "Only list sources" }) as HTMLButtonElement;
  const form = el("form", { id: "search-form", class: "search", role: "search" },
    el("label", { for: "sq", text: "Question" }), q,
    el("div", { class: "search-filters" },
      el("div", {}, el("label", { for: "sproject", text: "Project" }), project),
      el("div", {}, el("label", { for: "stype", text: "Source type" }), type)),
    el("label", { class: "check", for: "shard" }, hard,
      el("span", {}, "Hard question ", el("small", { class: "muted", text: "answered by Claude, uses allowance" }))),
    el("div", { class: "search-actions" }, ask, list)) as HTMLFormElement;
  const status = el("p", { id: "sstatus", class: "muted", role: "status", "aria-live": "polite" });
  const answerBox = el("section", { id: "sanswer", "aria-live": "polite" });
  const resultsBox = el("section", { id: "sresults" });
  answerBox.hidden = resultsBox.hidden = true;
  main.replaceChildren(
    el("h1", { text: "Search" }),
    el("p", { class: "muted", text: "Ask about anything written down: the second brain, briefs, sessions, repo docs and memory. Private, answered on the box." }),
    form, status, answerBox, resultsBox);

  let seq = 0; // a newer question makes an older, slower reply stale
  const say = (text: string, bad = false): void => { status.textContent = text; status.classList.toggle("error", bad); };
  const filters = (): Record<string, string> => {
    const f: Record<string, string> = {};
    if (project.value) f.project = project.value;
    if (type.value) f.type = type.value;
    return f;
  };

  getJson<Filters>("/api/search/filters").then((d) => {
    for (const [box, values] of [[project, d.projects], [type, d.types]] as const) {
      for (const v of values ?? []) box.append(el("option", { value: v, text: v }));
    }
    if (!d.chunks) say("The index is empty. Run homelab-search sync on the box.", true);
  }).catch(() => { /* the form still works without filters */ });

  function showResults(hits: Hit[]): void {
    resultsBox.replaceChildren(el("h2", { text: `Sources (${hits.length})` }),
      ...(hits.length ? hits.map(resultRow) : [el("p", { class: "empty", text: "No results." })]));
    resultsBox.hidden = false;
  }

  function showAnswer(a: Answer): void {
    const kids: HTMLElement[] = [el("h2", { text: "Answer" }), citedText(a)];
    const cites = a.citations ?? [];
    if (cites.length) {
      kids.push(el("ol", { class: "cites" }, ...cites.map((c) => {
        const href = safeHref(c.link);
        const name = `${c.path ?? c.source} · ${c.location}`;
        const li = el("li", { id: `cite-${c.n}`, value: String(c.n), class: c.cited ? "" : "uncited" },
          href ? el("a", { href, text: name }) : name, el("span", { class: "muted", text: ` ${c.date || ""} ${c.project || ""}` }));
        return li;
      })));
    }
    const how = a.found ? `${a.backend || "?"} · ${a.hard ? "hard" : "easy"} tier` : "";
    const down = el("button", { type: "button", class: "quiet small", "aria-label": "Mark this answer as bad", text: "👎 Bad answer" }) as HTMLButtonElement;
    if (!a.request_id) down.hidden = true;
    down.addEventListener("click", () => {
      down.disabled = true;
      void postForm("/api/search/feedback", { request_id: a.request_id ?? "" }).catch(() => null).then(async (r) => {
        if (r?.ok) down.textContent = "Marked as bad";
        else { down.disabled = false; say(r ? await errorOf(r) : "Could not reach the inbox.", true); }
      });
    });
    kids.push(el("div", { class: "answer-foot" }, down, el("span", { class: "muted", text: how })));
    answerBox.replaceChildren(...kids);
    answerBox.hidden = false;
  }

  async function run(withAnswer: boolean): Promise<void> {
    const question = q.value.trim();
    if (!question) return;
    const mine = ++seq;
    for (const b of [ask, list]) b.disabled = true;
    answerBox.hidden = resultsBox.hidden = true;
    const isHard = withAnswer && hard.checked;
    say(withAnswer ? (isHard ? "Asking Claude…" : "Thinking…") : "Searching…");
    const qs = new URLSearchParams({ q: question, ...filters() });
    const listed = fetch(`/api/search/results?${qs}`, { cache: "no-store" });
    const asked = withAnswer ? postForm("/api/search/ask", { question, ...filters(), ...(isHard ? { hard: "1" } : {}) }) : null;
    try {
      const lr = await listed;
      if (mine !== seq) return;
      if (lr.ok) showResults(((await lr.json()) as { results?: Hit[] }).results ?? []);
      else say(await errorOf(lr), true);
      if (asked) {
        const ar = await asked;
        if (mine !== seq) return;
        if (ar.ok) { showAnswer((await ar.json()) as Answer); if (lr.ok) say(""); } else say(await errorOf(ar), true);
      } else if (lr.ok) say("");
    } catch {
      if (mine === seq) say("Could not reach the inbox.", true);
    } finally {
      if (mine === seq) for (const b of [ask, list]) b.disabled = false;
    }
  }

  form.addEventListener("submit", (ev) => { ev.preventDefault(); void run(true); });
  list.addEventListener("click", () => { if (form.reportValidity()) void run(false); });
}
