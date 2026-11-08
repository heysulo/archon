import { process, snapshot } from "../../test/fixtures";
import { groupProcesses, matches, roleOf, serviceLabel, stateTone, summarize } from "./model";

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

describe("groupProcesses", () => {
  it("hides stopped instances unless asked, and lists applications before Archon", () => {
    const groups = groupProcesses(snapshot.processes, { showStopped: false, query: "" });
    expect(groups.map((group) => group.key)).toEqual(["Group/Application", "Archon/Registry"]);
    expect(groups[0]!.processes.map((p) => p.rank)).toEqual([1, 2]);
    expect(groups[0]!.stopped).toBe(0);
  });

  it("includes stopped instances when asked", () => {
    const groups = groupProcesses(snapshot.processes, { showStopped: true, query: "" });
    expect(groups[0]!.processes.map((p) => p.rank)).toEqual([1, 2, 3]);
    expect(groups[0]).toMatchObject({ running: 2, stopped: 1 });
  });

  it("drops groups with nothing matching the search", () => {
    const groups = groupProcesses(snapshot.processes, { showStopped: false, query: "registry-0" });
    expect(groups).toHaveLength(1);
    expect(groups[0]!.processes.map((p) => p.details?.host)).toEqual(["archon-registry-0"]);
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
    expect(summarize(snapshot.processes)).toEqual({ running: 4, applications: 2, services: 1 });
  });
});
