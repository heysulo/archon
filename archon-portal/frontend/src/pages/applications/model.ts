import type { ProcessInfo } from "../../api/types";
import type { Service } from "../services/servicesApi";
import type { ApplicationDefinition } from "./applicationsApi";

export const key = (group: string, name: string) => `${group}:${name}`;

/** Running instances per application, as `Group:Name`. */
export function runningInstances(processes: ProcessInfo[]): Map<string, number> {
  const counts = new Map<string, number>();
  for (const process of processes) {
    if (!process.running) continue;
    const id = key(process.group, process.name);
    counts.set(id, (counts.get(id) ?? 0) + 1);
  }
  return counts;
}

/** Service ids per owning application, as `Group:Name`. */
export function servicesByOwner(services: Service[]): Map<string, string[]> {
  const owned = new Map<string, string[]>();
  for (const service of services) {
    const id = key(service.group, service.application);
    owned.set(id, [...(owned.get(id) ?? []), service.id].sort());
  }
  return owned;
}

/**
 * Applications with running instances that are not defined: registered before the definition was removed. The
 * registry refuses them on their next reconnect.
 */
export function undefinedRunning(running: Map<string, number>, definitions: ApplicationDefinition[]): string[] {
  const defined = new Set(definitions.map((definition) => key(definition.group, definition.name)));
  return [...running.keys()].filter((id) => !defined.has(id)).sort();
}

export function matches(definition: ApplicationDefinition, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (!needle) return true;
  return [definition.group, definition.name, definition.description].some((field) =>
    field.toLowerCase().includes(needle));
}

const IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

export function identifierError(value: string): string | null {
  return IDENTIFIER.test(value.trim()) ? null : "Letters, digits, dots, dashes or underscores";
}
