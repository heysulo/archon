import type { ProcessInfo } from "../../api/types";
import type { Service, ServiceMode } from "./servicesApi";

export const MODE_LABELS: Record<ServiceMode, string> = {
  PRIMARY_MIRROR: "Primary / mirror",
  LOAD_BALANCE: "Load balanced",
};

export const MODE_DESCRIPTIONS: Record<ServiceMode, string> = {
  PRIMARY_MIRROR: "Clients use the lowest-ranked running instance; the rest stand by and take over on failure.",
  LOAD_BALANCE: "Clients spread across every running instance.",
};

/** An application instance announcing that it serves a service. */
export interface ServiceInstance {
  group: string;
  name: string;
  rank: number;
  host: string;
  port: number;
  /** Pod or host name, when the instance reported one. */
  hostName?: string;
  /** Whether clients are sent to it now: all instances of a load-balanced service, one of a primary/mirror. */
  serving: boolean;
}

/** Splits `id@host:port`, as each process lists the services it serves. */
export function parseServed(entry: string): { id: string; host: string; port: number } | null {
  const at = entry.indexOf("@");
  const colon = entry.lastIndexOf(":");
  if (at <= 0 || colon <= at + 1) return null;
  const port = Number(entry.slice(colon + 1));
  return Number.isInteger(port) ? { id: entry.slice(0, at), host: entry.slice(at + 1, colon), port } : null;
}

/**
 * Every live instance announcing each service, lowest rank first. Like the registry, only instances that are
 * running and connected count, and of a primary/mirror service only the lowest-ranked one serves.
 */
export function instancesByService(processes: ProcessInfo[], modes: Map<string, ServiceMode>):
  Map<string, ServiceInstance[]> {
  const byService = new Map<string, ServiceInstance[]>();
  for (const process of processes) {
    if (!process.running || !process.connected) continue;
    for (const entry of process.services) {
      const served = parseServed(entry);
      if (!served) continue;
      const list = byService.get(served.id) ?? [];
      list.push({ group: process.group, name: process.name, rank: process.rank, host: served.host,
        port: served.port, hostName: process.details?.host, serving: false });
      byService.set(served.id, list);
    }
  }
  for (const [id, list] of byService) {
    list.sort((a, b) => a.rank - b.rank);
    const balanced = modes.get(id) === "LOAD_BALANCE";
    list.forEach((instance, index) => (instance.serving = balanced || index === 0));
  }
  return byService;
}

/**
 * How many instances list each service, whatever their state. The portal refuses changes to a service any instance
 * lists, so this decides what the page offers.
 */
export function announcements(processes: ProcessInfo[]): Map<string, number> {
  const counts = new Map<string, number>();
  for (const process of processes) {
    for (const entry of process.services) {
      const served = parseServed(entry);
      if (served) counts.set(served.id, (counts.get(served.id) ?? 0) + 1);
    }
  }
  return counts;
}

/** Services instances still announce but that are no longer defined, as after a definition is deleted. */
export function undefinedServices(instances: Map<string, ServiceInstance[]>, services: Service[]): string[] {
  const defined = new Set(services.map((service) => service.id));
  return [...instances.keys()].filter((id) => !defined.has(id)).sort();
}

export function matches(service: Service, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (!needle) return true;
  return [service.id, service.name, service.group, service.application, MODE_LABELS[service.mode]]
    .some((field) => field.toLowerCase().includes(needle));
}

const IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

export function identifierError(value: string): string | null {
  return IDENTIFIER.test(value.trim()) ? null : "Letters, digits, dots, dashes or underscores";
}

export const MAX_PORT_OFFSET = 65534;
