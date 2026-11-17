// The job pages: one job ("/job/<name>"), a draft ("/draft/<id>"), a run ("/run/<id>") and Research ("/research").
// Data from GET /api/job/<name>, /api/draft/<id>, /api/run/<id> and /api/research. The forms post to the same URLs as
// before; an error comes back as `{error}` and is shown on the page, a redirect is followed.
import { getJson } from "../api/client";
import type { components } from "../api/schema";
import { el, type Child } from "../dom";
import { submitForm } from "../form";
import { LogView } from "../log";
import { empty, link, runRow } from "./jobs";
import { reloadWhile } from "./tasks";

type JobFacts = components["schemas"]["JobFacts"];
type JobDetail = components["schemas"]["JobDetail"];
type DraftDetail = components["schemas"]["DraftDetail"];
type RunDetail = components["schemas"]["RunDetail"];
type ResearchPage = components["schemas"]["ResearchPage"];

const MODES: Record<string, string> = {
  quick: "quick: een paar minuten, een handvol bronnen",
  deep: "deep: tot de grens van een inbox-taak",
};
const DELIVER: Record<string, string> = { page: "alleen de pagina", ntfy: "ntfy (push)", conversation: "het gesprek (Discord)" };

const failed = (text: string): HTMLElement => el("p", { class: "st failed", text });

/** A form that posts in the background to `action` (the CSRF token is added); `next` is where a 303 leads, else the redirect is followed. */
function form(action: string, error: HTMLElement, button: string, cls: string | undefined, inner: Child[] = [], next?: string): HTMLElement {
  const f = el("form", { method: "post", action }, ...inner, el("button", cls ? { class: cls, text: button } : { text: button }));
  f.addEventListener("submit", (ev) => {
    ev.preventDefault();
    const fields = Object.fromEntries([...new FormData(f as HTMLFormElement)].map(([k, v]) => [k, String(v)]));
    void submitForm(action, fields, error, next);
  });
  return f;
}

const textarea = (name: string, value: string, attrs: Record<string, string> = {}): HTMLElement =>
  el("textarea", { name, required: "", ...attrs, text: value });

/** The facts of a job, as the job page and the draft page show them, with the instruction. */
export function factsView(f: JobFacts): Child[] {
  const rows: [string, Child | Child[]][] = [
    ["Wanneer", [f.when || "-", " ", el("span", { class: "muted", text: `(${f.schedule}, ${f.timezone})` })]],
    ["Volgende keren", f.paused ? "gepauzeerd" : f.next_times.join(", ")],
    ["Soort", f.kind],
    ["Model", f.model === "local" ? "lokaal (gratis)" : "Claude (telt mee voor de daglimiet)"],
  ];
  if (f.mode) rows.push(["Modus", MODES[f.mode] ?? f.mode]);
  if (f.repo) rows.push(["Repo", f.repo]);
  if (f.script) rows.push(["Script", el("code", { text: f.script })]);
  if (f.sources.length) rows.push(["Bronnen", f.sources.flatMap((s, i) => (i ? [el("br"), s] : [s]))]);
  rows.push(["Resultaat naar", DELIVER[f.deliver] ?? f.deliver]);
  if (f.nothing !== null) rows.push(["Niets te melden", el("pre", { text: f.nothing })]);
  rows.push(["Skills", f.skills.length ? f.skills.flatMap((s, i) => (i ? [", ", link("/skills", s)] : [link("/skills", s)])) : "-"]);
  if (f.minutes !== null) rows.push(["Bespaart", `${f.minutes} min per run`]);
  return [el("dl", {}, ...rows.flatMap(([k, v]) => [el("dt", { text: k }), el("dd", {}, ...(Array.isArray(v) ? v : [v]))])),
    el("h2", { text: "Instructie" }), el("pre", { text: f.instruction })];
}

const runs = (list: JobDetail["runs"]): HTMLElement[] => (list.length ? list.map(runRow) : [empty("Nog geen runs.")]);

export async function renderJob(main: HTMLElement, name: string): Promise<void> {
  let d: JobDetail;
  try {
    d = await getJson<JobDetail>(`/api/job/${name}`);
  } catch (ex) {
    if (name === "research") return renderResearch(main); // the page of research questions, unless a job has that name
    throw ex;
  }
  const error = el("p", { class: "st failed" });
  const url = `/job/${name}`;
  const parts: Child[] = [el("h1", { text: `Job ${name}` }), error];
  if (d.error || !d.facts) {
    parts.push(failed(`Fout in het bestand: ${d.error ?? ""}`));
  } else {
    parts.push(...factsView(d.facts),
      form(`${url}/run`, error, "Nu draaien", undefined),
      form(`${url}/${d.paused ? "resume" : "pause"}`, error, d.paused ? "Hervatten" : "Pauzeren", d.paused ? undefined : "ghost"),
      el("h2", { text: "Wijzigen" }),
      form(`${url}/change`, error, "Wijziging opstellen", undefined,
        [textarea("text", "", { placeholder: "Bijvoorbeeld: om 8:00 in plaats van 7:00" })]));
  }
  parts.push(el("h2", { text: "Geschiedenis" }), ...runs(d.runs),
    el("h2", { text: "Bestand" }), form(`${url}/save`, error, "Opslaan", undefined, [textarea("text", d.text, { rows: "14" })]),
    form(`${url}/delete`, error, "Job verwijderen", "ghost"));
  main.replaceChildren(...parts.filter((p): p is Node | string => Boolean(p)));
  reloadWhile(d.refresh);
}

