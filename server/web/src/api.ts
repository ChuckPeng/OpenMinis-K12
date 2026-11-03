let csrf = "";

export function getCsrf(): string {
  return csrf;
}

export async function api<T = unknown>(
  path: string,
  opts: RequestInit = {},
): Promise<{ ok: boolean; status: number; body: T | null }> {
  const headers = new Headers(opts.headers || {});
  if (!headers.has("Content-Type") && opts.body) {
    headers.set("Content-Type", "application/json");
  }
  if (opts.method && opts.method !== "GET" && csrf) {
    headers.set("X-CSRF-Token", csrf);
  }
  const res = await fetch(path, { ...opts, headers, credentials: "same-origin" });
  const newCsrf = res.headers.get("X-CSRF-Token");
  if (newCsrf) csrf = newCsrf;
  const ct = res.headers.get("content-type") || "";
  let body: T | null = null;
  if (ct.includes("application/json")) {
    body = (await res.json()) as T;
  }
  if (body && typeof body === "object" && body !== null && "csrf_token" in body) {
    const t = (body as { csrf_token?: string }).csrf_token;
    if (t) csrf = t;
  }
  return { ok: res.ok, status: res.status, body };
}
