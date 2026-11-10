import { parseServed } from "../services/model";
import { cpuPercent, memoryLimit, memoryUsed, resourceValue, share } from "./resources";
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

/** Something about a running instance that wants a look. */
export type Issue = "disconnected" | "notReady" | "unencrypted" | "nearLimit";

export const ISSUES: readonly Issue[] = ["disconnected", "notReady", "unencrypted", "nearLimit"];

/** The share of its CPUs or memory from which an instance counts as near its limit. */
export const NEAR_LIMIT_PERCENT = 90;

/** What is wrong with an instance, if anything. A stopped one has no issues: it is not expected to work. */
export function issuesOf(process: ProcessInfo): Issue[] {
  if (!process.running) return [];
  const issues: Issue[] = [];
  if (!process.connected) issues.push("disconnected");
  if (process.runLevel !== "RUNNING") issues.push("notReady");
  if (process.services.some((service) => !isSecure(process, service))) issues.push("unencrypted");
  const cpu = cpuPercent(process.metrics) ?? 0;
  const memory = share(memoryUsed(process.metrics), memoryLimit(process.metrics)) ?? 0;
  if (Math.max(cpu, memory) >= NEAR_LIMIT_PERCENT) issues.push("nearLimit");
  return issues;
}

/** How many instances have each issue. */
export function countIssues(processes: ProcessInfo[]): Record<Issue, number> {
  const counts: Record<Issue, number> = { disconnected: 0, notReady: 0, unencrypted: 0, nearLimit: 0 };
  for (const process of processes) {
    for (const issue of issuesOf(process)) counts[issue]++;
  }
  return counts;
}

/** A run level as a word: RUNNING is Running, SHUTTING_DOWN is Shutting down. */
export function stateLabel(runLevel: string): string {
  const words = runLevel.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export interface ProcessFilters {
  query: string;
  showStopped: boolean;
  /** `Group:Name` of the applications to show; empty shows all. */
  applications: string[];
  /** Run levels to show; empty shows all. */
  states: string[];
  /** Shows only instances with this issue; null shows all. */
  issue: Issue | null;
}

export function filterProcesses(processes: ProcessInfo[], filters: ProcessFilters): ProcessInfo[] {
  return processes.filter((process) =>
    (filters.showStopped || process.running)
    && (filters.applications.length === 0 || filters.applications.includes(applicationOf(process)))
    && (filters.states.length === 0 || filters.states.includes(process.runLevel))
    && (filters.issue === null || issuesOf(process).includes(filters.issue))
    && matches(process, filters.query));
}

export type SortColumn = "application" | "rank" | "role" | "state" | "cpu" | "memory" | "host" | "address" | "node"
  | "link" | "services";

export interface Sort {
  column: SortColumn;
  descending: boolean;
}

export const DEFAULT_SORT: Sort = { column: "application", descending: false };

const text = (value: string | undefined) => value ?? "";
// Missing values sort last whichever way the column is sorted
// Processes that have not reported a figure sort last whichever way the column is sorted
const missingLast = (a: number | undefined, b: number | undefined, descending: boolean) =>
  a === b ? 0 : a === undefined ? (descending ? -1 : 1) : b === undefined ? (descending ? 1 : -1) : a - b;

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
    case "cpu":
    case "memory":
      return missingLast(resourceValue(a, column), resourceValue(b, column), descending);
    case "services":
      return a.services.length - b.services.length;
  }
}

/** Sorts by the column, then in the default order, so equal rows keep a stable place. */
export function sortProcesses(processes: ProcessInfo[], { column, descending }: Sort): ProcessInfo[] {
  const sign = descending ? -1 : 1;
  return [...processes].sort((a, b) => sign * compareBy(column, a, b, descending) || defaultOrder(a, b));
}

export interface ApplicationGroup {
  /** `Group:Name`. */
  application: string;
  processes: ProcessInfo[];
}

/**
 * The processes under their applications. Applications keep the default order, so they do not jump about when a
 * column is sorted; the sort orders the instances inside each.
 */
export function groupByApplication(processes: ProcessInfo[], sort: Sort): ApplicationGroup[] {
  const groups = new Map<string, ProcessInfo[]>();
  for (const process of sortProcesses(processes, DEFAULT_SORT)) {
    groups.set(applicationOf(process), []);
  }
  for (const process of sortProcesses(processes, sort)) {
    groups.get(applicationOf(process))!.push(process);
  }
  return [...groups].map(([application, members]) => ({ application, processes: members }));
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

/** Whether a process serves a service, given as `id@host:port`, with TLS. */
export function isSecure(process: ProcessInfo, service: string): boolean {
  const served = parseServed(service);
  return Boolean(served && process.secureServices?.includes(served.id));
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
