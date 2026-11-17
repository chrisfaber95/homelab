import type { components } from "./api/schema";

/** GET a JSON endpoint of the inbox; throws with the `{"error"}` text on a failure. */
export async function getJson<T>(path: string): Promise<T> {
  const r = await fetch(path, { headers: { Accept: "application/json" } });
  if (!r.ok) {
    const body = (await r.json().catch(() => null)) as components["schemas"]["Error"] | null;
    throw new Error(body?.error ?? `${path}: ${r.status}`);
  }
  return (await r.json()) as T;
}

/** The CSRF token for the hidden `csrf` field of the HTML forms. */
export async function csrfToken(): Promise<string> {
  return (await getJson<{ csrf_token: string }>("/api/session")).csrf_token;
}
