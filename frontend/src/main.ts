import { renderOverview } from "./pages/overview";
import { renderTask } from "./pages/task";
import { renderTasks } from "./pages/tasks";
import { renderShell } from "./shell";

const app = document.getElementById("app");
const path = location.pathname;
const taskId = /^\/task\/(\d+)$/.exec(path)?.[1];

function failed(e: unknown): void {
  app?.append(`Could not load the page: ${e instanceof Error ? e.message : String(e)}`);
}

if (app && path === "/") {
  renderOverview(renderShell(app, "overzicht", "homelab")).catch(failed);
} else if (app && path === "/taken") {
  renderTasks(renderShell(app, "taken", "taken")).catch(failed);
} else if (app && taskId) {
  renderTask(renderShell(app, "taken", `inbox #${taskId}`), taskId).catch(failed);
}
