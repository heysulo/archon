import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../../api/http";

export type ServiceMode = "PRIMARY_MIRROR" | "LOAD_BALANCE";

/** A service definition as GET /api/services returns it. */
export interface Service {
  id: string;
  name: string;
  /** Added to each instance's ARCHON_BASE_PORT to give the port it serves on. */
  portOffset: number;
  group: string;
  application: string;
  mode: ServiceMode;
  /** Owned by Archon itself, such as archon-config: shown, but not changeable here. */
  managedByArchon: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export type ServiceInput = Pick<Service, "id" | "name" | "portOffset" | "group" | "application" | "mode">;

const SERVICES = ["services"] as const;

/** @param enabled false skips the request, for those who may not see services */
export function useServices(enabled = true) {
  return useQuery({ queryKey: SERVICES, queryFn: () => api<Service[]>("/api/services"), enabled });
}

export function useServiceMutations() {
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: SERVICES });
  return {
    create: useMutation({
      mutationFn: (service: ServiceInput) => api<Service>("/api/services", { method: "POST", body: service }),
      onSuccess: refresh,
    }),
    update: useMutation({
      mutationFn: (service: ServiceInput) =>
        api<Service>(`/api/services/${encodeURIComponent(service.id)}`, { method: "PUT", body: service }),
      onSuccess: refresh,
    }),
    remove: useMutation({
      mutationFn: (id: string) => api<void>(`/api/services/${encodeURIComponent(id)}`, { method: "DELETE" }),
      onSuccess: refresh,
    }),
  };
}
