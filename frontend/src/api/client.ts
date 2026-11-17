// Mutating /api/* calls need the CSRF token as X-CSRF-Token; it comes from GET /api/session (fetched once).
let csrf: Promise<string> | null = null;

export function csrfToken(): Promise<string> {
  csrf ||= getJson<{ csrf_token: string }>("/api/session")
    .then((d) => d.csrf_token)
    .catch((e) => {
      csrf = null;
      throw e;
    });
  return csrf;
}

export async function getJson<T>(path: string): Promise<T> {
  const r = await fetch(path, { cache: "no-store" });
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
  return (await r.json()) as T;
}

export async function postForm(path: string, fields: Record<string, string> = {}): Promise<Response> {
  return fetch(path, {
    method: "POST",
    cache: "no-store",
    headers: { "Content-Type": "application/x-www-form-urlencoded", "X-CSRF-Token": await csrfToken() },
    body: new URLSearchParams(fields),
  });
}
