// The Briefs pages ("/briefs" and "/briefs/<id>"): drafts to approve, approved briefs, the builds so far, and one
// brief with its text. Data from GET /api/briefs and GET /api/briefs/<id>; Build and Park POST to /briefs/<id>/<action>.
import { csrfToken, getJson, postForm } from "../api/client";
import type { components } from "../api/schema";
import { el, meta, type Child } from "../dom";
import { shortDate } from "../format";

type Brief = components["schemas"]["BriefView"];
type Build = components["schemas"]["BuildView"];
type BriefsPage = components["schemas"]["BriefsPage"];

const REFRESH = 20000; // ms; the page reloaded itself every 20 s while a build was active

const empty = (text: string): HTMLElement => el("p", { class: "empty", text });
const muted = (...kids: Child[]): HTMLElement => el("p", { class: "muted" }, ...kids);

/** A form button as the Python page had it: POST with the CSRF field; a redirect is followed, a 400 shows its reason. */
function action(brief: string, name: "build" | "park", label: string, cls: string, show: (e: string) => void,
  fields: Record<string, string> = {}): HTMLElement {
  const form = el("form", { method: "post", action: `/briefs/${brief}/${name}` },
    el("input", { type: "hidden", name: "csrf", value: "" }),
    ...Object.entries(fields).map(([k, v]) => el("input", { type: "hidden", name: k, value: v })),
    el("button", cls ? { class: cls, text: label } : { text: label }));
  void csrfToken().then((t) => form.querySelector<HTMLInputElement>("input[name=csrf]")?.setAttribute("value", t));
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const buttons = form.querySelectorAll("button");
    buttons.forEach((b) => { b.disabled = true; });
    try {
      const r = await postForm(`/briefs/${brief}/${name}`, { csrf: await csrfToken(), ...fields }); // a page POST takes the token as a field
      if (r.redirected) { location.href = r.url; return; }
      const d = await r.json().catch(() => ({})) as { error?: string };
      show(d.error ?? `HTTP ${r.status}`);
    } catch (e) {
      show(e instanceof Error ? e.message : String(e));
    }
    buttons.forEach((b) => { b.disabled = false; });
  });
  return form;
}

function actions(b: Brief, show: (e: string) => void): HTMLElement {
  if (b.build) return el("a", { href: b.build.url, text: `build #${b.build.id}` });
  const draft = b.status === "draft";
  const buttons: HTMLElement[] = [];
  if (b.actions.includes("build")) buttons.push(action(b.id, "build", draft ? "Approve & build" : "Build", "small", show));
  if (b.actions.includes("build-without-me")) {
    buttons.push(action(b.id, "build", draft ? "Approve & build without me" : "Build without me", "small quiet", show,
      { mode: "without-me" }));
  }
  if (b.actions.includes("park")) buttons.push(action(b.id, "park", "Park", "small quiet", show));
  return el("div", { class: "actions" }, ...buttons);
}

function row(b: Brief, show: (e: string) => void, withActions = true): HTMLElement {
  return el("div", { class: "row" },
    el("div", { class: "row-title" }, el("a", { href: `/briefs/${b.id}`, text: b.id }),
      el("span", { class: `st ${b.status === "approved" ? "ok" : "warn"}`, text: b.status || "no status" }),
      el("span", { text: b.title })),
    meta(b.project, b.priority, `repos: ${b.repos.join(", ") || "-"}`),
    withActions ? actions(b, show) : null);
}

function buildRow(t: Build, tz: string): HTMLElement {
  return el("div", { class: "row" },
    el("div", { class: "row-title" }, el("a", { href: t.url, text: t.label }),
      el("span", { class: `st ${t.status}`, text: t.status_label }), el("span", { text: t.what })),
    meta(t.repo, t.source ? `via ${t.source.charAt(0).toUpperCase()}${t.source.slice(1).toLowerCase()}` : "",
      `aangemaakt ${shortDate(new Date(t.created), tz)}`, t.ended ? `klaar ${shortDate(new Date(t.ended), tz)}` : "",
      t.pr_url ? el("a", { href: t.pr_url, text: `PR #${t.pr_number}` }) : ""));
}

/** An error box that the Build and Park buttons fill in; it sits under the heading. */
function errorBox(initial: string | null): { node: HTMLElement; show: (e: string) => void } {
  const node = el("p", { class: "error", role: "alert" });
  const show = (e: string): void => { node.textContent = e; node.hidden = !e; };
  show(initial ?? "");
  return { node, show };
}

export async function renderBriefs(main: HTMLElement): Promise<void> {
  const draw = async (): Promise<boolean> => {
    const d = await getJson<BriefsPage>("/api/briefs");
    const err = errorBox(d.error);
    const list = (items: Brief[], none: string, withActions = true): HTMLElement[] =>
      items.length ? items.map((b) => row(b, err.show, withActions)) : [empty(none)];
    main.replaceChildren(
      el("h1", { text: "Briefs" }), err.node,
      muted("Briefs on main of the product repo. Read a draft, then approve it here: Approve & build commits ",
        el("code", { text: "status: approved" }), " and starts the planner, which splits the brief into inbox tasks; each PR is checked against the brief. Design new briefs with ",
        el("code", { text: "cd ~/dev/product && claude" }), " on the box."),
      muted(`Build without me: ${d.without_me}`),
      el("h2", { text: "To approve" }), ...list(d.drafts, "No drafts."),
      el("h2", { text: "Approved" }), ...list(d.approved, "No approved briefs."),
      ...(d.needs_design.length ? [el("h2", { text: "Needs design" }), ...list(d.needs_design, "", false)] : []),
      el("h2", { text: "Builds" }),
      ...(d.builds.length ? d.builds.map((t) => buildRow(t, d.timezone)) : [empty("No builds yet.")]),
      muted(`In the repo: ${d.counts.map((c) => `${c.count} ${c.status || "no status"}`).join(", ")}.`));
    return d.refresh;
  };
  document.title = "briefs";
  let again = await draw();
  const tick = (): void => {
    setTimeout(async () => {
      if (again && !document.hidden) {
        try { again = await draw(); } catch { /* no connection for a moment: try again */ }
      }
      if (again) tick();
    }, REFRESH);
  };
  if (again) tick();
}

export async function renderBrief(main: HTMLElement, id: string): Promise<void> {
  const d = await getJson<{ error: string | null; brief: Brief }>(`/api/briefs/${id}`);
  const b = d.brief;
  const err = errorBox(d.error);
  document.title = `briefs ${b.id}`;
  main.replaceChildren(
    el("h1", { text: `${b.id} ${b.title}` }), err.node, row(b, err.show, false),
    muted(`${b.path} on main of the product repo.`),
    el("pre", { text: b.text ?? "" }),
    ...(b.status === "draft" || b.status === "approved" ? [actions(b, err.show)] : []));
}
