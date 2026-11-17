import { renderJobs } from "./pages/jobs";
import { renderOverview } from "./pages/overview";
import { renderSsh } from "./pages/ssh";
import { renderShell } from "./shell";

const app = document.getElementById("app");
const PAGES: Record<string, { tab: string; title: string; render: (main: HTMLElement) => Promise<void> }> = {
  "/": { tab: "overzicht", title: "homelab", render: renderOverview },
  "/jobs": { tab: "jobs", title: "jobs", render: renderJobs },
  "/ssh": { tab: "ssh", title: "ssh", render: renderSsh },
};
const page = PAGES[location.pathname];
if (app && page) {
  page.render(renderShell(app, page.tab, page.title)).catch((e) => {
    app.append(`Could not load the page: ${e instanceof Error ? e.message : String(e)}`);
  });
}
