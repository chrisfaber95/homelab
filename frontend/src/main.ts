import { renderOverview } from "./pages/overview";
import { renderShell } from "./shell";

const app = document.getElementById("app");
if (app && location.pathname === "/") {
  renderOverview(renderShell(app, "overzicht", "homelab")).catch((e) => {
    app.append(`Could not load the page: ${e instanceof Error ? e.message : String(e)}`);
  });
}
