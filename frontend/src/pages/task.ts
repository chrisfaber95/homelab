// The task page ("/task/<id>"): the task, its derived facts, the forms that apply and the live log.
// Data from GET /api/tasks/<id>; the log keeps polling GET /api/log/t<id> (no server-sent events).
import { csrfToken, getJson } from "../api/client";
import type { components } from "../api/schema";
import { el, type Child } from "../dom";
import { LogView } from "../log";
import { postFormEl, reloadWhile, taskRow } from "./tasks";

type TaskDetail = components["schemas"]["TaskDetail"];

const link = (href: string, text: string): HTMLElement => el("a", { href, text });
const muted = (...kids: Child[]): HTMLElement => el("p", { class: "muted" }, ...kids);
const capitalize = (s: string): string => s.charAt(0).toUpperCase() + s.slice(1).toLowerCase();

const answerBox = (label: string): Child[] => [
  el("label", { for: "answer", text: label }), el("textarea", { name: "answer", id: "answer", required: "" })];

export async function renderTask(main: HTMLElement, id: string): Promise<TaskDetail> {
  const [t, csrf] = await Promise.all([getJson<TaskDetail>(`/api/tasks/${id}`), csrfToken()]);
  const tz = t.timezone;
  const post = (action: string, inner: Child[], button: string, cls?: string): HTMLElement =>
    postFormEl(`/task/${t.id}/${action}`, csrf, inner, button, cls);
  const pre = (text: string): HTMLElement => el("pre", { text });
  const parts: Child[] = [el("h1", { text: t.label }), taskRow(t, tz, false)];

  if (t.source) {
    parts.push(muted(`Sent from ${capitalize(t.source)}${t.is_build ? ", approved there (two-step confirm)" : ""}.`));
  }
  parts.push(el("h2", { text: "Taak" }), pre(t.text));
  if (t.question) {
    parts.push(el("h2", { text: "Vraag van Claude" }), pre(t.question));
    if (t.status === "waiting") parts.push(post("answer", answerBox("Jouw antwoord"), "Antwoorden"));
  }
  if (t.answer) {
    parts.push(el("h2", { text: "Antwoord" }), pre(t.answer));
    if (t.status === "answered" && t.actions.answer) {
      parts.push(post("answer", answerBox("Reageren op dit antwoord"), "Reactie sturen"));
    }
  }
  if (t.parent) {
    parts.push(muted("Onderdeel van ", link(`/task/${t.parent}`, `bericht #${t.parent}`), t.reason ? `: ${t.reason}` : "",
      ...(t.after_task ? [". Start pas na ", link(`/task/${t.after_task}`, `#${t.after_task}`)] : []), "."));
  }
  if (t.criteria.length) {
    parts.push(el("h2", { text: "Acceptance criteria" }), el("ul", {}, ...t.criteria.map((c) => el("li", { text: c }))));
  }
  if (t.review) {
    parts.push(el("h2", { text: "Brief review" }), el("p", { text: t.review }),
      t.reviews ? muted(`${t.reviews} automatic rework round(s) used.`) : null);
  }
  if (t.is_build && t.unattended) {
    parts.push(el("h2", { text: "Build without me" }), muted(t.without_me),
      post("attended", [], "Stop: I merge the rest myself", "small quiet"));
  } else if (t.is_build && t.brief) {
    parts.push(muted("You merge the PRs of this build yourself."));
  }
  if (t.build_left_nothing && t.brief) {
    parts.push(muted("Nothing of this build is left: no task of it is still running or merged. A build has no Retry; "
      + "start it again on the brief itself (", link(`/briefs/${t.brief}`, t.brief),
      "), as long as it is still approved. A new build plans the brief as it stands on main then."));
  }
  if (t.is_build && t.handoff) {
    parts.push(el("h2", { text: "Handoff" }),
      el("p", {}, t.handoff.startsWith("http") ? link(t.handoff, t.handoff) : t.handoff));
  }
  if (t.children.length) {
    parts.push(el("h2", { text: t.is_build ? "Planned tasks" : "Doorgestuurd naar" }),
      ...t.children.map((k) => taskRow(k, tz)));
  }
  if (t.summary) parts.push(el("h2", { text: t.summary_title }), pre(t.summary));
  if (t.note) parts.push(muted(t.note));
  parts.push(muted(`${t.turns} beurten in ${t.rounds} ronde(s), ${t.model}. `
    + `Grens per ronde: ${String(t.max_turns)} beurten of ${String(t.max_minutes)} minuten.`));
  if (t.actions.cancel) parts.push(post("cancel", [], "Annuleren", "ghost"));
  if (t.actions.retry) {
    parts.push(post("retry", [el("label", { for: "note", text: t.retry_hint }), el("textarea", { name: "note", id: "note" })],
      t.retry_label));
  } else if (t.blocked_by) {
    parts.push(muted("Waits for ", link(`/task/${t.blocked_by}`, `#${t.blocked_by}`),
      ": continue, retry or mark that task as fine first; this one then comes back in the queue by itself."));
  }
  if (t.accepted) {
    parts.push(muted("You marked this task as fine; its follow-ups may run."));
  } else if (t.actions.accept) {
    parts.push(muted(`${t.accept_pr_unmerged ? `PR #${t.pr_number} is not merged as far as the inbox knows. ` : ""}`
      + "Follow-ups start from main, so work of this task that is not merged will not be in them."),
    post("accept", [], "Mark as fine, continue follow-ups"));
  }
  const log = el("div", { class: "log", role: "log", "data-key": `t${t.id}` });
  parts.push(el("h2", { text: "Log" }), log);
  main.replaceChildren(...parts.filter((p): p is Node | string => Boolean(p)));
  new LogView(`t${t.id}`, log).start();
  reloadWhile(t.refresh);
  return t;
}
