import type { ProcessInfo } from "../../api/types";
import type { Service } from "../services/servicesApi";
import { matches, runningInstances, servicesByOwner, undefinedRunning } from "./model";

const process = (group: string, name: string, running = true): ProcessInfo =>
  ({ group, name, rank: 1, runLevel: running ? "RUNNING" : "KILLED", running, connected: running, services: [] });

describe("applications model", () => {
  it("counts running instances per application", () => {
    const counts = runningInstances([process("Shop", "Cart"), process("Shop", "Cart"), process("Shop", "Cart", false),
      process("Shop", "Search")]);
    expect(counts.get("Shop:Cart")).toBe(2);
    expect(counts.get("Shop:Search")).toBe(1);
  });

  it("lists the services each application owns", () => {
    const service = (id: string, group: string, application: string) =>
      ({ id, group, application }) as unknown as Service;
    const owned = servicesByOwner([service("search", "Shop", "Search"), service("index", "Shop", "Search")]);
    expect(owned.get("Shop:Search")).toEqual(["index", "search"]);
  });

  it("finds applications running without a definition", () => {
    const running = runningInstances([process("Shop", "Cart"), process("Old", "Thing")]);
    expect(undefinedRunning(running, [{ group: "Shop", name: "Cart", description: "", builtIn: false }]))
      .toEqual(["Old:Thing"]);
  });

  it("searches groups, names and descriptions", () => {
    const definition = { group: "Shop", name: "Cart", description: "Baskets", builtIn: false };
    expect(matches(definition, "bask")).toBe(true);
    expect(matches(definition, "search")).toBe(false);
  });
});
