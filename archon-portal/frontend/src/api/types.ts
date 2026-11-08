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
  details?: InstanceDetails;
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
