/** The SSH page (/ssh): ask for a public key to be added, confirm on the phone. Data: GET /api/ssh, POST /api/ssh. */
import { el, meta } from "../dom";
import { localParts } from "../format";

const pad = (n: number): string => String(n).padStart(2, "0");

/** "dd-mm HH:MM" in the box's timezone, like pages.local; "-" for an empty value. */
export function localTime(iso: string): string {
  if (!iso) return "-";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const l = localParts(d, "Europe/Amsterdam");
  return `${pad(l.d)}-${pad(l.m)} ${pad(l.h)}:${pad(l.min)}`;
}

export interface SshState {
  user: string;
  confirm_minutes: number;
  history: { status: "waiting" | "ok" | "fail"; summary: string; result: string; at: string; by: string }[];
}
export interface SshDone { fingerprint: string; deadline: string; id: string }

const STATUS_LABEL = { waiting: "aanvraag", ok: "ok", fail: "fout" };

export function renderHistory(history: SshState["history"]): HTMLElement[] {
  if (!history.length) return [el("p", { class: "empty" }, "Nog geen sleutels via deze pagina.")];
  return history.map((h) =>
    el("div", { class: "row" },
      el("div", { class: "row-title" }, el("span", { class: `st ${h.status}` }, STATUS_LABEL[h.status]), el("span", {}, h.summary)),
      el("div", { class: "muted wrap" }, h.result),
      meta(localTime(h.at), h.by)));
}

export function renderDone(done: SshDone, name: string, minutes: number): HTMLElement {
  return el("div", { class: "row" },
    el("div", { class: "row-title" }, el("span", { class: "st waiting" }, "bevestig op je telefoon"), el("b", {}, name)),
    el("div", {}, el("code", {}, done.fingerprint)),
    meta(`binnen ${minutes} min, tot ${localTime(done.deadline)}`, done.id));
}

async function csrfToken(): Promise<string> {
  const r = await fetch("/api/session");
  return (await r.json()).csrf_token;
}

export async function renderSsh(main: HTMLElement): Promise<void> {
  let state: SshState;
  try {
    const r = await fetch("/api/ssh");
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    state = await r.json();
  } catch (ex) {
    main.append(el("h1", {}, "SSH"), el("p", { class: "error" }, `Laden mislukt: ${(ex as Error).message}`));
    return;
  }
  const { user, confirm_minutes: minutes } = state;
  const top = el("div");
  const history = el("div", {}, ...renderHistory(state.history));
  const name = el("input", {
    name: "name", id: "name", required: "", maxlength: "40", pattern: "[A-Za-z0-9._@-]+", placeholder: "windows-pc",
  }) as HTMLInputElement;
  const key = el("textarea", { name: "key", id: "key", required: "", spellcheck: "false", placeholder: "ssh-ed25519 AAAA..." }) as HTMLTextAreaElement;
  const form = el("form", {},
    el("label", { for: "name" }, "Naam (bijvoorbeeld het apparaat)"), name,
    el("label", { for: "key" }, "Publieke sleutel (de inhoud van het .pub-bestand)"), key,
    el("button", {}, "Sleutel aanvragen"));

  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const body = new URLSearchParams({ name: name.value, key: key.value });
    let res: Response;
    try {
      res = await fetch("/api/ssh", { method: "POST", body, headers: { "X-CSRF-Token": await csrfToken() } });
    } catch (ex) {
      top.replaceChildren(el("p", { class: "error" }, `mislukt: ${(ex as Error).message}`));
      return;
    }
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      // A pasted private key is never put back on the page.
      if (key.value.includes("PRIVATE KEY")) key.value = "";
      top.replaceChildren(el("p", { class: "error" }, data.error || "mislukt"));
      return;
    }
    top.replaceChildren(renderDone(data, name.value.trim(), minutes));
    key.value = "";
    try {
      const again = await fetch("/api/ssh");
      if (again.ok) history.replaceChildren(...renderHistory((await again.json()).history));
    } catch { /* the request itself went through; the history refreshes on the next visit */ }
  });

  main.append(
    el("h1", {}, "SSH"),
    el("p", { class: "muted" }, "Een publieke sleutel om als ", el("code", {}, user), " in te loggen. Hij komt pas in ",
      el("code", {}, "authorized_keys"), ` als je binnen ${minutes} minuten op `, el("b", {}, "Bevestig"),
      " drukt in de melding op je telefoon. Vergelijk daar de vingerafdruk met ",
      el("code", {}, "ssh-keygen -lf ~/.ssh/id_ed25519.pub"), "."),
    top, form,
    el("p", { class: "note" }, "Nooit de privésleutel (het bestand zonder .pub). Weghalen doe je zelf: ",
      el("code", {}, `~${user}/.ssh/authorized_keys`), '; sleutels van deze pagina hebben "homelab-pagina" in hun commentaar.'),
    el("h2", {}, "Geschiedenis"), history);
}
