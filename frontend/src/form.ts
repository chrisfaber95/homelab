import { csrfToken } from "./api/client";

/**
 * Post a form to its old URL. A 303 means it worked: the page then goes to `next`, or (without `next`) wherever the
 * redirect points. A 4xx carries `{error}`, which is shown in `error`.
 */
export async function submitForm(path: string, fields: Record<string, string>, error: HTMLElement, next?: string): Promise<void> {
  let res: Response;
  try {
    res = await fetch(path, {
      method: "POST", cache: "no-store", redirect: next ? "manual" : "follow",
      body: new URLSearchParams({ csrf: await csrfToken(), ...fields }),
    });
  } catch (ex) {
    error.replaceChildren(`mislukt: ${(ex as Error).message}`);
    return;
  }
  if (next && (res.type === "opaqueredirect" || res.status === 303)) {
    location.assign(next);
    return;
  }
  if (!next && res.redirected) {
    location.assign(res.url);
    return;
  }
  const data = await res.json().catch(() => ({}));
  error.replaceChildren((data as { error?: string }).error || `mislukt (HTTP ${res.status})`);
}
