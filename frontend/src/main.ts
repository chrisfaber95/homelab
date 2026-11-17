import { renderBrief, renderBriefs } from "./pages/briefs";
import { renderDraft, renderJob, renderResearch, renderRun } from "./pages/job";
import { renderJobs } from "./pages/jobs";
import { renderMemory, renderMemoryEntry } from "./pages/memory";
import { renderOverview } from "./pages/overview";
import { renderSearch } from "./pages/search";
import { renderSkills } from "./pages/skills";
import { renderSsh } from "./pages/ssh";
import { renderTask } from "./pages/task";
import { renderTasks } from "./pages/tasks";
import { renderShell } from "./shell";

const app = document.getElementById("app");
const PAGES: Record<string, { tab: string; title: string; render: (main: HTMLElement) => Promise<void> }> = {
  "/": { tab: "overzicht", title: "homelab", render: renderOverview },
  "/jobs": { tab: "jobs", title: "jobs", render: renderJobs },
  "/research": { tab: "jobs", title: "onderzoek", render: renderResearch },
  "/taken": { tab: "taken", title: "taken", render: renderTasks },
  "/briefs": { tab: "briefs", title: "briefs", render: renderBriefs },
  "/memory": { tab: "geheugen", title: "geheugen", render: renderMemory },
  "/search": { tab: "search", title: "search", render: renderSearch },
  "/skills": { tab: "skills", title: "skills", render: renderSkills },
  "/ssh": { tab: "ssh", title: "ssh", render: renderSsh },
};
const taskId = /^\/task\/(\d+)$/.exec(location.pathname)?.[1];
const briefId = /^\/briefs\/([A-Z]-\d{3,4})$/.exec(location.pathname)?.[1];
const jobName = /^\/job\/([a-z0-9-]+)$/.exec(location.pathname)?.[1];
const draftId = /^\/draft\/(\d+)$/.exec(location.pathname)?.[1];
const runId = /^\/run\/(\d+)$/.exec(location.pathname)?.[1];
const memoryId = /^\/memory\/e\/(.+)$/.exec(location.pathname)?.[1];
const page = PAGES[location.pathname] ?? (taskId ? {
  tab: "taken", title: `inbox #${taskId}`, render: async (main: HTMLElement) => { await renderTask(main, taskId); },
} : memoryId ? {
  tab: "geheugen", title: `geheugen ${memoryId.split("/").pop()}`, render: (main: HTMLElement) => renderMemoryEntry(main, memoryId),
} : jobName ? {
  tab: "jobs", title: `job ${jobName}`, render: (main: HTMLElement) => renderJob(main, jobName),
} : draftId ? {
  tab: "jobs", title: `concept #${draftId}`, render: (main: HTMLElement) => renderDraft(main, draftId),
} : runId ? {
  tab: "jobs", title: `run ${runId}`, render: (main: HTMLElement) => renderRun(main, runId),
} : briefId ? {
  tab: "briefs", title: "briefs", render: (main: HTMLElement) => renderBrief(main, briefId),
} : undefined);

if (app && page) {
  page.render(renderShell(app, page.tab, page.title)).catch((e) => {
    app.append(`Could not load the page: ${e instanceof Error ? e.message : String(e)}`);
  });
}
