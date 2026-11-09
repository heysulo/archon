import type { ProcessInfo, ProcessSnapshot } from "../api/types";

export function process(overrides: Partial<ProcessInfo>): ProcessInfo {
  return {
    group: "Group",
    name: "Application",
    rank: 1,
    runLevel: "RUNNING",
    running: true,
    connected: true,
    services: [],
    ...overrides,
  };
}

export const snapshot: ProcessSnapshot = {
  registry: "10.21.3.202",
  term: 10,
  generatedAt: "2026-11-08T17:10:18Z",
  servedBy: { host: "archon-portal-789849d744-8kdl5", rank: 1, role: "primary" },
  processes: [
    process({
      rank: 1,
      services: ["ping@192.168.192.78:9001"],
      secureServices: ["ping"],
      details: { host: "hello-world-java-4vsbc", address: "192.168.192.78", node: "kubernetes" },
    }),
    process({ rank: 2, services: ["ping@192.168.192.86:9001"], secureServices: [],
      details: { host: "hello-world-java-94fdt", address: "192.168.192.86", node: "kubernetes" } }),
    process({ rank: 3, runLevel: "KILLED", running: false, connected: false, details: { host: "hello-world-java-dead1" } }),
    process({ group: "Archon", name: "Registry", rank: 1, details: { host: "archon-registry-1", address: "10.21.3.202" } }),
    process({ group: "Archon", name: "Registry", rank: 2, details: { host: "archon-registry-0", address: "10.21.3.201" } }),
  ],
};
