import { parseServed } from "../services/model";
import type { ProcessInfo } from "../../api/types";

/** Archon's own components, the registries and portals, rank as one primary and any number of mirrors. */
export const ARCHON_GROUP = "Archon";

export interface Role {
  label: string;
  /** Holds rank 1 now. */
  lead: boolean;
  /** A stopped record, which keeps the rank it last held. */
  former: boolean;
}

export function roleOf(process: ProcessInfo): Role {
  if (process.rank <= 0) {
    return { label: "Unassigned", lead: false, former: !process.running };
  }
  const archon = process.group === ARCHON_GROUP;
  const name = process.rank === 1 ? (archon ? "Primary" : "Leader") : archon ? "Mirror" : "Standby";
  if (!process.running) {
    return { label: `Former ${name.toLowerCase()}`, lead: false, former: true };
  }
  return { label: name, lead: process.rank === 1, former: false };
}

export type StateTone = "green" | "yellow" | "red" | "gray";

export function stateTone(process: ProcessInfo): StateTone {
  if (process.runLevel === "RUNNING") return "green";
  if (process.running) return "yellow";
  if (process.runLevel === "KILLED" || process.runLevel === "FAILED") return "red";
  return "gray";
}

/** Case-insensitive match against everything a row shows. */
export function matches(process: ProcessInfo, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (!needle) return true;
  const { host, address, node } = process.details ?? {};
  return [process.group, process.name, process.runLevel, host, address, node, ...process.services]
    .filter((value): value is string => Boolean(value))
    .some((value) => value.toLowerCase().includes(needle));
}

export const applicationOf = (process: ProcessInfo) => `${process.group}:${process.name}`;

export type LinkFilter = "any" | "connected" | "disconnected";

export interface ProcessFilters {
  query: string;
  showStopped: boolean;
  /** `Group:Name` of the applications to show; empty shows all. */
  applications: string[];
  /** Run levels to show; empty shows all. */
  states: string[];
  link: LinkFilter;
}

export function filterProcesses(processes: ProcessInfo[], filters: ProcessFilters): ProcessInfo[] {
  return processes.filter((process) =>
    (filters.showStopped || process.running)
    && (filters.applications.length === 0 || filters.applications.includes(applicationOf(process)))
    && (filters.states.length === 0 || filters.states.includes(process.runLevel))
    && (filters.link === "any" || process.connected === (filters.link === "connected"))
    && matches(process, filters.query));
}

export type SortColumn = "application" | "rank" | "role" | "state" | "host" | "address" | "node" | "link"
  | "services";

export interface Sort {
  column: SortColumn;
  descending: boolean;
}

export const DEFAULT_SORT: Sort = { column: "application", descending: false };

const text = (value: string | undefined) => value ?? "";
// Missing values sort last whichever way the column is sorted
const blankLast = (a: string, b: string, descending: boolean) =>
  a === b ? 0 : !a ? (descending ? -1 : 1) : !b ? (descending ? 1 : -1) : a.localeCompare(b, undefined,
    { numeric: true });

// Applications before Archon's own components, then by name and rank, running records before stopped ones
function defaultOrder(a: ProcessInfo, b: ProcessInfo): number {
  const archon = (process: ProcessInfo) => Number(process.group === ARCHON_GROUP);
  return archon(a) - archon(b) || applicationOf(a).localeCompare(applicationOf(b)) || a.rank - b.rank
    || Number(b.running) - Number(a.running) || text(a.details?.host).localeCompare(text(b.details?.host));
}

function compareBy(column: SortColumn, a: ProcessInfo, b: ProcessInfo, descending: boolean): number {
  switch (column) {
    case "application":
      return defaultOrder(a, b);
    case "rank":
      return a.rank - b.rank;
    case "role":
      return roleOf(a).label.localeCompare(roleOf(b).label);
    case "state":
      return a.runLevel.localeCompare(b.runLevel);
    case "host":
      return blankLast(text(a.details?.host), text(b.details?.host), descending);
    case "address":
      return blankLast(text(a.details?.address), text(b.details?.address), descending);
    case "node":
      return blankLast(text(a.details?.node), text(b.details?.node), descending);
    case "link":
      return Number(b.connected) - Number(a.connected);
    case "services":
      return a.services.length - b.services.length;
  }
}

/** Sorts by the column, then in the default order, so equal rows keep a stable place. */
export function sortProcesses(processes: ProcessInfo[], { column, descending }: Sort): ProcessInfo[] {
  const sign = descending ? -1 : 1;
  return [...processes].sort((a, b) => sign * compareBy(column, a, b, descending) || defaultOrder(a, b));
}

export interface Summary {
  running: number;
  applications: number;
  services: number;
}

export function summarize(processes: ProcessInfo[]): Summary {
  const running = processes.filter((process) => process.running);
  return {
    running: running.length,
    applications: new Set(running.map((process) => `${process.group}/${process.name}`)).size,
    services: running.reduce((count, process) => count + process.services.length, 0),
  };
}

/**
 * A service as the process row shows it: `ping:9001`, since the row already shows the instance's address. One served
 * on another address keeps it, `ping@10.0.0.9:9001`, so that is not lost.
 */
export function serviceLabel(service: string, address: string | undefined): string {
  const served = parseServed(service);
  if (!served) return service;
  return served.host === address ? `${served.id}:${served.port}` : service;
}
