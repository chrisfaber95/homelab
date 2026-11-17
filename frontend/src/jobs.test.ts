import { expect, test } from "vitest";
import { catalogueHtml, jobsHtml, type JobsPage } from "./jobs";

const data: JobsPage = {
  claude_runs_today: 2,
  claude_cap: 10,
  timezone: "Europe/Amsterdam",
  refresh: false,
  drafts: [{ id: 7, status: "ready", request: "Eerste regel <x>" }],
  catalogue: [
    { name: "morning", error: null, on: false, what: "Say <hi>", when: "daily", model: "lokaal", deliver: "page", fill: "sources" },
    { name: "live", error: null, on: true, what: "w", when: "daily", model: "Claude", deliver: "ntfy", fill: null },
    { name: "bad", error: "kop <kapot>" },
  ],
  jobs: [
    { name: "a-job", error: null, kind: "Box-rapport", model: "lokaal", when: "daily", next: "morgen 07:00", paused: false, last: "gelukt" },
    { name: "broken", error: "stuk" },
  ],
  runs: [
    { id: 1, job: "a-job", job_url: "/job/a-job", due: "16-11 07:00", status: "ok", status_label: "gelukt", url: "/run/1", model: "lokaal", manual: true, pr: null, summary: "Alles goed" },
  ],
};

test("the page keeps its texts, links and form actions", () => {
  const h = jobsHtml(data, "tok");
  for (const s of [
    "<h1>Jobs</h1>",
    "Claude-runs door jobs vandaag: 2 van 10. Tijden in Europe/Amsterdam.",
    '<a href="/research">Onderzoeksvraag stellen</a>',
    'action="/jobs/draft"',
    'name="csrf" value="tok"',
    "Job opstellen",
    "<h2>Concepten</h2>",
    '<a href="/draft/7">concept #7</a>',
    "Eerste regel &lt;x&gt;",
    "<h2>Catalogus</h2>",
    "<h2>Alle jobs</h2>",
    "volgende: morgen 07:00",
    "laatste: gelukt",
    "<h2>Laatste runs</h2>",
    '<a href="/run/1">16-11 07:00</a>',
    "lokaal model",
    "nu gedraaid",
    "Alles goed",
  ]) {
    expect(h).toContain(s);
  }
});

test("catalogue: switch-on form, 'staat aan' link, broken file, fill hint", () => {
  const h = catalogueHtml(data.catalogue, "tok");
  expect(h).toContain('action="/agents/morning/on"');
  expect(h).toContain("Aanzetten");
  expect(h).toContain('<a href="/job/live">staat aan</a>');
  expect(h).not.toContain("/agents/live/on");
  expect(h).toContain("fout in het bestand");
  expect(h).toContain("kop &lt;kapot&gt;");
  expect(h).toContain("in te vullen bij aanzetten: sources");
  expect(h).toContain("bezorgt: page");
  expect(h).toContain("Say &lt;hi&gt;");
});

test("empty page says there is nothing", () => {
  const h = jobsHtml({ ...data, drafts: [], catalogue: [], jobs: [], runs: [] }, "t");
  for (const s of ["Geen agents.", "Nog geen jobs.", "Nog geen runs."]) expect(h).toContain(s);
  expect(h).not.toContain("Concepten");
});

test("a broken job shows only its error", () => {
  expect(jobsHtml(data, "t")).toContain('<span class="st failed">fout in het bestand</span>');
});
