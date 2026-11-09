// Mirrors the JSON the portal serves. The portal omits null fields, so anything that may be null is optional.

/** Where an instance runs, as it reported when it registered. */
export interface InstanceDetails {
  /** Host name; in Kubernetes, the pod name. */
  host?: string;
  /** Address others reach it at; in Kubernetes, the pod IP, or a registry's load balancer address. */
  address?: string;
  /** Machine it runs on; in Kubernetes, the node name. */
  node?: string;
}

/** CPU and memory an instance measured itself using; -1 for a figure it could not measure. */
export interface InstanceMetrics {
  /** When it measured, in milliseconds since the epoch, by its own clock. */
  sampledAt: number;
  /** Its share of the CPUs available to it, from 0 to 1. */
  cpuLoad: number;
  /** CPUs available to it: in a container, its CPU limit. */
  processors: number;
  /** Resident memory, on Linux. */
  rssBytes: number;
  /** Memory available to it: in a container, its memory limit. */
  memoryLimit: number;
  heapUsed: number;
  heapMax: number;
  nonHeapUsed: number;
  threads: number;
  uptimeMillis: number;
}

/** One application instance as the primary registry sees it. */
export interface ProcessInfo {
  group: string;
  name: string;
  rank: number;
  /** Lifecycle state, such as RUNNING, STARTING or KILLED. */
  runLevel: string;
  /** Whether that state counts as running. */
  running: boolean;
  /** Whether the instance's link to the registry is open. */
  connected: boolean;
  /** Services it serves, as `id@host:port`. */
  services: string[];
  /** Ids of the services it serves with TLS; absent from registries that predate TLS. */
  secureServices?: string[];
  details?: InstanceDetails;
  /** Its latest CPU and memory use; absent until it reports, and for stopped processes. */
  metrics?: InstanceMetrics;
}

export type PortalRole = "primary" | "mirror" | "unregistered";

/** The portal replica that sent a snapshot. */
export interface ServedBy {
  host: string;
  rank: number;
  role: PortalRole;
}

export interface ProcessSnapshot {
  /** Address of the primary registry that answered. */
  registry: string;
  /** That primary's leadership term. */
  term: number;
  /** When the registry produced this list, as an ISO timestamp. */
  generatedAt: string;
  servedBy?: ServedBy;
  processes: ProcessInfo[];
}
