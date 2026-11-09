import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../../api/http";

/** Whether TLS is on, and the Archon CA instances join with. Nothing here is secret. */
export interface TlsStatus {
  /** This portal's ARCHON_TLS: off, optional or required. */
  mode: "off" | "optional" | "required";
  /** SHA-256 fingerprint of the CA certificate; absent until a registry has created the CA. */
  fingerprint?: string;
  /** The CA certificate as PEM. */
  certificate?: string;
  expiresAt?: string;
}

/** A join token as listed. The token itself is shown only once, when created. */
export interface JoinToken {
  id: number;
  group: string;
  name: string;
  description: string;
  reusable: boolean;
  expiresAt?: string;
  usedAt?: string;
  useCount: number;
  createdBy: string;
  createdAt: string;
}

export interface JoinTokenInput {
  description: string;
  reusable: boolean;
  /** Null for a token that never expires. */
  expiresInHours: number | null;
}

export interface CreatedJoinToken {
  token: string;
  record: JoinToken;
}

const path = (group: string, name: string) =>
  `/api/applications/${encodeURIComponent(group)}/${encodeURIComponent(name)}/tokens`;
const key = (group: string, name: string) => ["joinTokens", group, name] as const;

export function useTlsStatus(enabled = true) {
  return useQuery({ queryKey: ["tls"], queryFn: () => api<TlsStatus>("/api/tls"), enabled });
}

export function useJoinTokens(group: string, name: string, enabled = true) {
  return useQuery({ queryKey: key(group, name), queryFn: () => api<JoinToken[]>(path(group, name)), enabled });
}

export function useJoinTokenMutations(group: string, name: string) {
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: key(group, name) });
  return {
    create: useMutation({
      mutationFn: (input: JoinTokenInput) => api<CreatedJoinToken>(path(group, name), { method: "POST", body: input }),
      onSuccess: refresh,
    }),
    revoke: useMutation({
      mutationFn: (id: number) => api<void>(`${path(group, name)}/${id}`, { method: "DELETE" }),
      onSuccess: refresh,
    }),
  };
}

/** What an instance is started with to join, ready to paste. */
export function joinEnvironment(mode: TlsStatus["mode"], fingerprint: string | undefined, token: string): string {
  return [
    `ARCHON_TLS=${mode === "off" ? "optional" : mode}`,
    `ARCHON_CA_FINGERPRINT=${fingerprint ?? "<no CA yet: start a registry with ARCHON_TLS>"}`,
    `ARCHON_JOIN_TOKEN=${token}`,
  ].join("\n");
}

/** How a token's state reads in the list. */
export function tokenState(token: JoinToken, now: Date = new Date()): { label: string; color: string } {
  if (token.expiresAt && new Date(token.expiresAt) <= now) return { label: "Expired", color: "gray" };
  if (!token.reusable && token.usedAt) return { label: "Used", color: "gray" };
  return { label: "Active", color: "green" };
}
