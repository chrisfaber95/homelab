import { csrfToken, getJson } from "./api";
import { esc } from "./html";
import { jobsHtml, type JobsPage } from "./jobs";
import { shellHtml } from "./shell";

const REFRESH_MS = 20000; // the Python page reloaded itself every 20 s while a draft was being drafted

function show(app: HTMLElement, title: string, current: string, body: string): void {
  document.title = title;
  app.innerHTML = shellHtml(current, body);
}

async function jobsPage(app: HTMLElement): Promise<void> {
  const [data, csrf] = await Promise.all([getJson<JobsPage>("/api/jobs-page"), csrfToken()]);
  show(app, "jobs", "jobs", jobsHtml(data, csrf));
  if (data.refresh) setTimeout(() => location.reload(), REFRESH_MS);
}

const app = document.getElementById("app");
if (app) {
  const path = location.pathname;
  const page = path === "/jobs" ? jobsPage(app) : Promise.reject(new Error(`no ported page at ${path}`));
  page.catch((e: unknown) => {
    show(app, "homelab", "", `<h1>Fout</h1><p class="st failed">${esc(e instanceof Error ? e.message : String(e))}</p>`);
  });
}
