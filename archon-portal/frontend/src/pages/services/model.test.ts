import type { ProcessInfo } from "../../api/types";
import { announcements, instancesByService, matches, parseServed, undefinedServices } from "./model";
import type { Service, ServiceMode } from "./servicesApi";

function process(rank: number, services: string[], extra: Partial<ProcessInfo> = {}): ProcessInfo {
  return { group: "Group", name: "Application", rank, runLevel: "RUNNING", running: true, connected: true, services,
    ...extra };
}

const PING: Service = { id: "ping", name: "Ping", portOffset: 0, group: "Group", application: "Application",
  mode: "PRIMARY_MIRROR", managedByArchon: false };

describe("services model", () => {
  it("reads what a process serves", () => {
    expect(parseServed("ping@10.0.0.5:9000")).toEqual({ id: "ping", host: "10.0.0.5", port: 9000 });
    expect(parseServed("ping")).toBeNull();
    expect(parseServed("ping@host:port")).toBeNull();
  });

  it("marks the lowest-ranked instance of a primary/mirror service as serving", () => {
    const modes = new Map<string, ServiceMode>([["ping", "PRIMARY_MIRROR"]]);
    const found = instancesByService([process(2, ["ping@b:9000"]), process(1, ["ping@a:9000"])], modes).get("ping");
    expect(found?.map((instance) => [instance.rank, instance.serving])).toEqual([[1, true], [2, false]]);
  });

  it("marks every instance of a load-balanced service as serving", () => {
    const modes = new Map<string, ServiceMode>([["search", "LOAD_BALANCE"]]);
    const found = instancesByService([process(1, ["search@a:9001"]), process(2, ["search@b:9001"])], modes);
    expect(found.get("search")?.every((instance) => instance.serving)).toBe(true);
  });

  it("ignores instances that are stopped or cut off, as the registry does", () => {
    const found = instancesByService([process(1, ["ping@a:9000"], { running: false }),
      process(2, ["ping@b:9000"], { connected: false })], new Map());
    expect(found.get("ping")).toBeUndefined();
  });

  it("finds services still served after their definition was deleted", () => {
    const found = instancesByService([process(1, ["ping@a:9000", "old@a:9005"])], new Map());
    expect(undefinedServices(found, [PING])).toEqual(["old"]);
  });

  // The portal refuses changes while any instance lists the service, stopped or cut off included
  it("counts every instance listing a service, whatever its state", () => {
    const counts = announcements([process(1, ["ping@a:9000"]), process(2, ["ping@b:9000"], { running: false })]);
    expect(counts.get("ping")).toBe(2);
    expect(counts.has("search")).toBe(false);
  });

  it("searches ids, names, owners and modes", () => {
    expect(matches(PING, "PIN")).toBe(true);
    expect(matches(PING, "application")).toBe(true);
    expect(matches(PING, "mirror")).toBe(true);
    expect(matches(PING, "balanced")).toBe(false);
  });
});
