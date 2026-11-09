import { process, snapshot } from "../../test/fixtures";
import { DEFAULT_SORT, filterProcesses, isSecure, matches, roleOf, serviceLabel, sortProcesses, stateTone, summarize,
  type ProcessFilters } from "./model";

describe("roleOf", () => {
  it("names leaders and standbys of applications", () => {
    expect(roleOf(process({ rank: 1 }))).toEqual({ label: "Leader", lead: true, former: false });
    expect(roleOf(process({ rank: 3 })).label).toBe("Standby");
  });

  it("names Archon's own components primary and mirror", () => {
    expect(roleOf(process({ group: "Archon", name: "Portal", rank: 1 })).label).toBe("Primary");
    expect(roleOf(process({ group: "Archon", name: "Portal", rank: 4 })).label).toBe("Mirror");
  });

  // A stopped record keeps its last rank, so two rows can show rank 1; only the running one leads
  it("marks stopped records as former holders of their rank", () => {
    expect(roleOf(process({ rank: 1, running: false, runLevel: "KILLED" }))).toEqual({
      label: "Former leader",
      lead: false,
      former: true,
    });
  });
});

describe("stateTone", () => {
  it("colours states by how healthy they are", () => {
    expect(stateTone(process({ runLevel: "RUNNING" }))).toBe("green");
    expect(stateTone(process({ runLevel: "STARTING" }))).toBe("yellow");
    expect(stateTone(process({ runLevel: "KILLED", running: false }))).toBe("red");
    expect(stateTone(process({ runLevel: "TERMINATED", running: false }))).toBe("gray");
  });
});

describe("matches", () => {
  const leader = snapshot.processes[0]!;

  it("searches names, pods, addresses, nodes and services, ignoring case", () => {
    expect(matches(leader, "")).toBe(true);
    expect(matches(leader, "APPLICATION")).toBe(true);
    expect(matches(leader, "4vsbc")).toBe(true);
    expect(matches(leader, "192.168.192.78")).toBe(true);
    expect(matches(leader, "kubernetes")).toBe(true);
    expect(matches(leader, "ping@")).toBe(true);
    expect(matches(leader, "billing")).toBe(false);
  });
});

const NONE: ProcessFilters = { query: "", showStopped: false, applications: [], states: [], link: "any" };
const hosts = (list: typeof snapshot.processes) => list.map((process) => process.details?.host);

describe("filterProcesses", () => {
  it("hides stopped instances unless asked", () => {
    expect(filterProcesses(snapshot.processes, NONE)).toHaveLength(4);
    expect(filterProcesses(snapshot.processes, { ...NONE, showStopped: true })).toHaveLength(5);
  });

  it("filters by application, state, registry link and search together", () => {
    expect(hosts(filterProcesses(snapshot.processes, { ...NONE, applications: ["Archon:Registry"] })))
      .toEqual(["archon-registry-1", "archon-registry-0"]);
    expect(hosts(filterProcesses(snapshot.processes, { ...NONE, showStopped: true, states: ["KILLED"] })))
      .toEqual(["hello-world-java-dead1"]);
    expect(hosts(filterProcesses(snapshot.processes, { ...NONE, showStopped: true, link: "disconnected" })))
      .toEqual(["hello-world-java-dead1"]);
    expect(hosts(filterProcesses(snapshot.processes, { ...NONE, applications: ["Archon:Registry"],
      query: "registry-0" }))).toEqual(["archon-registry-0"]);
  });
});

describe("sortProcesses", () => {
  const all = filterProcesses(snapshot.processes, { ...NONE, showStopped: true });

  it("lists applications before Archon by default, each by rank", () => {
    expect(hosts(sortProcesses(all, DEFAULT_SORT))).toEqual(["hello-world-java-4vsbc", "hello-world-java-94fdt",
      "hello-world-java-dead1", "archon-registry-1", "archon-registry-0"]);
  });

  it("sorts by any column either way, keeping blanks last", () => {
    expect(hosts(sortProcesses(all, { column: "host", descending: false }))[0]).toBe("archon-registry-0");
    expect(hosts(sortProcesses(all, { column: "host", descending: true }))[0]).toBe("hello-world-java-dead1");
    const byAddress = sortProcesses(all, { column: "address", descending: true });
    expect(byAddress.at(-1)!.details?.address).toBeUndefined();
    expect(byAddress[0]!.details?.address).toBe("192.168.192.86");
    expect(sortProcesses(all, { column: "rank", descending: true })[0]!.rank).toBe(3);
  });
});

describe("summarize", () => {
  it("drops the address from a service served where the row's address already says", () => {
    expect(serviceLabel("ping@192.168.192.123:9001", "192.168.192.123")).toBe("ping:9001");
    expect(serviceLabel("ping@10.0.0.9:9001", "192.168.192.123")).toBe("ping@10.0.0.9:9001");
    expect(serviceLabel("ping@10.0.0.9:9001", undefined)).toBe("ping@10.0.0.9:9001");
    expect(serviceLabel("odd", "10.0.0.9")).toBe("odd");
  });

  it("counts running instances, their applications and services", () => {
    expect(summarize(snapshot.processes)).toEqual({ running: 4, applications: 2, services: 2 });
  });
});

describe("isSecure", () => {
  it("reads which services a process serves with TLS", () => {
    const [leader, standby] = snapshot.processes;
    expect(isSecure(leader!, "ping@192.168.192.78:9001")).toBe(true);
    expect(isSecure(standby!, "ping@192.168.192.86:9001")).toBe(false);
    expect(isSecure({ ...leader!, secureServices: undefined }, "ping@192.168.192.78:9001")).toBe(false);
  });
});
