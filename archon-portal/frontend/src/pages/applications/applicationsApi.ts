import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../../api/http";

/** An application definition: instances registering with this group and name are admitted. */
export interface ApplicationDefinition {
  group: string;
  name: string;
  description: string;
  /** One of Archon's own components: always defined, not changeable. */
  builtIn: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export type ApplicationInput = Pick<ApplicationDefinition, "group" | "name" | "description">;

const APPLICATIONS = ["applications"] as const;

export function useApplications() {
  return useQuery({ queryKey: APPLICATIONS, queryFn: () => api<ApplicationDefinition[]>("/api/applications") });
}

const path = (group: string, name: string) =>
  `/api/applications/${encodeURIComponent(group)}/${encodeURIComponent(name)}`;

export function useApplicationMutations() {
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: APPLICATIONS });
  return {
    create: useMutation({
      mutationFn: (input: ApplicationInput) =>
        api<ApplicationDefinition>("/api/applications", { method: "POST", body: input }),
      onSuccess: refresh,
    }),
    update: useMutation({
      mutationFn: (input: ApplicationInput) =>
        api<ApplicationDefinition>(path(input.group, input.name), {
          method: "PUT",
          body: { description: input.description },
        }),
      onSuccess: refresh,
    }),
    remove: useMutation({
      mutationFn: ({ group, name }: { group: string; name: string }) =>
        api<void>(path(group, name), { method: "DELETE" }),
      onSuccess: refresh,
    }),
  };
}
