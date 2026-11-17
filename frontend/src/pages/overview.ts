// The Overview page ("/"): the status sentence, what is running, the job timeline, changes, services, switches, a
// week of usage and health. Data from GET /api/overview; "Nu bezig" is refreshed from GET /api/live.
import { csrfToken, getJson, postForm } from "../api/client";
import type { components } from "../api/schema";
import { el, meta, type Child } from "../dom";
import { clockTime, dayLabel, dayTime, elapsed, minutesNl } from "../format";
import { LogView } from "../log";

type Overview = components["schemas"]["Overview"];
type LiveItem = components["schemas"]["LiveItem"];
interface Live {
  items: LiveItem[];
  queued: number;
  activity: string;
}

const POLL_LIVE = 2500; // ms; a finished run disappears within a few seconds
const CHECK_NL: Record<string, string> = { OK: "in orde", WARN: "let op", FAIL: "faalt" };

const section = (cls: string, head: Child[], ...body: Child[]): HTMLElement =>
  el("section", { class: cls }, el("div", { class: "section-head" }, ...head), ...body);
const note = (...kids: Child[]): HTMLElement => el("p", { class: "note" }, ...kids);
const link = (href: string, text: string): HTMLElement => el("a", { href, text });
const when = (iso: string | null | undefined, tz: string): string => (iso ? dayTime(new Date(iso), tz) : "-");

export async function renderOverview(main: HTMLElement): Promise<void> {
  const [d, csrf] = await Promise.all([getJson<Overview>("/api/overview"), csrfToken()]);
  const tz = d.status.timezone;
  const live = new LiveSection(d.live.items, d.live.queued);

  const hero = el("section", { class: `hero ${d.status.class}${d.live.items.length ? " busy" : ""}` },
    el("div", { class: "state-label", text: `Status om ${clockTime(new Date(d.status.at), tz)}` }),
    el("h1", { text: d.status.sentence }), el("div", { class: "scanner", "aria-hidden": "true" }),
    el("p", { id: "activity", text: d.status.activity }));
  main.replaceChildren(hero,
    el("div", { class: "grid" },
      el("div", {}, live.root,
        section("sec-jobs", [el("h2", { text: "Jobs" }), note("Zeven dagen terug, 24 uur vooruit. ", link("/jobs", "Alle jobs"))],
          timeline(d.timeline)),
        section("sec-changes", [el("h2", { text: "Wijzigingen op de box" }), note("de laatste vijf, via homelab-admin")],
          ...changes(d.changes, tz))),
      el("div", {},
        section("sec-services", [el("h2", { text: "Services" }), note("only over Tailscale")], ...services(d.services)),
        forge(d.forge_link), discord(d.switches, csrf), kitt(d.switches, csrf),
        section("sec-week", [el("h2", { text: "Deze week" }), note("de laatste 7 dagen")], ...usage(d.usage)),
        el("section", { class: "sec-health" }, el("h2", { text: "Gezondheid" }), ...health(d.health, tz)))));
  live.start(hero);
  forgeControls();
}

// "Nu bezig" and its logs.
class LiveSection {
  readonly root: HTMLElement;
  private readonly list = el("div", { class: "live-list" });
  private readonly queued = el("span", { class: "queued" });
  private readonly open = new Map<string, LogView>();

  constructor(items: LiveItem[], queued: number) {
    this.root = el("section", { id: "live", class: "sec-live" },
      el("div", { class: "section-head" }, el("h2", { text: "Nu bezig" }),
        note(this.queued, " ", link("/taken#nieuw", "Taak sturen"))), this.list);
    this.render(items, queued);
  }

