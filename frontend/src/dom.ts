export type Child = Node | string | null | undefined | false;

/** Small element builder: `class`, `text` and every other key as an attribute. */
export function el(tag: string, attrs: Record<string, string> = {}, ...kids: Child[]): HTMLElement {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") n.className = v;
    else if (k === "text") n.textContent = v;
    else n.setAttribute(k, v);
  }
  for (const k of kids) if (k) n.append(k);
  return n;
}

/** `<div class="row-meta">` with one span per non-empty part. */
export function meta(...parts: Child[]): HTMLElement {
  return el("div", { class: "row-meta" }, ...parts.filter(Boolean).map((p) => el("span", {}, p)));
}
