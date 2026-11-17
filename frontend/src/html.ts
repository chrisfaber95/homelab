/** Escape text for use in HTML (text and double-quoted attributes). */
export function esc(s: string | number): string {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#x27;");
}

/** A POST form with the CSRF field, like the forms of the Python pages. `inner` and `button` are trusted HTML. */
export function form(csrf: string, action: string, inner: string, button: string, cls?: string): string {
  return (
    `<form method="post" action="${esc(action)}"><input type="hidden" name="csrf" value="${esc(csrf)}">` +
    `${inner}<button${cls ? ` class="${cls}"` : ""}>${button}</button></form>`
  );
}

/** A row-meta line; empty parts are left out. Parts are trusted HTML. */
export function meta(...parts: string[]): string {
  return '<div class="row-meta">' + parts.filter(Boolean).map((p) => `<span>${p}</span>`).join("") + "</div>";
}
