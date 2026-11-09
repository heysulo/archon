import type { Role } from "../users/usersApi";
import { mayAssign, mayGrant, permissionGrid, roleIdError, summarize, toggle } from "./model";

const role = (permissions: string[]): Role => ({ id: "r", description: "", permissions, builtIn: false, users: [] });

describe("roles model", () => {
  it("grants only what you hold", () => {
    expect(mayGrant(["*"], "users:manage")).toBe(true);
    expect(mayGrant(["roles:manage", "processes:view"], "processes:view")).toBe(true);
    expect(mayGrant(["roles:manage"], "configs:manage")).toBe(false);
  });

  it("gives a role only if you hold everything it grants", () => {
    expect(mayAssign(["*"], role(["*"]))).toBe(true);
    expect(mayAssign(["users:manage", "processes:view"], role(["processes:view"]))).toBe(true);
    expect(mayAssign(["users:manage"], role(["processes:view"]))).toBe(false);
    expect(mayAssign(["users:manage", "processes:view"], role(["*"]))).toBe(false);
  });

  it("checks role ids as the portal does", () => {
    expect(roleIdError("operator")).toBeNull();
    expect(roleIdError("on-call.v2")).toBeNull();
    expect(roleIdError("Operator")).not.toBeNull();
    expect(roleIdError("has space")).not.toBeNull();
  });

  const PERMISSIONS = ["processes:view", "users:manage", "roles:manage", "configs:view", "configs:manage",
    "services:view", "services:manage"].map((key) => ({ key, description: key }));
  const KEYS = PERMISSIONS.map((permission) => permission.key);

  it("lays permissions out as features by actions, in menu order", () => {
    const grid = permissionGrid(PERMISSIONS);
    expect(grid.actions.map((action) => action.label)).toEqual(["View", "Modify"]);
    expect(grid.rows.map((row) => row.label)).toEqual(["Processes", "Services", "Configurations", "Users", "Roles"]);
    expect([...(grid.rows[0]?.permissions.keys() ?? [])]).toEqual(["view"]);
    expect([...(grid.rows[3]?.permissions.keys() ?? [])]).toEqual(["manage"]);
  });

  it("keeps features and actions the portal adds later", () => {
    const grid = permissionGrid([...PERMISSIONS, { key: "alerts:mute", description: "" }]);
    expect(grid.rows.at(-1)?.label).toBe("Alerts");
    expect(grid.actions.map((action) => action.key)).toEqual(["view", "manage", "mute"]);
  });

  it("treats modifying as including viewing", () => {
    expect(toggle([], "configs:manage", true, KEYS)).toEqual(["configs:view", "configs:manage"]);
    expect(toggle(["configs:view", "configs:manage"], "configs:view", false, KEYS)).toEqual([]);
    expect(toggle(["configs:view", "configs:manage"], "configs:manage", false, KEYS)).toEqual(["configs:view"]);
    expect(toggle([], "users:manage", true, KEYS)).toEqual(["users:manage"]);
  });

  it("summarizes a role per feature", () => {
    expect(summarize(["configs:manage", "configs:view", "processes:view", "gone:view"], PERMISSIONS))
      .toEqual(["Processes: View", "Configurations: View, Modify", "gone:view"]);
  });
});
