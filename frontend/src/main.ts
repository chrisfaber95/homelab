import { renderBrief, renderBriefs } from "./pages/briefs";
import { renderJobs } from "./pages/jobs";
import { renderOverview } from "./pages/overview";
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
  "/ssh": { tab: "ssh", title: "ssh", render: renderSsh },
};
const taskId = /^\/task\/(\d+)$/.exec(location.pathname)?.[1];
const briefId = /^\/briefs\/([A-Z]-\d{3,4})$/.exec(location.pathname)?.[1];
const page = PAGES[location.pathname] ?? (taskId ? {
  tab: "taken", title: `inbox #${taskId}`, render: async (main: HTMLElement) => { await renderTask(main, taskId); },
} : briefId ? {
  tab: "briefs", title: "briefs", render: (main: HTMLElement) => renderBrief(main, briefId),
} : undefined);

if (app && page) {
  page.render(renderShell(app, page.tab, page.title)).catch((e) => {
    app.append(`Could not load the page: ${e instanceof Error ? e.message : String(e)}`);
  });
}
