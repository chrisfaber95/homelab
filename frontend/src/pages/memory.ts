// The Memory page ("/memory", Geheugen): what Claude knows, searchable, with the add form; and one entry
// ("/memory/e/<id>") with the edit and delete forms. Data from GET /api/memory and GET /api/memory/e/<id>.
// The forms post to the same URLs as before (/memory/add, /memory/e/<id>/save|delete) with the CSRF token as a field.
import { getJson } from "../api/client";
import type { paths } from "../api/schema";
import { el, meta } from "../dom";
import { submitForm as submit } from "../form";

type MemoryPage = paths["/api/memory"]["get"]["responses"][200]["content"]["application/json; charset=utf-8"];
type Entry = MemoryPage["sections"][number]["entries"][number];
type EntryPage = paths["/api/memory/e/{id}"]["get"]["responses"][200]["content"]["application/json; charset=utf-8"];

const TITLES: Record<string, string> = { voorkeuren: "Voorkeuren", feiten: "Feiten", geschiedenis: "Geschiedenis" };
const empty = (): HTMLElement => el("p", { class: "empty", text: "Niets." });
const safeUrl = (u: string): boolean => /^https?:\/\//i.test(u);

function row(m: Entry): HTMLElement {
  return el("div", { class: "row" },
    el("div", { class: "row-title" }, el("a", { href: `/memory/e/${m.id}`, text: m.title }),
      el("span", { class: "kind", text: m.about }), m.status ? el("span", { class: "st", text: m.status }) : null),
    m.kind === "geschiedenis" ? null : el("div", { text: m.summary }),
    meta(m.changed_local, m.source));
}

function addForm(abouts: string[], error: HTMLElement): HTMLElement {
  const kind = el("select", { name: "kind", id: "mkind" }, el("option", { value: "voorkeuren", text: "voorkeur" }),
    el("option", { value: "feiten", text: "feit" })) as HTMLSelectElement;
  const about = el("select", { name: "about", id: "mabout" }, ...abouts.map((a) => el("option", { value: a, text: a }))) as HTMLSelectElement;
  const name = el("input", {
    name: "name", id: "mname", required: "", placeholder: "naam-met-streepjes", pattern: "[a-z0-9][a-z0-9-]{0,40}",
  }) as HTMLInputElement;
  const text = el("textarea", {
    name: "text", id: "mtext", required: "", placeholder: "Bijvoorbeeld: houd PR's klein, één onderwerp per PR",
  }) as HTMLTextAreaElement;
  const form = el("form", { method: "post", action: "/memory/add" },
    el("label", { for: "mkind", text: "Soort" }), kind, el("label", { for: "mabout", text: "Over" }), about,
    el("label", { for: "mname", text: "Naam" }), name, el("label", { for: "mtext", text: "Tekst" }), text,
    el("button", { text: "Toevoegen" }));
  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    void submit("/memory/add", { kind: kind.value, about: about.value, name: name.value, text: text.value }, error,
      `/memory/e/${kind.value}/${about.value}/${name.value.trim()}`);
  });
  return form;
}

export async function renderMemory(main: HTMLElement): Promise<void> {
  const q = (new URLSearchParams(location.search).get("q") ?? "").slice(0, 200);
  const d = await getJson<MemoryPage>(`/api/memory${q ? `?q=${encodeURIComponent(q)}` : ""}`);
  const error = el("p", { class: "st failed" });
  main.replaceChildren(
    el("h1", { text: "Geheugen" }),
    el("p", {
      class: "muted",
      text: "Wat Claude in de inbox over jou, de box en eerdere taken weet. Elke taak en Claude-job begint met de "
        + "voorkeuren en de feiten; de geschiedenis kan hij doorzoeken. Wat je hier wist of verandert, geldt vanaf "
        + "de volgende taak.",
    }),
    el("form", { method: "get", action: "/memory", class: "searchbar", role: "search" },
      el("input", { type: "search", name: "q", value: q, "aria-label": "Zoeken in het geheugen", placeholder: "Zoeken, bijvoorbeeld: deploy mislukt" }),
      el("button", { text: "Zoeken" })),
    error);
  for (const s of d.sections) {
    main.append(el("h2", {}, `${TITLES[s.kind]} `, el("span", { class: "muted", text: `(${s.count})` })));
    if (s.kind === "geschiedenis") {
      main.append(...(s.entries.length ? s.entries.map(row) : [empty()]));
      if (s.truncated) main.append(el("p", { class: "muted", text: "De nieuwste 30; zoek om de rest te vinden." }));
      continue;
    }
    const abouts = [...new Set(s.entries.map((m) => m.about))]
      .sort((a, b) => Number(a !== "algemeen" && a !== "box") - Number(b !== "algemeen" && b !== "box") || (a < b ? -1 : a > b ? 1 : 0));
    for (const about of abouts) main.append(el("h3", { text: about }), ...s.entries.filter((m) => m.about === about).map(row));
    if (!s.entries.length) main.append(empty());
  }
  main.append(el("h2", { text: "Zelf toevoegen" }), addForm(d.abouts, error));
}

export async function renderMemoryEntry(main: HTMLElement, id: string): Promise<void> {
  const m = await getJson<EntryPage>(`/api/memory/e/${id}`);
  const error = el("p", { class: "st failed" });
  const src = m.link && safeUrl(m.link) ? el("a", { href: m.link, text: m.source || "-" }) : (m.source || "-");
  const rows: [string, Node | string][] = [["Soort", m.kind_label === "voorkeur" ? "Voorkeur" : m.kind_label === "feit" ? "Feit" : "Geschiedenis"],
    ["Over", m.about], ["Toegevoegd", m.added_local], ["Gewijzigd", m.changed_local], ["Bron", src]];
  if (m.status) rows.push(["Status", m.status]);
  if (m.pr) rows.push(["PR", safeUrl(m.pr) ? el("a", { href: m.pr, text: m.pr }) : m.pr]);
  const text = el("textarea", { name: "text", rows: "10" }) as HTMLTextAreaElement;
  text.value = m.body;
  const save = el("form", { method: "post", action: `/memory/e/${m.id}/save` }, text, el("button", { text: "Opslaan" }));
  save.addEventListener("submit", (ev) => {
    ev.preventDefault();
    void submit(`/memory/e/${m.id}/save`, { text: text.value }, error, `/memory/e/${m.id}`);
  });
  const del = el("form", { method: "post", action: `/memory/e/${m.id}/delete` }, el("button", { class: "ghost", text: "Wissen" }));
  del.addEventListener("submit", (ev) => {
    ev.preventDefault();
    void submit(`/memory/e/${m.id}/delete`, {}, error, "/memory");
  });
  main.replaceChildren(
    el("h1", { text: m.name }),
    el("p", { class: "muted" }, el("code", { text: `${m.id}.md` }), ` op de branch ${m.branch} van ${m.data_repo}`),
    error,
    el("dl", {}, ...rows.flatMap(([k, v]) => [el("dt", { text: k }), el("dd", {}, v)])),
    save, del);
}
