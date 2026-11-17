// The Skills page ("/skills"): the merged skills with the jobs that use each, and the latest skill proposals.
// Data from GET /api/skills.
import { getJson } from "../api/client";
import type { paths } from "../api/schema";
import { el, meta, type Child } from "../dom";

type SkillsPage = paths["/api/skills"]["get"]["responses"][200]["content"]["application/json; charset=utf-8"];
type Skill = SkillsPage["skills"][number];
type Proposal = SkillsPage["proposals"][number];

const link = (href: string, text: string): HTMLElement => el("a", { href, text });
const empty = (text: string): HTMLElement => el("p", { class: "empty", text });

function skillRow(s: Skill): HTMLElement {
  const jobs: Child[] = s.jobs.flatMap((j, i) => [i ? ", " : null, link(`/job/${j}`, j)]);
  return el("div", { class: "row" },
    el("div", { class: "row-title" }, el("b", { text: s.name }), el("span", { text: s.description })),
    meta(el("span", {}, "jobs: ", ...(jobs.length ? jobs : ["geen"]))));
}

function proposalRow(p: Proposal): HTMLElement {
  return el("div", { class: "row" },
    el("div", { class: "row-title" }, el("b", { text: p.name }), link(p.pr_url, "PR")),
    meta(el("span", {}, "uit ", p.task_id === null ? "taak" : link(`/task/${p.task_id}`, `taak #${p.task_id}`)), p.created));
}

export async function renderSkills(main: HTMLElement): Promise<void> {
  const d = await getJson<SkillsPage>("/api/skills");
  main.replaceChildren(
    el("h1", { text: "Skills" }),
    el("p", { class: "muted", text: d.intro }),
    ...(d.skills.length ? d.skills.map(skillRow) : [empty(d.empty)]),
    el("h2", { text: "Voorstellen" }),
    ...(d.proposals.length ? d.proposals.map(proposalRow) : [empty(d.proposals_empty)]));
}
