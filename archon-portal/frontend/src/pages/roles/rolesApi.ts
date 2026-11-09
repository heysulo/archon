import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "../../api/http";
import { ROLES, type Role } from "../users/usersApi";

export interface RoleInput {
  id: string;
  description: string;
  permissions: string[];
}

/**
 * Every change refreshes the roles afterwards. Signed-in users holding a changed role get its new permissions on
 * their next request, this user included, so the current user is refreshed too.
 */
export function useRoleMutations(refreshMe: () => Promise<void>) {
  const client = useQueryClient();
  const refresh = async () => {
    await client.invalidateQueries({ queryKey: ROLES });
    await refreshMe();
  };
  return {
    create: useMutation({
      mutationFn: (role: RoleInput) => api<Role>("/api/roles", { method: "POST", body: role }),
      onSuccess: refresh,
    }),
    update: useMutation({
      mutationFn: (role: RoleInput) =>
        api<Role>(`/api/roles/${encodeURIComponent(role.id)}`, { method: "PUT", body: role }),
      onSuccess: refresh,
    }),
    remove: useMutation({
      mutationFn: (id: string) => api<void>(`/api/roles/${encodeURIComponent(id)}`, { method: "DELETE" }),
      onSuccess: refresh,
    }),
  };
}
