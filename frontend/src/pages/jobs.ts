// The Jobs page ("/jobs") with the catalogue: the counter, the new-job form, open drafts, the catalogue of agents/,
// all jobs and the latest runs. Data from GET /api/jobs-page; the forms are plain HTML forms with the CSRF field.
import { csrfToken, getJson } from "../api/client";
import type { components } from "../api/schema";
import { el, meta, type Child } from "../dom";

type JobsPage = components["schemas"]["JobsPage"];
type Agent = components["schemas"]["CatalogueAgent"];
type Job = components["schemas"]["JobSummary"];
type Run = components["schemas"]["JobRunSummary"];

const REFRESH_MS = 20000; // the page reloads itself while a draft is being drafted

export const link = (href: string, text: string): HTMLElement => el("a", { href, text });
const failed = (text: string): HTMLElement => el("span", { class: "st failed", text });
export const empty = (text: string): HTMLElement => el("p", { class: "empty", text });

function form(csrf: string, action: string, button: string, cls?: string, ...inner: Child[]): HTMLElement {
  return el("form", { method: "post", action },
    el("input", { type: "hidden", name: "csrf", value: csrf }), ...inner,
    el("button", cls ? { class: cls, text: button } : { text: button }));
}

function agentRow(a: Agent, csrf: string): HTMLElement {
  if (a.error) return el("div", { class: "row" }, el("div", { class: "row-title" }, a.name, failed("fout in het bestand")), meta(a.error));
  const action = a.on ? link(`/job/${a.name}`, "staat aan") : form(csrf, `/agents/${a.name}/on`, "Aanzetten", "small");
  return el("div", { class: "row" },
    el("div", { class: "row-title" }, a.name, el("span", {}, action)),
    el("p", { class: "muted", text: a.what ?? "" }),
    meta(a.when, `model: ${a.model}`, `bezorgt: ${a.deliver}`, a.fill ? `in te vullen bij aanzetten: ${a.fill}` : ""));
}

function jobRow(j: Job): HTMLElement {
  const head = link(`/job/${j.name}`, j.name);
  if (j.error) return el("div", { class: "row" }, el("div", { class: "row-title" }, head, failed("fout in het bestand")), meta(j.error));
  return el("div", { class: "row" },
    el("div", { class: "row-title" }, head, el("span", { text: `${j.kind}, ${j.model}` })),
    meta(j.when, `volgende: ${j.next}`, j.last ? `laatste: ${j.last}` : ""));
}

export function runRow(r: Run): HTMLElement {
  return el("div", { class: "row" },
    el("div", { class: "row-title" }, link(r.job_url, r.job), link(r.url, r.due),
      el("span", { class: `st run-${r.status}`, text: r.status_label })),
    meta(r.model === "lokaal" ? "lokaal model" : r.model, r.manual ? "nu gedraaid" : "", r.pr ? link(r.pr, "PR") : "", r.summary));
}

export async function renderJobs(main: HTMLElement): Promise<void> {
  const [d, csrf] = await Promise.all([getJson<JobsPage>("/api/jobs-page"), csrfToken()]);
  const drafts = d.drafts.map((x) =>
    el("div", { class: "row" }, el("div", { class: "row-title" }, link(`/draft/${x.id}`, `concept #${x.id}`), el("span", { text: x.request }))));
  main.replaceChildren(
    el("h1", { text: "Jobs" }),
    el("p", { class: "muted", text: `Claude-runs door jobs vandaag: ${d.claude_runs_today} van ${d.claude_cap}. Tijden in ${d.timezone}.` }),
    el("p", {}, link("/research", "Onderzoeksvraag stellen")),
    form(csrf, "/jobs/draft", "Job opstellen", undefined,
      el("label", { for: "jobtext", text: "Wat moet de job doen, en wanneer?" }),
      el("textarea", { name: "text", id: "jobtext", required: "", placeholder: "Bijvoorbeeld: elke werkdag om 7:00, vertel me wat er vannacht misging" })),
    ...(drafts.length ? [el("h2", { text: "Concepten" }), ...drafts] : []),
    el("h2", { text: "Catalogus" }),
    el("p", { class: "muted" }, "Kant-en-klare agents uit ", el("code", { text: "agents/" }),
      " in de repo. Aanzetten toont eerst het jobbestand; pas na Bevestigen bestaat de job."),
    ...(d.catalogue.length ? d.catalogue.map((a) => agentRow(a, csrf)) : [empty("Geen agents.")]),
    el("h2", { text: "Alle jobs" }),
    ...(d.jobs.length ? d.jobs.map(jobRow) : [empty("Nog geen jobs.")]),
    el("h2", { text: "Laatste runs" }),
    ...(d.runs.length ? d.runs.map(runRow) : [empty("Nog geen runs.")]));
  if (d.refresh) setTimeout(() => location.reload(), REFRESH_MS);
}
