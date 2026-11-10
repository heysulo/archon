import { ActionIcon, Alert, Badge, Button, Center, Group, Loader, Menu, Modal, Stack, Table, Text,
  Tooltip } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { IconDots, IconLock, IconPencil, IconPlus, IconTrash } from "@tabler/icons-react";
import { useState } from "react";
import { useAuth } from "../../auth/AuthProvider";
import { ALL_PERMISSIONS, PERMISSIONS } from "../../auth/permissions";
import { useRoles, type PermissionInfo, type Role } from "../users/usersApi";
import { summarize } from "./model";
import { RoleFormModal } from "./RoleFormModal";
import { useRoleMutations, type RoleInput } from "./rolesApi";
import { PageHeader } from "../../ui/PageHeader";
import { TableCard } from "../../ui/TableCard";
import { TableCount } from "../../ui/TableCount";

// One chip per feature, so a role stays one or two lines however many features there are
function Permissions({ role, permissions }: { role: Role; permissions: PermissionInfo[] }) {
  if (role.permissions.includes(ALL_PERMISSIONS)) {
    return (
      <Badge color="red" tt="none">
        Every permission
      </Badge>
    );
  }
  return (
    <Group gap={6}>
      {summarize(role.permissions, permissions).map((line) => (
        // Changing something stands out from only looking at it
        <Badge key={line} variant={line.endsWith("View") ? "default" : "light"} fw={500}>
          {line}
        </Badge>
      ))}
    </Group>
  );
}

export function RolesPage() {
  const { user, refresh, can } = useAuth();
  const manage = can(PERMISSIONS.rolesManage);
  const roles = useRoles();
  const { create, update, remove } = useRoleMutations(refresh);
  // undefined: closed; null: adding; a role: editing it
  const [editing, setEditing] = useState<Role | null | undefined>(undefined);
  const [deleting, setDeleting] = useState<Role | null>(null);
  const myPermissions = user?.permissions ?? [];

  const save = async (input: RoleInput) => {
    if (editing === null) {
      await create.mutateAsync(input);
      notifications.show({ color: "green", message: `Added ${input.id}` });
    } else if (editing) {
      await update.mutateAsync(input);
      notifications.show({ color: "green", message: `Saved ${input.id}` });
    }
  };

  const confirmDelete = async () => {
    if (!deleting) return;
    try {
      await remove.mutateAsync(deleting.id);
      notifications.show({ color: "green", message: `Deleted ${deleting.id}` });
      setDeleting(null);
    } catch (failure) {
      notifications.show({ color: "red", message: failure instanceof Error ? failure.message : "Delete failed" });
    }
  };

  return (
    <Stack gap="md">
      <PageHeader title="Roles" description="Each role grants permissions; users get every permission of every role they hold. You can grant only permissions you hold yourself."
                  action={manage && (
                    <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing(null)}>
                      Add role
                    </Button>
                  )} />

      {roles.isError && (
        <Alert color="red" variant="light">
          {roles.error.message}
        </Alert>
      )}

      {roles.isPending ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : (
        <TableCard footer={<TableCount shown={roles.data?.roles.length ?? 0} total={roles.data?.roles.length ?? 0}
                                       one="role" many="roles" />}>
            <Table miw={760}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Role</Table.Th>
                  <Table.Th>Permissions</Table.Th>
                  <Table.Th>Users</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {(roles.data?.roles ?? []).map((role) => (
                  <Table.Tr key={role.id}>
                    <Table.Td>
                      <Group gap={6} wrap="nowrap">
                        <Text size="sm" ff="monospace" fw={500}>
                          {role.id}
                        </Text>
                        {role.builtIn && (
                          <Tooltip label="Built in: holds every permission and cannot be changed">
                            <IconLock size={14} aria-label="Built in" />
                          </Tooltip>
                        )}
                      </Group>
                      {role.description && (
                        <Text size="xs" c="dimmed">
                          {role.description}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Permissions role={role} permissions={roles.data?.permissions ?? []} />
                    </Table.Td>
                    <Table.Td>
                      {role.users.length ? (
                        <Tooltip label={role.users.join(", ")}>
                          <Text size="sm">{role.users.length === 1 ? "1 user" : `${role.users.length} users`}</Text>
                        </Tooltip>
                      ) : (
                        <Text size="sm" c="dimmed">
                          None
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>
                      {manage && !role.builtIn && (
                        <Menu position="bottom-end">
                          <Menu.Target>
                            <ActionIcon variant="subtle" color="gray" aria-label={`Actions for ${role.id}`}>
                              <IconDots size={16} />
                            </ActionIcon>
                          </Menu.Target>
                          <Menu.Dropdown>
                            <Menu.Item leftSection={<IconPencil size={16} />} onClick={() => setEditing(role)}>
                              Edit
                            </Menu.Item>
                            <Menu.Item color="red" leftSection={<IconTrash size={16} />}
                                       disabled={role.users.length > 0} onClick={() => setDeleting(role)}>
                              {role.users.length > 0 ? "Delete (still held)" : "Delete"}
                            </Menu.Item>
                          </Menu.Dropdown>
                        </Menu>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
        </TableCard>
      )}

      <RoleFormModal opened={editing !== undefined} role={editing ?? null} permissions={roles.data?.permissions ?? []}
                     myPermissions={myPermissions} onClose={() => setEditing(undefined)} onSubmit={save} />
      <Modal opened={deleting !== null} onClose={() => setDeleting(null)} title={`Delete ${deleting?.id ?? ""}?`}>
        <Stack>
          <Text size="sm">Nobody holds it, so no one loses access.</Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setDeleting(null)}>
              Cancel
            </Button>
            <Button color="red" loading={remove.isPending} onClick={() => void confirmDelete()}>
              Delete role
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
