import type { Config } from "./configsApi";
import { scopeOf, valueError } from "./model";

const config: Config = { id: 1, level: "APPLICATION", group: "Group", application: "Application",
  name: "ping.intervalMillis", type: "INTEGER", value: "1000", readOnly: false, description: "How often to ping" };

describe("configs model", () => {
  it("names who sees a configuration", () => {
    expect(scopeOf({ level: "GLOBAL", group: "", application: "" })).toBe("Everyone");
    expect(scopeOf({ level: "GROUP", group: "Shop", application: "" })).toBe("Shop");
    expect(scopeOf(config)).toBe("Group / Application");
  });

  it("checks values against their type", () => {
    expect(valueError("INTEGER", " 42 ")).toBeNull();
    expect(valueError("INTEGER", "4.2")).toBe("A whole number");
    expect(valueError("DECIMAL", "4.2")).toBeNull();
    expect(valueError("DECIMAL", "")).toBe("A number");
    expect(valueError("BOOLEAN", "yes")).toBe("true or false");
    expect(valueError("STRING", "")).toBeNull();
  });
});
