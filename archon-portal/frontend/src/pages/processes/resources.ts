import type { InstanceMetrics, ProcessInfo } from "../../api/types";

/** A figure the instance could not measure. */
const UNKNOWN = -1;

const known = (value: number) => value !== UNKNOWN && value >= 0;

/** The process's CPU use as a percentage of the CPUs available to it, or undefined if unknown. */
export function cpuPercent(metrics: InstanceMetrics | undefined): number | undefined {
  return metrics && known(metrics.cpuLoad) ? metrics.cpuLoad * 100 : undefined;
}

/** The memory the process uses: resident memory where known (Linux), else the heap in use. */
export function memoryUsed(metrics: InstanceMetrics | undefined): number | undefined {
  if (!metrics) return undefined;
  if (known(metrics.rssBytes)) return metrics.rssBytes;
  return known(metrics.heapUsed) ? metrics.heapUsed : undefined;
}

/** What memoryUsed is measured against: the memory limit for resident memory, the heap's maximum for the heap. */
export function memoryLimit(metrics: InstanceMetrics | undefined): number | undefined {
  if (!metrics) return undefined;
  if (known(metrics.rssBytes)) return known(metrics.memoryLimit) && metrics.memoryLimit > 0 ? metrics.memoryLimit
    : undefined;
  return known(metrics.heapMax) && metrics.heapMax > 0 ? metrics.heapMax : undefined;
}

/** A percentage of a limit, capped at 100, or undefined without one. */
export function share(used: number | undefined, limit: number | undefined): number | undefined {
  return used === undefined || limit === undefined ? undefined : Math.min(100, (used / limit) * 100);
}

/** How busy a resource is, as a colour: fine, getting high, near the limit. */
export function loadColor(percent: number | undefined): string {
  if (percent === undefined) return "gray";
  return percent >= 90 ? "red" : percent >= 70 ? "yellow" : "teal";
}

const UNITS = ["B", "KB", "MB", "GB", "TB"];

/** Bytes for people, binary units: 512 MB, 1.5 GB. */
export function formatBytes(bytes: number | undefined): string {
  if (bytes === undefined || !known(bytes)) return "–";
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value >= 100 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${UNITS[unit]}`;
}

export function formatPercent(percent: number | undefined): string {
  if (percent === undefined) return "–";
  return percent < 10 ? `${percent.toFixed(1)}%` : `${Math.round(percent)}%`;
}

/** A duration for people: 45 s, 12 min, 3 h 5 min, 2 d 4 h. */
export function formatUptime(millis: number | undefined): string {
  if (millis === undefined || !known(millis)) return "–";
  const seconds = Math.floor(millis / 1000);
  if (seconds < 60) return `${seconds} s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ${minutes % 60} min`;
  return `${Math.floor(hours / 24)} d ${hours % 24} h`;
}

/** For sorting: the CPU or memory figure, undefined for processes that did not report one. */
export function resourceValue(process: ProcessInfo, resource: "cpu" | "memory"): number | undefined {
  return resource === "cpu" ? cpuPercent(process.metrics) : memoryUsed(process.metrics);
}
