import { renderOverview } from "./pages/overview";
import { renderSsh } from "./pages/ssh";
import { renderShell } from "./shell";

const app = document.getElementById("app");
const pages: Record<string, { current: string; title: string; render: (main: HTMLElement) => Promise<void> }> = {
  "/": { current: "overzicht", title: "homelab", render: renderOverview },
  "/ssh": { current: "ssh", title: "ssh", render: renderSsh },
};
const page = pages[location.pathname];
if (app && page) {
  page.render(renderShell(app, page.current, page.title)).catch((e) => {
    app.append(`Could not load the page: ${e instanceof Error ? e.message : String(e)}`);
  });
}
