import { ActionIcon, Alert, Badge, Button, Card, Center, Group, Loader, Menu, Modal, Stack, Table, Text, Title }
  from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { IconDots, IconKey, IconPencil, IconPlus, IconTrash } from "@tabler/icons-react";
import { useState } from "react";
import { useAuth } from "../../auth/AuthProvider";
import { ResetPasswordModal } from "./ResetPasswordModal";
import { UserFormModal, type UserFormValues } from "./UserFormModal";
import { useRoles, useUserMutations, useUsers, type User } from "./usersApi";

function when(timestamp: string | undefined): string {
  return timestamp ? new Date(timestamp).toLocaleString() : "Never";
}

export function UsersPage() {
  const { user: me } = useAuth();
  const users = useUsers();
  const roles = useRoles();
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
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={2}>Users</Title>
          <Text size="sm" c="dimmed">
            Who can sign in to the portal, and with which roles
          </Text>
        </div>
        <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing(null)}>
          Add user
        </Button>
      </Group>

      {users.isError && (
        <Alert color="red" variant="light">
          {users.error.message}
        </Alert>
      )}

      {users.isPending ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : (
        <Card withBorder padding={0} radius="md">
          <div style={{ overflowX: "auto" }}>
            <Table verticalSpacing="sm" highlightOnHover miw={760} style={{ whiteSpace: "nowrap" }}>
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
                {(users.data ?? []).map((user) => {
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
                            <Badge key={role} variant="outline" radius="sm">
                              {role}
                            </Badge>
                          ))}
                        </Group>
                      </Table.Td>
                      <Table.Td>
                        <Badge variant="light" color={user.enabled ? "green" : "gray"} radius="sm">
                          {user.enabled ? "Enabled" : "Disabled"}
                        </Badge>
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm">{when(user.lastLoginAt)}</Text>
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm">{when(user.createdAt)}</Text>
                      </Table.Td>
                      <Table.Td>
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
                      </Table.Td>
                    </Table.Tr>
                  );
                })}
              </Table.Tbody>
            </Table>
          </div>
        </Card>
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
