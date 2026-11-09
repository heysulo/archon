/** Permission keys, matching the portal's Permission enum. Roles grant them; "*" grants every one. */
export const PERMISSIONS = {
  processesView: "processes:view",
  logsView: "logs:view",
  applicationsView: "applications:view",
  applicationsManage: "applications:manage",
  usersView: "users:view",
  usersManage: "users:manage",
  rolesView: "roles:view",
  rolesManage: "roles:manage",
  configsView: "configs:view",
  configsManage: "configs:manage",
  servicesView: "services:view",
  servicesManage: "services:manage",
} as const;

export type PermissionKey = (typeof PERMISSIONS)[keyof typeof PERMISSIONS];

export const ALL_PERMISSIONS = "*";

/**
 * Whether these permissions grant one, as the portal decides: directly, through `*`, or for a `view` permission
 * through its feature's `manage`, since modifying something includes seeing it.
 */
export function grants(permissions: readonly string[], permission: PermissionKey | string): boolean {
  if (permissions.includes(ALL_PERMISSIONS) || permissions.includes(permission)) return true;
  return permission.endsWith(":view") && permissions.includes(`${permission.slice(0, -":view".length)}:manage`);
}
