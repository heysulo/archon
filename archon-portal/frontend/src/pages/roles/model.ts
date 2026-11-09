import { ALL_PERMISSIONS, grants } from "../../auth/permissions";
import type { PermissionInfo, Role } from "../users/usersApi";

/** Whether someone holding these permissions may grant a permission. Nobody grants what they do not hold. */
export function mayGrant(myPermissions: readonly string[], permission: string): boolean {
  return grants(myPermissions, permission);
}

/** Whether someone holding these permissions may give a role to a user: only if they hold all it grants. */
export function mayAssign(myPermissions: readonly string[], role: Role): boolean {
  if (myPermissions.includes(ALL_PERMISSIONS)) return true;
  return role.permissions.every((permission) => permission !== ALL_PERMISSIONS && grants(myPermissions, permission));
}

const ROLE_ID = /^[a-z0-9][a-z0-9._-]{0,63}$/;

export function roleIdError(value: string): string | null {
  return ROLE_ID.test(value.trim()) ? null : "Lowercase letters, digits, dots, dashes or underscores";
}

/** The actions a feature's permissions can grant, as columns, in this order; any others follow. */
export const ACTIONS = ["view", "manage"] as const;

const ACTION_LABELS: Record<string, string> = { view: "View", manage: "Modify" };

// In the order the portal's menu lists them; features without a label follow, as the portal lists them
const FEATURE_LABELS: Record<string, string> = {
  processes: "Processes",
  logs: "Logs",
  applications: "Applications",
  services: "Services",
  configs: "Configurations",
  users: "Users",
  roles: "Roles",
};

/** One feature's row: the permission for each action it has, keyed by action. */
export interface FeatureRow {
  feature: string;
  label: string;
  permissions: Map<string, PermissionInfo>;
}

export interface PermissionGrid {
  /** Columns, `view` and `manage` first. */
  actions: { key: string; label: string }[];
  rows: FeatureRow[];
}

/** Splits `configs:manage` into its feature and action. */
export function splitPermission(key: string): { feature: string; action: string } {
  const colon = key.indexOf(":");
  return colon < 0 ? { feature: key, action: "" } : { feature: key.slice(0, colon), action: key.slice(colon + 1) };
}

function titleCase(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Lays permissions out as features by actions, in the order the portal lists them. */
export function permissionGrid(permissions: PermissionInfo[]): PermissionGrid {
  const rows = new Map<string, FeatureRow>();
  const actions: string[] = [...ACTIONS];
  for (const permission of permissions) {
    const { feature, action } = splitPermission(permission.key);
    if (!actions.includes(action)) actions.push(action);
    const row = rows.get(feature) ?? { feature, label: FEATURE_LABELS[feature] ?? titleCase(feature),
      permissions: new Map() };
    row.permissions.set(action, permission);
    rows.set(feature, row);
  }
  const used = actions.filter((action) => [...rows.values()].some((row) => row.permissions.has(action)));
  const order = Object.keys(FEATURE_LABELS);
  const rank = (feature: string) => (order.includes(feature) ? order.indexOf(feature) : order.length);
  return {
    actions: used.map((key) => ({ key, label: ACTION_LABELS[key] ?? titleCase(key) })),
    // Stable, so unlabelled features keep the portal's order
    rows: [...rows.values()].sort((a, b) => rank(a.feature) - rank(b.feature)),
  };
}

/**
 * Ticks or unticks one permission. Modifying something means seeing it, so ticking `manage` ticks the feature's
 * `view` too, and unticking `view` unticks its `manage`.
 */
export function toggle(selected: string[], key: string, on: boolean, available: string[]): string[] {
  const { feature, action } = splitPermission(key);
  const next = new Set(selected);
  if (on) {
    next.add(key);
    const view = `${feature}:view`;
    if (action === "manage" && available.includes(view)) next.add(view);
  } else {
    next.delete(key);
    if (action === "view") next.delete(`${feature}:manage`);
  }
  return available.filter((permission) => next.has(permission));
}

/** A role's permissions grouped by feature, for showing compactly: `Configurations: View, Modify`. */
export function summarize(permissionKeys: string[], permissions: PermissionInfo[]): string[] {
  const grid = permissionGrid(permissions);
  const labels = new Map(grid.actions.map((action) => [action.key, action.label]));
  const known = new Set(permissions.map((permission) => permission.key));
  const lines = grid.rows
    .map((row) => {
      const held = grid.actions.filter((action) => permissionKeys.includes(`${row.feature}:${action.key}`));
      return held.length ? `${row.label}: ${held.map((action) => labels.get(action.key)).join(", ")}` : null;
    })
    .filter((line): line is string => line !== null);
  // Permissions the portal no longer knows still show, as stored
  return [...lines, ...permissionKeys.filter((key) => !known.has(key) && key !== "*")];
}
