import { renderBrief, renderBriefs } from "./pages/briefs";
import { renderJobs } from "./pages/jobs";
import { renderOverview } from "./pages/overview";
import { renderShell } from "./shell";

const app = document.getElementById("app");
const PAGES: Record<string, { tab: string; title: string; render: (main: HTMLElement) => Promise<void> }> = {
  "/": { tab: "overzicht", title: "homelab", render: renderOverview },
  "/jobs": { tab: "jobs", title: "jobs", render: renderJobs },
  "/briefs": { tab: "briefs", title: "briefs", render: renderBriefs },
};
const brief = /^\/briefs\/([A-Z]-\d{3,4})$/.exec(location.pathname);
const page = PAGES[location.pathname] ?? (brief?.[1]
  ? { tab: "briefs", title: "briefs", render: (main: HTMLElement) => renderBrief(main, brief[1] ?? "") } : undefined);
if (app && page) {
  page.render(renderShell(app, page.tab, page.title)).catch((e) => {
    app.append(`Could not load the page: ${e instanceof Error ? e.message : String(e)}`);
  });
}
