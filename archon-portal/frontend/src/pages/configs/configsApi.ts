import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
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

/** One page of configurations, and how many match in all. */
export interface ConfigPage {
  items: Config[];
  total: number;
  offset: number;
  limit: number;
}

export interface ConfigFilter {
  query: string;
  level: ConfigLevel | null;
  page: number;
  pageSize: number;
}

const CONFIGS = ["configs"] as const;

export function configsPath({ query, level, page, pageSize }: ConfigFilter): string {
  const params = new URLSearchParams({ offset: String((page - 1) * pageSize), limit: String(pageSize) });
  if (query.trim()) params.set("q", query.trim());
  if (level) params.set("level", level);
  return `/api/configs?${params}`;
}

/** Searched and paged by the portal, which holds every configuration in memory. */
export function useConfigs(filter: ConfigFilter) {
  return useQuery({
    queryKey: [...CONFIGS, filter],
    queryFn: () => api<ConfigPage>(configsPath(filter)),
    // Keeps the current page on screen while the next one loads
    placeholderData: keepPreviousData,
  });
}

/** A configuration an application's instances see, and the less specific ones of the same name it hides. */
export interface EffectiveConfig {
  effective: Config;
  /** Most specific first. */
  overridden: Config[];
}

/** What an instance of `group:application` sees, by name. */
export function useEffectiveConfigs(group: string, application: string, enabled = true) {
  return useQuery({
    queryKey: [...CONFIGS, "effective", group, application],
    queryFn: () => api<EffectiveConfig[]>(`/api/configs/effective?${new URLSearchParams({ group, application })}`),
    enabled,
  });
}

// archon-config pushes a change back to every portal replica a moment after storing it
const PUSH_DELAY_MILLIS = 1_000;

/** Every change refreshes the lists afterwards, so they show what archon-config stored. */
export function useConfigMutations() {
  const client = useQueryClient();
  const refresh = () => {
    setTimeout(() => void client.invalidateQueries({ queryKey: CONFIGS }), PUSH_DELAY_MILLIS);
    return client.invalidateQueries({ queryKey: CONFIGS });
  };
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
