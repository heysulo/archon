import { ActionIcon, Alert, Badge, Button, Center, Group, Loader, Menu, Modal, Stack, Table, Text, TextInput }
  from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { IconDots, IconKey, IconPencil, IconPlus, IconSearch, IconTrash } from "@tabler/icons-react";
import { useState } from "react";
import { useAuth } from "../../auth/AuthProvider";
import { PERMISSIONS } from "../../auth/permissions";
import { ResetPasswordModal } from "./ResetPasswordModal";
import { UserFormModal, type UserFormValues } from "./UserFormModal";
import { useRoles, useUserMutations, useUsers, type User } from "./usersApi";
import { PageHeader } from "../../ui/PageHeader";
import { TableCard } from "../../ui/TableCard";
import { When } from "../../ui/When";
import { EmptyState } from "../../ui/EmptyState";
import { TableCount } from "../../ui/TableCount";

export function UsersPage() {
  const { user: me, can } = useAuth();
  const manage = can(PERMISSIONS.usersManage);
  const users = useUsers();
  // Only for assigning roles, so only fetched by those who can
  const roles = useRoles(manage);
  const [query, setQuery] = useState("");
  const all = users.data ?? [];
  const needle = query.trim().toLowerCase();
  const shown = all.filter((user) => !needle
    || [user.username, user.displayName, ...user.roles].some((value) => value.toLowerCase().includes(needle)));
  const { create, update, resetPassword, remove } = useUserMutations();
  // undefined: closed; null: adding a user; a user: editing them
  const [editing, setEditing] = useState<User | null | undefined>(undefined);
  const [resetting, setResetting] = useState<User | null>(null);
  const [deleting, setDeleting] = useState<User | null>(null);

  const save = async (values: UserFormValues) => {
    if (editing === null) {
      await create.mutateAsync({
        username: values.username.trim(),
        displayName: values.displayName.trim(),
        password: values.password,
        roles: values.roles,
      });
      notifications.show({ color: "green", message: `Added ${values.username.trim().toLowerCase()}` });
    } else if (editing) {
      await update.mutateAsync({
        id: editing.id,
        changes: { displayName: values.displayName.trim(), enabled: values.enabled, roles: values.roles },
      });
      notifications.show({ color: "green", message: `Saved ${editing.username}` });
    }
  };

  const confirmDelete = async () => {
    if (!deleting) return;
    try {
      await remove.mutateAsync(deleting.id);
      notifications.show({ color: "green", message: `Deleted ${deleting.username}` });
      setDeleting(null);
    } catch (failure) {
      notifications.show({ color: "red", message: failure instanceof Error ? failure.message : "Delete failed" });
    }
  };

  return (
    <Stack gap="md">
      <PageHeader title="Users" description="Who can sign in to the portal, and with which roles"
                  action={manage && (
                    <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing(null)}>
                      Add user
                    </Button>
                  )} />

      <TextInput
        placeholder="Search by username, name or role"
        leftSection={<IconSearch size={16} />}
        value={query}
        onChange={(event) => setQuery(event.currentTarget.value)}
        aria-label="Search users"
      />

      {users.isError && (
        <Alert color="red" variant="light">
          {users.error.message}
        </Alert>
      )}

      {users.isPending ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : shown.length === 0 ? (
        <EmptyState icon={IconSearch} title="No users match"
                    action={<Button variant="subtle" size="xs" onClick={() => setQuery("")}>Clear search</Button>} />
      ) : (
        <TableCard footer={<TableCount shown={shown.length} total={all.length} one="user" many="users" />}>
            <Table miw={760} style={{ whiteSpace: "nowrap" }}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Username</Table.Th>
                  <Table.Th>Name</Table.Th>
                  <Table.Th>Roles</Table.Th>
                  <Table.Th>Status</Table.Th>
                  <Table.Th>Last sign-in</Table.Th>
                  <Table.Th>Created</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {shown.map((user) => {
                  const isSelf = user.id === me?.id;
                  return (
                    <Table.Tr key={user.id}>
                      <Table.Td>
                        <Group gap={6}>
                          <Text size="sm" ff="monospace">
                            {user.username}
                          </Text>
                          {isSelf && (
                            <Badge size="xs" variant="light">
                              You
                            </Badge>
                          )}
                        </Group>
                      </Table.Td>
                      <Table.Td>{user.displayName}</Table.Td>
                      <Table.Td>
                        <Group gap={4}>
                          {user.roles.map((role) => (
                            <Badge key={role} variant="outline">
                              {role}
                            </Badge>
                          ))}
                        </Group>
                      </Table.Td>
                      <Table.Td>
                        <Badge color={user.enabled ? "green" : "gray"}>
                          {user.enabled ? "Enabled" : "Disabled"}
                        </Badge>
                      </Table.Td>
                      <Table.Td>
                        <When value={user.lastLoginAt} never="Never" />
                      </Table.Td>
                      <Table.Td>
                        <When value={user.createdAt} />
                      </Table.Td>
                      <Table.Td>
                        {manage && (
                          <Menu position="bottom-end">
                            <Menu.Target>
                              <ActionIcon variant="subtle" color="gray" aria-label={`Actions for ${user.username}`}>
                                <IconDots size={16} />
                              </ActionIcon>
                            </Menu.Target>
                            <Menu.Dropdown>
                              <Menu.Item leftSection={<IconPencil size={16} />} onClick={() => setEditing(user)}>
                                Edit
                              </Menu.Item>
                              <Menu.Item leftSection={<IconKey size={16} />} onClick={() => setResetting(user)}>
                                Reset password
                              </Menu.Item>
                              <Menu.Item
                                color="red"
                                leftSection={<IconTrash size={16} />}
                                disabled={isSelf}
                                onClick={() => setDeleting(user)}
                              >
                                Delete
                              </Menu.Item>
                            </Menu.Dropdown>
                          </Menu>
                        )}
                      </Table.Td>
                    </Table.Tr>
                  );
                })}
              </Table.Tbody>
            </Table>
        </TableCard>
      )}

      <UserFormModal
        opened={editing !== undefined}
        user={editing ?? null}
        isSelf={editing?.id === me?.id}
        roles={roles.data?.roles ?? []}
        onClose={() => setEditing(undefined)}
        onSubmit={save}
      />
      <ResetPasswordModal
        user={resetting}
        onClose={() => setResetting(null)}
        onSubmit={async (password) => {
          if (!resetting) return;
          await resetPassword.mutateAsync({ id: resetting.id, password });
          notifications.show({ color: "green", message: `Reset the password of ${resetting.username}` });
        }}
      />
      <Modal opened={deleting !== null} onClose={() => setDeleting(null)} title={`Delete ${deleting?.username ?? ""}?`}>
        <Stack>
          <Text size="sm">They are signed out and can no longer sign in. This cannot be undone.</Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setDeleting(null)}>
              Cancel
            </Button>
            <Button color="red" loading={remove.isPending} onClick={() => void confirmDelete()}>
              Delete user
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