  start(hero: HTMLElement): void {
    const poll = async (): Promise<void> => {
      if (!document.hidden) {
        try {
          const data = await getJson<Live>("/api/live");
          this.render(data.items, data.queued);
          const activity = document.getElementById("activity");
          if (activity) activity.textContent = data.activity;
          // The scanner under the status sentence runs faster while something is running.
          hero.classList.toggle("busy", data.items.length > 0);
        } catch { /* no connection for a moment: try again */ }
      }
      setTimeout(() => void poll(), POLL_LIVE);
    };
    setTimeout(() => void poll(), POLL_LIVE);
    setInterval(() => {
      for (const node of this.list.querySelectorAll<HTMLElement>(".live-item")) {
        const e = node.querySelector(".elapsed");
        if (e) e.textContent = elapsed(node.dataset.started ?? "");
      }
    }, 1000);
  }

  private cancelForm(url: string | null): HTMLElement {
    // Not everything can be cancelled from here: forge has its own Start/Stop.
    if (!url) return el("span", { class: "note", text: "eigen dienst: zie Forge hieronder" });
    const form = el("form", { method: "post", action: url }, el("input", { type: "hidden", name: "csrf", value: "" }),
      el("button", { class: "linkbtn danger", text: "Annuleren" }));
    void csrfToken().then((t) => form.querySelector("input")?.setAttribute("value", t));
    return form;
  }

  private row(it: LiveItem): HTMLElement {
    const toggle = el("button", { class: "linkbtn", type: "button", "aria-expanded": "false", text: "Log tonen" });
    const node = el("div", { class: "live-item", "data-key": it.key, "data-started": it.started },
      el("span", { class: "pulse", "aria-hidden": "true" }),
      el("div", {},
        el("div", { class: "row-title" }, link(it.url, it.title), el("span", { text: it.what })),
        meta(it.repo, it.model, el("span", {}, "bezig sinds ", el("span", { class: "elapsed", text: elapsed(it.started) }))),
        el("div", { class: "live-actions" }, toggle, this.cancelForm(it.cancel))));
    toggle.addEventListener("click", () => {
      const shown = this.open.get(it.key);
      if (shown) {
        shown.stop();
        this.open.delete(it.key);
        node.querySelector(".log")?.remove();
        toggle.textContent = "Log tonen";
        toggle.setAttribute("aria-expanded", "false");
      } else {
        const box = el("div", { class: "log", role: "log", "aria-live": "polite" });
        node.append(box);
        const view = new LogView(it.key, box);
        this.open.set(it.key, view);
        view.start();
        toggle.textContent = "Log verbergen";
        toggle.setAttribute("aria-expanded", "true");
      }
    });
    return node;
  }

  render(items: LiveItem[], queued: number): void {
    const keep = new Set(items.map((i) => i.key));
    for (const [key, view] of this.open) if (!keep.has(key)) { view.stop(); this.open.delete(key); }
    const nodes = items.map((it) => {
      const existing = this.list.querySelector<HTMLElement>(`[data-key="${it.key}"]`);
      if (!existing) return this.row(it);
      const e = existing.querySelector(".elapsed");
      if (e) e.textContent = elapsed(it.started);
      // What an agent does changes while it runs (forge: its last log line).
      const what = existing.querySelector(".row-title span");
      if (what) what.textContent = it.what;
      return existing;
    });
    this.list.querySelector(".empty")?.remove();
    // Only move what changed: a node taken out and put back (replaceChildren) loses the scroll position of its log.
    for (const n of [...this.list.children]) if (!nodes.includes(n as HTMLElement)) n.remove();
    nodes.forEach((n, i) => { if (this.list.children[i] !== n) this.list.insertBefore(n, this.list.children[i] ?? null); });
    if (!items.length) {
      this.list.append(el("p", { class: "empty", text: "Niets bezig. Taken en jobs verschijnen hier zodra ze starten." }));
    }
    this.queued.textContent = queued ? `${queued} in de rij` : "";
  }
}

