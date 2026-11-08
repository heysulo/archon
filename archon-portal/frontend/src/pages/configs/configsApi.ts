import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../../api/http";

export type ConfigLevel = "GLOBAL" | "GROUP" | "APPLICATION";
export type ConfigType = "STRING" | "INTEGER" | "DECIMAL" | "BOOLEAN";

/** A configuration as GET /api/configs returns it. */
export interface Config {
  id: number;
  level: ConfigLevel;
  group: string;
  application: string;
  name: string;
  type: ConfigType;
  value: string;
  readOnly: boolean;
  description: string;
  updatedAt?: string;
  updatedBy?: string;
}

export type ConfigInput = Omit<Config, "id" | "updatedAt" | "updatedBy">;

const CONFIGS = ["configs"] as const;

export function useConfigs() {
  return useQuery({ queryKey: CONFIGS, queryFn: () => api<Config[]>("/api/configs") });
}

/** Every change refreshes the list afterwards, so it shows what archon-config stored. */
export function useConfigMutations() {
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: CONFIGS });
  return {
    create: useMutation({
      mutationFn: (config: ConfigInput) => api<Config>("/api/configs", { method: "POST", body: config }),
      onSuccess: refresh,
    }),
    update: useMutation({
      mutationFn: ({ id, config }: { id: number; config: ConfigInput }) =>
        api<Config>(`/api/configs/${id}`, { method: "PUT", body: config }),
      onSuccess: refresh,
    }),
    remove: useMutation({
      mutationFn: (id: number) => api<void>(`/api/configs/${id}`, { method: "DELETE" }),
      onSuccess: refresh,
    }),
  };
}
