// The Tasks page ("/taken"): the new-task form, the queue and the history. Data from GET /api/tasks.
import { csrfToken, getJson } from "../api/client";
import type { components } from "../api/schema";
import { el, meta, type Child } from "../dom";
import { shortDate } from "../format";

type TaskList = components["schemas"]["TaskList"];
type TaskRow = components["schemas"]["TaskRow"];

const RELOAD = 20_000; // ms; the page reloads itself while something is queued or running

const capitalize = (s: string): string => s.charAt(0).toUpperCase() + s.slice(1).toLowerCase();
const stamp = (iso: string, tz: string): string => shortDate(new Date(iso), tz);

/** One row of a task list; `link` false on the task page itself (the title is then bold, not a link). */
export function taskRow(t: TaskRow, tz: string, link = true): HTMLElement {
  const head = link ? el("a", { href: `/task/${t.id}`, text: t.label }) : el("b", { text: t.label });
  return el("div", { class: "row" },
    el("div", { class: "row-title" }, head, el("span", { class: `st ${t.status}`, text: t.status_text }),
      el("span", { text: t.what })),
    meta(t.repo, t.source ? `via ${capitalize(t.source)}` : "", `aangemaakt ${stamp(t.created, tz)}`,
      t.ended ? `klaar ${stamp(t.ended, tz)}` : "", t.pr_url ? el("a", { href: t.pr_url, text: `PR #${t.pr_number}` }) : ""));
}

/** A POST form with the CSRF field; the server answers with a redirect, as it always did. */
export function postFormEl(action: string, csrf: string, inner: Child[], button: string, cls?: string): HTMLElement {
  return el("form", { method: "post", action },
    el("input", { type: "hidden", name: "csrf", value: csrf }), ...inner,
    el("button", cls ? { class: cls, text: button } : { text: button }));
}

export function reloadWhile(refresh: boolean): void {
  if (refresh) setTimeout(() => location.reload(), RELOAD);
}

export async function renderTasks(main: HTMLElement): Promise<void> {
  const [d, csrf] = await Promise.all([getJson<TaskList>("/api/tasks"), csrfToken()]);
  const rows = (list: TaskRow[], empty: string): HTMLElement[] =>
    list.length ? list.map((t) => taskRow(t, d.timezone)) : [el("p", { class: "empty", text: empty })];
  const form = postFormEl("/tasks", csrf, [
    el("label", { for: "repo", text: "Repo" }),
    el("select", { name: "repo", id: "repo" },
      el("option", { value: d.auto, text: "auto (homelab beslist)" }),
      ...d.repos.map((r) => el("option", { value: r, text: r }))),
    el("label", { for: "text", text: "Wat moet Claude doen of uitzoeken?" }),
    el("textarea", { name: "text", id: "text", required: "" }),
  ], "Taak sturen");
  form.id = "nieuw";
  main.replaceChildren(el("h1", { text: "Taken" }), form,
    el("h2", { text: "Nu" }), ...rows(d.queue, "Niets in de rij."),
    el("h2", { text: "Geschiedenis" }), ...rows(d.history, "Nog geen taken."));
  reloadWhile(d.refresh);
}
