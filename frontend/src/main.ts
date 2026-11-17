import { renderBrief, renderBriefs } from "./pages/briefs";
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
  "/taken": { tab: "taken", title: "taken", render: renderTasks },
  "/briefs": { tab: "briefs", title: "briefs", render: renderBriefs },
  "/memory": { tab: "geheugen", title: "geheugen", render: renderMemory },
  "/search": { tab: "search", title: "search", render: renderSearch },
  "/skills": { tab: "skills", title: "skills", render: renderSkills },
  "/ssh": { tab: "ssh", title: "ssh", render: renderSsh },
};
const taskId = /^\/task\/(\d+)$/.exec(location.pathname)?.[1];
const briefId = /^\/briefs\/([A-Z]-\d{3,4})$/.exec(location.pathname)?.[1];
const memoryId = /^\/memory\/e\/(.+)$/.exec(location.pathname)?.[1];
const page = PAGES[location.pathname] ?? (taskId ? {
  tab: "taken", title: `inbox #${taskId}`, render: async (main: HTMLElement) => { await renderTask(main, taskId); },
} : memoryId ? {
  tab: "geheugen", title: `geheugen ${memoryId.split("/").pop()}`, render: (main: HTMLElement) => renderMemoryEntry(main, memoryId),
} : briefId ? {
  tab: "briefs", title: "briefs", render: (main: HTMLElement) => renderBrief(main, briefId),
} : undefined);

if (app && page) {
  page.render(renderShell(app, page.tab, page.title)).catch((e) => {
    app.append(`Could not load the page: ${e instanceof Error ? e.message : String(e)}`);
  });
}
