import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../../api/http";

/** A user as GET /api/users returns it. The portal leaves out lastLoginAt for users who never signed in. */
export interface User {
  id: number;
  username: string;
  displayName: string;
  enabled: boolean;
  roles: string[];
  createdAt: string;
  lastLoginAt?: string;
}

export interface Role {
  id: string;
  description: string;
  /** Permission keys; the built-in admin role holds `*`, every permission. */
  permissions: string[];
  /** The admin role: it holds every permission and cannot be changed or deleted. */
  builtIn: boolean;
  /** Usernames holding the role. */
  users: string[];
}

export interface PermissionInfo {
  key: string;
  description: string;
}

export interface RolesResponse {
  roles: Role[];
  permissions: PermissionInfo[];
}

export interface NewUser {
  username: string;
  displayName: string;
  password: string;
  roles: string[];
}

export interface UserChanges {
  displayName: string;
  enabled: boolean;
  roles: string[];
}

const USERS = ["users"] as const;

export function useUsers() {
  return useQuery({ queryKey: USERS, queryFn: () => api<User[]>("/api/users") });
}

export const ROLES = ["roles"] as const;

/** @param enabled false skips the request, for those who may not see roles */
export function useRoles(enabled = true) {
  return useQuery({ queryKey: ROLES, queryFn: () => api<RolesResponse>("/api/roles"), enabled });
}

/** Every change refreshes the list afterwards, so it always shows what the portal stored. */
export function useUserMutations() {
  const client = useQueryClient();
  // Roles list who holds them, so they change with users too
  const refresh = () =>
    Promise.all([client.invalidateQueries({ queryKey: USERS }), client.invalidateQueries({ queryKey: ROLES })]);
  return {
    create: useMutation({
      mutationFn: (user: NewUser) => api<User>("/api/users", { method: "POST", body: user }),
      onSuccess: refresh,
    }),
    update: useMutation({
      mutationFn: ({ id, changes }: { id: number; changes: UserChanges }) =>
        api<User>(`/api/users/${id}`, { method: "PUT", body: changes }),
      onSuccess: refresh,
    }),
    resetPassword: useMutation({
      mutationFn: ({ id, password }: { id: number; password: string }) =>
        api<void>(`/api/users/${id}/password`, { method: "POST", body: { password } }),
    }),
    remove: useMutation({
      mutationFn: (id: number) => api<void>(`/api/users/${id}`, { method: "DELETE" }),
      onSuccess: refresh,
    }),
  };
}
