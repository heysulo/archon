/** A refused or failed API call, with the portal's message. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/** Fired on any 401, so the app can return to the sign-in page wherever the call came from. */
export const UNAUTHORIZED_EVENT = "archon:unauthorized";

interface Options {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
}

/**
 * Calls the portal's API. Sends the session cookie and the header the portal requires on every request that
 * changes something, which pages on other sites cannot add.
 */
export async function api<T>(path: string, { method = "GET", body }: Options = {}): Promise<T> {
  const headers: Record<string, string> = { "X-Archon-Request": "1", Accept: "application/json" };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      credentials: "same-origin",
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "Cannot reach the portal");
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  const parsed: unknown = text ? safeJson(text) : undefined;
  if (!response.ok) {
    if (response.status === 401 && path !== "/api/auth/login") {
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
    }
    const message =
      parsed && typeof parsed === "object" && "error" in parsed && typeof parsed.error === "string"
        ? parsed.error
        : `The portal answered ${response.status}`;
    throw new ApiError(response.status, message);
  }
  return parsed as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}
