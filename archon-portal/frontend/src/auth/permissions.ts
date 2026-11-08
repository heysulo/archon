/** Permission keys, matching the portal's Permission enum. Roles grant them; "*" grants every one. */
export const PERMISSIONS = {
  processesView: "processes:view",
  usersManage: "users:manage",
  configsView: "configs:view",
  configsManage: "configs:manage",
  servicesView: "services:view",
  servicesManage: "services:manage",
} as const;

export type PermissionKey = (typeof PERMISSIONS)[keyof typeof PERMISSIONS];

export const ALL_PERMISSIONS = "*";

export function grants(permissions: readonly string[], permission: PermissionKey): boolean {
  return permissions.includes(ALL_PERMISSIONS) || permissions.includes(permission);
}