export async function renderDraft(main: HTMLElement, id: string): Promise<void> {
  const d = await getJson<DraftDetail>(`/api/draft/${id}`);
  const error = el("p", { class: "st failed" });
  const parts: Child[] = [el("h1", { text: `Concept #${d.id}${d.base ? ` voor ${d.base}` : ""}` }), el("pre", { text: d.request })];
  if (d.status === "drafting") {
    parts.push(el("p", { class: "muted", text: "Claude stelt de job op…" }));
  } else if (d.status === "failed") {
    parts.push(failed(`Mislukt: ${d.error ?? ""}`));
  } else if (d.status === "confirmed" || d.status === "cancelled") {
    parts.push(el("p", { class: "muted", text: d.status === "confirmed" ? "Bevestigd." : "Geannuleerd." }));
  } else {
    if (d.facts) parts.push(...factsView(d.facts));
    if (d.text_error) error.textContent = d.text_error;
    parts.push(error,
      form(`/draft/${d.id}/confirm`, error, "Bevestigen", undefined, [el("h2", { text: "Bestand" }), textarea("text", d.text, { rows: "14" })]),
      form(`/draft/${d.id}/cancel`, error, "Annuleren", "ghost", [], "/jobs"));
  }
  main.replaceChildren(...parts.filter((p): p is Node | string => Boolean(p)));
  reloadWhile(d.refresh);
}

export async function renderRun(main: HTMLElement, id: string): Promise<void> {
  const d = await getJson<RunDetail>(`/api/run/${id}`);
  const error = el("p", { class: "st failed" });
  const log = d.log_key ? el("div", { class: "log", role: "log", "data-key": d.log_key }) : null;
  const parts: Child[] = [
    el("h1", {}, "Job ", link(d.job_url, d.job)),
    el("div", { class: "row-title" }, el("span", { text: d.due }), el("span", { class: `st run-${d.status}`, text: d.status_label }),
      el("span", { text: d.model === "lokaal" ? "lokaal model" : d.model })),
    el("pre", { text: d.text }),
    d.note ? el("p", { class: "muted", text: d.note }) : null,
    error,
    d.cancellable ? form(`/run/${d.id}/cancel`, error, "Annuleren", "ghost", [], `/run/${d.id}`) : null,
    log ? el("h2", { text: "Log" }) : null, log];
  main.replaceChildren(...parts.filter((p): p is Node | string => Boolean(p)));
  if (log && d.log_key) new LogView(d.log_key, log).start();
  reloadWhile(d.refresh);
}

export async function renderResearch(main: HTMLElement): Promise<void> {
  const d = await getJson<ResearchPage>("/api/research");
  const error = el("p", { class: "st failed" });
  main.replaceChildren(
    el("h1", { text: "Onderzoek" }),
    el("p", { class: "muted" }, "Een vraag, een rapport met bronnen. Eerst de privézoekindex, dan het web. ",
      el("b", { text: "quick" }), ": een paar minuten, een handvol bronnen. ", el("b", { text: "deep" }),
      ": tot de grens van een inbox-taak. "
      + `Elke vraag telt als een Claude-run voor de daglimiet (${d.claude_runs_today} van ${d.claude_cap} vandaag). `
      + "Alleen lezen: er komt nooit een commit of PR."),
    error,
    form("/research/ask", error, "Onderzoek starten", undefined, [
      el("label", { for: "rq", text: "Vraag" }), textarea("question", "", { id: "rq" }),
      el("label", { for: "rm", text: "Modus" }),
      el("select", { name: "mode", id: "rm" }, ...d.modes.map((m) => el("option", { value: m, text: m })))], "/research"),
    el("h2", { text: "Rapporten" }),
    ...(d.runs.length ? d.runs.map(runRow) : [empty("Nog geen vragen.")]),
    el("p", { class: "muted" }, "Geplande onderzoeken staan als job (", el("code", { text: "kind: research" }), ") op de tab Jobs."));
  reloadWhile(d.refresh);
}
