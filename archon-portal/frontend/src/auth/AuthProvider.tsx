import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, UNAUTHORIZED_EVENT } from "../api/http";
import { grants, type PermissionKey } from "./permissions";
import type { AuthStateResponse, CurrentUser } from "./types";

export type AuthStatus = "loading" | "setup" | "signedOut" | "signedIn" | "unavailable";

interface AuthContextValue {
  status: AuthStatus;
  user: CurrentUser | null;
  /** Why the portal could not be asked, when status is "unavailable". */
  error: string | null;
  can: (permission: PermissionKey) => boolean;
  login: (username: string, password: string) => Promise<void>;
  setup: (token: string, username: string, displayName: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

function statusOf(state: AuthStateResponse): AuthStatus {
  if (state.user) return "signedIn";
  return state.setupRequired ? "setup" : "signedOut";
}

/** Knows who is signed in, from the portal's session cookie, and signs in and out. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>("loading");
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [error, setError] = useState<string | null>(null);

  const apply = useCallback((state: AuthStateResponse) => {
    setUser(state.user ?? null);
    setStatus(statusOf(state));
    setError(null);
  }, []);

  const refresh = useCallback(async () => {
    try {
      apply(await api<AuthStateResponse>("/api/auth/state"));
    } catch (failure) {
      setStatus("unavailable");
      setError(failure instanceof Error ? failure.message : "Cannot reach the portal");
    }
  }, [apply]);

  useEffect(() => {
    let active = true;
    api<AuthStateResponse>("/api/auth/state")
      .then((state) => active && apply(state))
      .catch((failure: unknown) => {
        if (!active) return;
        setStatus("unavailable");
        setError(failure instanceof Error ? failure.message : "Cannot reach the portal");
      });
    // Any 401 means the session ended: it expired, the user was disabled, or signed out elsewhere
    const signedOut = () => {
      setUser(null);
      void refresh();
    };
    window.addEventListener(UNAUTHORIZED_EVENT, signedOut);
    return () => {
      active = false;
      window.removeEventListener(UNAUTHORIZED_EVENT, signedOut);
    };
  }, [apply, refresh]);

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      error,
      can: (permission) => (user ? grants(user.permissions, permission) : false),
      login: async (username, password) =>
        apply(await api<AuthStateResponse>("/api/auth/login", { method: "POST", body: { username, password } })),
      setup: async (token, username, displayName, password) =>
        apply(
          await api<AuthStateResponse>("/api/auth/setup", {
            method: "POST",
            body: { token, username, displayName, password },
          }),
        ),
      logout: async () => {
        await api("/api/auth/logout", { method: "POST", body: {} });
        setUser(null);
        setStatus("signedOut");
      },
      refresh,
    }),
    [status, user, error, apply, refresh],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return value;
}
