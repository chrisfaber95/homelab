import type { components } from "./api/schema";
import { esc, form, meta } from "./html";

export type JobsPage = components["schemas"]["JobsPage"];
type Catalogue = components["schemas"]["CatalogueAgent"];
type Job = components["schemas"]["JobSummary"];
type Run = components["schemas"]["JobRunSummary"];

const empty = (text: string) => `<p class=empty>${text}</p>`;

export function catalogueHtml(agents: Catalogue[], csrf: string): string {
  const rows = agents.map((a) => {
    const name = esc(a.name);
    if (a.error) {
      return (
        `<div class="row"><div class="row-title">${name}<span class="st failed">fout in het bestand</span></div>` +
        `${meta(esc(a.error))}</div>`
      );
    }
    const action = a.on
      ? `<a href="/job/${name}">staat aan</a>`
      : form(csrf, `/agents/${a.name}/on`, "", "Aanzetten", "small");
    return (
      `<div class="row"><div class="row-title">${name}<span>${action}</span></div>` +
      `<p class="muted">${esc(a.what ?? "")}</p>` +
      meta(
        esc(a.when ?? ""),
        `model: ${esc(a.model ?? "")}`,
        `bezorgt: ${esc(a.deliver ?? "")}`,
        a.fill ? `in te vullen bij aanzetten: ${esc(a.fill)}` : "",
      ) +
      "</div>"
    );
  });
  return (
    "<h2>Catalogus</h2><p class=\"muted\">Kant-en-klare agents uit <code>agents/</code> in de repo. Aanzetten toont " +
    "eerst het jobbestand; pas na Bevestigen bestaat de job.</p>" +
    (rows.join("") || empty("Geen agents."))
  );
}

function jobRow(j: Job): string {
  const link = `<a href="/job/${esc(j.name)}">${esc(j.name)}</a>`;
  if (j.error) {
    return (
      `<div class="row"><div class="row-title">${link}<span class="st failed">fout in het bestand</span></div>` +
      `${meta(esc(j.error))}</div>`
    );
  }
  return (
    `<div class="row"><div class="row-title">${link}<span>${esc(j.kind ?? "")}, ${esc(j.model ?? "")}</span></div>` +
    meta(esc(j.when ?? ""), `volgende: ${esc(j.next ?? "")}`, j.last ? `laatste: ${esc(j.last)}` : "") +
    "</div>"
  );
}

function runRow(r: Run): string {
  return (
    `<div class="row"><div class="row-title"><a href="${esc(r.job_url)}">${esc(r.job)}</a>` +
    `<a href="${esc(r.url)}">${esc(r.due)}</a>` +
    `<span class="st run-${esc(r.status)}">${esc(r.status_label)}</span></div>` +
    meta(
      esc(r.model === "lokaal" ? "lokaal model" : r.model),
      r.manual ? "nu gedraaid" : "",
      r.pr ? `<a href="${esc(r.pr)}">PR</a>` : "",
      esc(r.summary),
    ) +
    "</div>"
  );
}

/** The body of the Jobs page: what `pages.jobs_page` rendered, from the data of GET /api/jobs-page. */
export function jobsHtml(d: JobsPage, csrf: string): string {
  const drafts = d.drafts
    .map(
      (x) =>
        `<div class="row"><div class="row-title"><a href="/draft/${x.id}">concept #${x.id}</a>` +
        `<span>${esc(x.request)}</span></div></div>`,
    )
    .join("");
  return (
    "<h1>Jobs</h1>" +
    `<p class="muted">Claude-runs door jobs vandaag: ${d.claude_runs_today} van ${d.claude_cap}. ` +
    `Tijden in ${esc(d.timezone)}.</p>` +
    '<p><a href="/research">Onderzoeksvraag stellen</a></p>' +
    form(
      csrf,
      "/jobs/draft",
      '<label for="jobtext">Wat moet de job doen, en wanneer?</label><textarea name="text" id="jobtext" required ' +
        'placeholder="Bijvoorbeeld: elke werkdag om 7:00, vertel me wat er vannacht misging"></textarea>',
      "Job opstellen",
    ) +
    (drafts ? `<h2>Concepten</h2>${drafts}` : "") +
    catalogueHtml(d.catalogue, csrf) +
    `<h2>Alle jobs</h2>${d.jobs.map(jobRow).join("") || empty("Nog geen jobs.")}` +
    `<h2>Laatste runs</h2>${d.runs.map(runRow).join("") || empty("Nog geen runs.")}`
  );
}