function timeline(t: Overview["timeline"]): HTMLElement {
  if (!t.lanes.length) {
    return el("p", { class: "empty" }, "Nog geen jobs. ", link("/jobs", "Maak een job"),
      " en hij verschijnt hier met zijn runs en zijn volgende keren.");
  }
  const left = (x: number): string => `left:${x.toFixed(2)}%`;
  const days = t.ticks.filter((k) => k.day).map((k) => el("i", { class: "tl-day", style: left(k.x) }));
  const rows = t.lanes.map((lane) => {
    const sub = lane.state === "deleted" ? "verwijderd" : lane.state === "error" ? "fout in het bestand"
      : lane.state === "paused" ? "gepauzeerd" : `${lane.model === "local" ? "lokaal" : "Claude"}, ${lane.when ?? ""}`;
    const marks = lane.marks.map((m) => el("a", {
      class: `mk ${m.status}`, style: left(m.x), href: m.link, title: m.label, "aria-label": m.label }));
    return el("div", { class: "tl-row" },
      el("div", { class: "tl-name" }, link(`/job/${lane.name}`, lane.name), el("small", { text: sub })),
      el("div", { class: "tl-lane" }, ...days.map((n) => n.cloneNode() as HTMLElement), el("i", { class: "tl-now" }), ...marks));
  });
  const legend = [["ok", "gelukt"], ["pr", "PR"], ["failed", "mislukt"], ["held", "wacht of vraag"],
    ["running", "bezig"], ["plan", "gepland"]] as const;
  return el("div", {},
    el("div", { class: "tl" },
      el("div", { class: "tl-scale", "aria-hidden": "true" },
        ...t.ticks.map((k) => el("span", { style: left(k.x), text: k.label })),
        el("span", { class: "now", style: `left:${t.now_x}%`, text: "nu" })), ...rows),
    el("div", { class: "legend" }, ...legend.map(([k, v]) => el("span", {}, el("i", { class: `mk ${k}` }), v))));
}

function changes(list: Overview["changes"], tz: string): HTMLElement[] {
  if (!list.length) return [el("p", { class: "empty", text: "Nog geen wijzigingen." })];
  return list.map((c) => el("div", { class: "row" },
    el("div", { class: "row-title" }, el("span", { class: `st ${c.ok ? "ok" : "fail"}`, text: c.ok ? "ok" : "fout" }),
      el("span", { text: c.text })),
    meta(when(c.at, tz), c.user, c.reason)));
}

function services(list: Overview["services"]): HTMLElement[] {
  const address = (a: string): HTMLElement =>
    /^https?:\/\//.test(a) ? link(a, a.split("://", 2)[1] ?? a) : el("code", { text: a });
  const facts = list.flatMap((s) => [el("dt", { text: s.name }),
    el("dd", {}, address(s.address), el("br"), el("span", { class: "muted", text: s.note }))]);
  const pre = el("pre", { text: "ssh homelab-claude\ntmux new -A -s designer\ncd ~/dev/product && claude" });
  return [facts.length ? el("dl", { class: "facts" }, ...facts) : el("p", { class: "empty", text: "No services in config/services.conf." }),
    el("h3", { text: "Run the designer" }),
    note("From your PC with Tailscale on, as ", el("code", { text: "claude" }), ". tmux keeps the session alive if SSH drops; to get back in, run the first two lines again."),
    pre,
    note("It interviews you and merges the brief as a draft; approve it on the ", link("/briefs", "Briefs"),
      " tab. More in docs/briefs.md.")];
}

// Forge (H-015): state, Start and Stop. Talks to the inbox only (/api/forge*), which calls forge.
function forge(address: string): HTMLElement {
  const page = /^https?:\/\//.test(address) ? [" ", link(address, "Forge page")] : [];
  return el("section", { id: "forge", class: "sec-forge" },
    el("div", { class: "section-head" }, el("h2", { text: "Forge" }), note("autopilot", ...page)),
    el("p", { id: "forge-status", class: "note", role: "status", text: "Checking forge..." }),
    el("div", { class: "forge-actions" },
      el("button", { type: "button", "data-forge": "start", text: "Start" }), " ",
      el("button", { type: "button", class: "ghost", "data-forge": "stop", text: "Stop" })));
}

