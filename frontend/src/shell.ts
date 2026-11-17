import { esc } from "./html";

/** The tabs of the homelab page, same order and labels as TABS in pages.py. */
export const TABS: ReadonlyArray<readonly [string, string, string]> = [
  ["overzicht", "/", "Overzicht"],
  ["taken", "/taken", "Taken"],
  ["briefs", "/briefs", "Briefs"],
  ["jobs", "/jobs", "Jobs"],
  ["search", "/search", "Search"],
  ["geheugen", "/memory", "Geheugen"],
  ["skills", "/skills", "Skills"],
  ["ssh", "/ssh", "SSH"],
];

/** The header with the tabs and the main element around a page body, as the Python `page()` rendered it. */
export function shellHtml(current: string, body: string): string {
  const tabs = TABS.map(
    ([key, href, name]) => `<a href="${href}"${key === current ? " aria-current=page" : ""}>${esc(name)}</a>`,
  ).join("");
  return (
    `<header class="top"><a class="mark" href="/">homelab</a><nav class="tabs" aria-label="Onderdelen">${tabs}</nav>` +
    `</header><main class="narrow">${body}</main>`
  );
}
