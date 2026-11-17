import { renderBrief, renderBriefs } from "./pages/briefs";
import { renderOverview } from "./pages/overview";
import { renderShell } from "./shell";

const app = document.getElementById("app");
const fail = (e: unknown): void => {
  app?.append(`Could not load the page: ${e instanceof Error ? e.message : String(e)}`);
};
const brief = /^\/briefs\/([A-Z]-\d{3,4})$/.exec(location.pathname);
if (app && location.pathname === "/") {
  renderOverview(renderShell(app, "overzicht", "homelab")).catch(fail);
} else if (app && location.pathname === "/briefs") {
  renderBriefs(renderShell(app, "briefs", "briefs")).catch(fail);
} else if (app && brief?.[1]) {
  renderBrief(renderShell(app, "briefs", "briefs"), brief[1]).catch(fail);
}
