import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { AuthContext } from "../auth/AuthProvider";
import { grants, type PermissionKey } from "../auth/permissions";
import type { CurrentUser } from "../auth/types";

export const admin: CurrentUser = { id: 1, username: "alice", displayName: "Alice Admin", roles: ["admin"],
  permissions: ["*"] };

type Status = "loading" | "setup" | "signedOut" | "signedIn" | "unavailable";

/** Renders with Mantine, a fresh query cache and a fixed sign-in state. */
export function renderWith(ui: ReactElement, { status = "signedIn", user = admin }: { status?: Status;
  user?: CurrentUser | null } = {}) {
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const signedIn = status === "signedIn" ? user : null;
  return render(
    <MantineProvider>
      <QueryClientProvider client={queries}>
        <AuthContext.Provider
          value={{
            status,
            user: signedIn,
            error: null,
            can: (permission: PermissionKey) => (signedIn ? grants(signedIn.permissions, permission) : false),
            login: async () => {},
            setup: async () => {},
            logout: async () => {},
            refresh: async () => {},
          }}
        >
          {ui}
        </AuthContext.Provider>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

/** Answers fetch calls by path, as the portal would. */
export function mockPortal(routes: Record<string, { status?: number; body?: unknown }>) {
  const calls: { path: string; method: string; body: unknown; headers: Record<string, string> }[] = [];
  globalThis.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    const method = init?.method ?? "GET";
    calls.push({
      path,
      method,
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
      headers: (init?.headers ?? {}) as Record<string, string>,
    });
    const route = routes[`${method} ${path}`] ?? { status: 404, body: { error: "Not found" } };
    const status = route.status ?? 200;
    return new Response(status === 204 ? null : JSON.stringify(route.body ?? {}), { status });
  }) as typeof fetch;
  return calls;
}