function forgeControls(): void {
  const status = document.getElementById("forge-status");
  const buttons = document.querySelectorAll<HTMLButtonElement>("#forge button[data-forge]");
  if (!status) return;
  const show = (d: { running: boolean; step?: string; project?: string }): void => {
    status.classList.remove("error");
    status.textContent = d.running ? `Running${d.step ? `: ${d.step}` : ""}${d.project ? ` (${d.project})` : ""}` : "Stopped";
  };
  const fail = (text: string): void => { status.classList.add("error"); status.textContent = text; };
  const handle = async (r: Response): Promise<void> => {
    const d = await r.json() as { error?: string; running: boolean; step?: string; project?: string };
    if (r.ok) show(d); else fail(d.error ?? `HTTP ${r.status}`);
  };
  const refresh = async (): Promise<void> => {
    try { await handle(await fetch("/api/forge", { cache: "no-store" })); } catch { fail("Forge is not reachable."); }
  };
  for (const b of buttons) {
    b.addEventListener("click", async () => {
      buttons.forEach((x) => { x.disabled = true; });
      try { await handle(await postForm(`/api/forge/${b.dataset.forge}`)); } catch { fail("Forge is not reachable."); }
      buttons.forEach((x) => { x.disabled = false; });
    });
  }
  void refresh();
  setInterval(() => void refresh(), 10000);
}

function switchForm(name: string, on: boolean, csrf: string): HTMLElement {
  return el("form", { method: "post", action: `/discord/${name}/${on ? "off" : "on"}` },
    el("input", { type: "hidden", name: "csrf", value: csrf }),
    el("button", on ? { class: "ghost", text: "Switch off" } : { text: "Switch on" }));
}

function discord(s: Overview["switches"], csrf: string): HTMLElement {
  const row = (name: "discord" | "discord_approvals", title: string, what: string): HTMLElement =>
    el("div", { class: "switch-row" },
      el("div", {}, el("strong", { text: title }), " ", el("span", { class: "note", text: what }), el("br"),
        el("span", { id: `switch-${name}`, role: "status", text: s[name] ? "On" : "Off" })),
      switchForm(name, s[name], csrf));
  return el("section", { id: "discord", class: "sec-discord" },
    el("div", { class: "section-head" }, el("h2", { text: "Discord" }), note("chat with the box")),
    row("discord", "Discord", "the bot and its messages"),
    s.discord_on ? null : note("Discord is off: nothing is sent or read; what is written meanwhile arrives when it is switched on."),
    row("discord_approvals", "Approvals from Discord", "build buttons and their confirm"));
}

function kitt(s: Overview["switches"], csrf: string): HTMLElement {
  return el("section", { id: "kitt", class: "sec-discord" },
    el("div", { class: "section-head" }, el("h2", { text: "Kitt" }), note("the cockpit app")),
    el("div", { class: "switch-row" },
      el("div", {}, el("strong", { text: "Kitt" }), " ", el("span", { class: "note", text: "chat, agents, dashboard, logs and approvals in the app" }),
        el("br"), el("span", { id: "switch-kitt", role: "status", text: s.kitt ? "On" : "Off" }), " ",
        el("span", { class: "note", text: `may: ${s.kitt_may.join(", ") || "nothing"}` })),
      switchForm("kitt", s.kitt, csrf)));
}

function bars(values: number[], days: string[], cap?: number): HTMLElement {
  const top = Math.max(...values, cap ?? 0, 1);
  const cols = values.map((v, i) => el("i", {
    ...(i === values.length - 1 ? { class: "today" } : {}), style: `height:${(v / top * 100).toFixed(0)}%`,
    title: `${dayLabel(days[i] ?? "").short}: ${v}` }));
  const capLine = cap ? [el("span", { class: "cap", style: `bottom:${(cap / top * 100).toFixed(0)}%`, title: `daglimiet ${cap}` })] : [];
  return el("div", {},
    el("div", { class: "bars", "aria-hidden": "true" }, ...cols, ...capLine),
    el("div", { class: "days", "aria-hidden": "true" }, ...days.map((d) => el("span", { text: dayLabel(d).weekday }))));
}

