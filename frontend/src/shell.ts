import { el } from "./dom";

// Same tabs as TABS in pages.py: [key, href, name].
const TABS = [
  ["overzicht", "/", "Overzicht"], ["taken", "/taken", "Taken"], ["briefs", "/briefs", "Briefs"],
  ["jobs", "/jobs", "Jobs"], ["search", "/search", "Search"], ["geheugen", "/memory", "Geheugen"],
  ["skills", "/skills", "Skills"], ["ssh", "/ssh", "SSH"],
] as const;

/** The header with the tabs and the empty `<main>` of a page; returns the main element to fill. */
export function renderShell(app: HTMLElement, current: string, title: string): HTMLElement {
  document.title = title;
  const tabs = el("nav", { class: "tabs", "aria-label": "Onderdelen" },
    ...TABS.map(([key, href, name]) => {
      const a = el("a", { href, text: name });
      if (key === current) a.setAttribute("aria-current", "page");
      return a;
    }));
  const main = el("main", { class: "narrow" });
  app.replaceChildren(el("header", { class: "top" }, el("a", { class: "mark", href: "/", text: "homelab" }), tabs), main);
  return main;
}
