import { grants } from "./permissions";

describe("grants", () => {
  it("grants directly and through *", () => {
    expect(grants(["configs:view"], "configs:view")).toBe(true);
    expect(grants(["*"], "roles:manage")).toBe(true);
    expect(grants(["configs:view"], "configs:manage")).toBe(false);
  });

  // Modifying something includes seeing it
  it("grants view through the same feature's manage", () => {
    expect(grants(["users:manage"], "users:view")).toBe(true);
    expect(grants(["roles:manage"], "users:view")).toBe(false);
  });
});