function usage(u: Overview["usage"]): HTMLElement[] {
  const sum = (v: number[]): number => v.reduce((a, b) => a + b, 0);
  const last = (v: number[]): number => v[v.length - 1] ?? 0;
  const use = (label: string, value: Child[], ...rest: Child[]): HTMLElement =>
    el("div", { class: "use" }, el("div", {}, el("div", { class: "use-label", text: label }), el("div", { class: "use-value" }, ...value)), ...rest);
  const small = (text: string): HTMLElement => el("small", { text });
  const rows = [
    u.llm === null
      ? use("Lokaal model", [small("de helper is niet bereikbaar")])
      : use("Lokaal model", [String(sum(u.llm)), small(`calls, vandaag ${last(u.llm)}`)], bars(u.llm, u.days)),
    use("Claude voor jobs", [String(sum(u.claude_jobs)), small(`runs, vandaag ${last(u.claude_jobs)} van ${u.cap}`)],
      bars(u.claude_jobs, u.days, u.cap)),
    use("Claude voor jouw taken", [String(sum(u.inbox)), small(`taken, vandaag ${last(u.inbox)}`)], bars(u.inbox, u.days)),
  ];
  const kinds = Object.entries(u.saved_minutes).sort((a, b) => b[1] - a[1]);
  rows.push(use("Tijd bespaard door jobs", [minutesNl(kinds.reduce((a, [, v]) => a + v, 0))],
    kinds.length
      ? el("ul", { class: "saved" }, ...kinds.map(([k, v]) => el("li", {}, el("span", { text: k }), el("b", { text: minutesNl(v) }))))
      : el("p", { class: "saved" }, "Telt gelukte runs van jobs met ", el("code", { text: "minutes" }), " in hun bestand.")));
  return rows;
}

function health(h: Overview["health"], tz: string): HTMLElement[] {
  const checks = Object.entries(h.checks).sort(([an, a], [bn, b]) =>
    Number(a.status === "OK") - Number(b.status === "OK") || (an < bn ? -1 : an > bn ? 1 : 0));
  const bad = checks.filter(([, c]) => c.status !== "OK");
  const checkRow = ([name, c]: (typeof checks)[number]): HTMLElement =>
    el("div", { class: "check" },
      el("span", { class: `st ${c.status === "OK" ? "ok" : c.status.toLowerCase()}`, text: CHECK_NL[c.status] ?? c.status }),
      el("div", {}, el("b", { text: name }), meta(c.text, c.since ? `sinds ${when(c.since, tz)}` : "")));
  const out: HTMLElement[] = [note(`${checks.length - bad.length} van ${checks.length} checks in orde, laatst gecontroleerd ${h.last ? when(h.last, tz) : "nooit"}. Alleen lezen.`)];
  out.push(...bad.map(checkRow));
  if (checks.length) out.push(el("details", {}, el("summary", { text: "Alle checks" }), ...checks.map(checkRow)));
  const facts: [string, Child[]][] = h.deploys.map((d) => [d.name, [
    el("span", { class: `st ${d.ok ? "ok" : "fail"}`, text: d.ok === null ? "nog niet" : d.ok ? "ok" : "mislukt" }),
    `, ${(d.sha ?? "-").slice(0, 7)}, ${when(d.at, tz)}`,
    ...(d.sync_error ? [el("br"), el("span", { class: "error", text: d.sync_error })] : [])]]);
  const b = h.backup;
  facts.push(["Backup", b ? [el("span", { class: `st ${b.ok ? "ok" : "fail"}`, text: b.ok ? "ok" : "mislukt" }),
    `, ${when(b.at, tz)}; ${Math.floor(b.free_bytes / 2 ** 30)} GB vrij (${String(b.free_pct ?? "?")}%)`] : ["nog geen"]]);
  out.push(el("h3", { text: "Deploys en backup" }),
    el("dl", { class: "facts" }, ...facts.flatMap(([k, v]) => [el("dt", { text: k }), el("dd", {}, ...v)])));
  return out;
}
