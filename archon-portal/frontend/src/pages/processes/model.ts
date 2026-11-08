import type { ProcessInfo } from "../../api/types";

/** Archon's own components, the registries and portals, rank as one primary and any number of mirrors. */
export const ARCHON_GROUP = "Archon";

export interface ApplicationGroup {
  key: string;
  group: string;
  name: string;
  processes: ProcessInfo[];
  running: number;
  stopped: number;
}

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

interface GroupOptions {
  showStopped: boolean;
  query: string;
}

/** Groups instances by application: applications first, Archon's own components last, each ordered by rank. */
export function groupProcesses(processes: ProcessInfo[], { showStopped, query }: GroupOptions): ApplicationGroup[] {
  const groups = new Map<string, ApplicationGroup>();
  for (const process of processes) {
    if (!showStopped && !process.running) continue;
    if (!matches(process, query)) continue;
    const key = `${process.group}/${process.name}`;
    let group = groups.get(key);
    if (!group) {
      group = { key, group: process.group, name: process.name, processes: [], running: 0, stopped: 0 };
      groups.set(key, group);
    }
    group.processes.push(process);
    if (process.running) group.running += 1;
    else group.stopped += 1;
  }
  for (const group of groups.values()) {
    group.processes.sort((a, b) => a.rank - b.rank || Number(b.running) - Number(a.running));
  }
  const isArchon = (group: ApplicationGroup) => group.group === ARCHON_GROUP;
  return [...groups.values()].sort((a, b) => Number(isArchon(a)) - Number(isArchon(b)) || a.key.localeCompare(b.key));
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
