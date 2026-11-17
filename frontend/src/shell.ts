/** The header and tab bar every inbox page has (same markup as pages.page). */
const TABS: [string, string][] = [
  ["/", "Overzicht"], ["/taken", "Taken"], ["/briefs", "Briefs"], ["/jobs", "Jobs"],
  ["/search", "Search"], ["/memory", "Geheugen"], ["/skills", "Skills"], ["/ssh", "SSH"],
];

export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K, attrs: Record<string, string> = {}, ...kids: (Node | string)[]
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  node.append(...kids);
  return node;
}

/** Fills #app with the header and an empty <main class="narrow">, and returns the main element. */
export function mountShell(root: HTMLElement, currentPath: string, title: string): HTMLElement {
  document.title = title;
  const tabs = TABS.map(([href, name]) => {
    const a = el("a", { href }, name);
    if (href === currentPath) a.setAttribute("aria-current", "page");
    return a;
  });
  const nav = el("nav", { class: "tabs", "aria-label": "Onderdelen" }, ...tabs);
  const main = el("main", { class: "narrow" });
  root.replaceChildren(el("header", { class: "top" }, el("a", { class: "mark", href: "/" }, "homelab"), nav), main);
  return main;
}

/** "dd-mm HH:MM" in the box's timezone, like pages.local; "-" for an empty value. */
export function localTime(iso: string): string {
  if (!iso) return "-";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = Object.fromEntries(
    new Intl.DateTimeFormat("nl-NL", {
      timeZone: "Europe/Amsterdam", day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false,
    }).formatToParts(d).map((x) => [x.type, x.value]),
  );
  return `${p.day}-${p.month} ${p.hour}:${p.minute}`;
}

export function meta(...parts: string[]): HTMLElement {
  return el("div", { class: "row-meta" }, ...parts.filter(Boolean).map((p) => el("span", {}, p)));
}
