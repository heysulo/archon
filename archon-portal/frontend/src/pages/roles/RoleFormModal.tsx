import { Alert, Button, Center, Checkbox, Group, Modal, Stack, Table, Text, TextInput, Tooltip }
  from "@mantine/core";
import { useForm } from "@mantine/form";
import { useState } from "react";
import type { PermissionInfo, Role } from "../users/usersApi";
import { mayGrant, permissionGrid, roleIdError, toggle } from "./model";
import type { RoleInput } from "./rolesApi";

interface Props {
  opened: boolean;
  /** The role being edited, or null to add one. */
  role: Role | null;
  permissions: PermissionInfo[];
  /** The signed-in user's permissions: only these can be granted. */
  myPermissions: string[];
  onClose: () => void;
  onSubmit: (input: RoleInput) => Promise<void>;
}

export function RoleFormModal({ opened, role, permissions, myPermissions, onClose, onSubmit }: Props) {
  return (
    <Modal opened={opened} onClose={onClose} size="lg" title={role ? `Edit ${role.id}` : "Add role"}>
      {/* Mounted afresh each time, so the form starts from the role being edited */}
      <RoleForm key={role?.id ?? "new"} role={role} permissions={permissions} myPermissions={myPermissions}
                onClose={onClose} onSubmit={onSubmit} />
    </Modal>
  );
}

interface TableProps {
  permissions: PermissionInfo[];
  myPermissions: string[];
  selected: string[];
  onChange: (permissions: string[]) => void;
}

/** Features as rows, actions as columns, so new features add a row rather than a list of boxes. */
function PermissionTable({ permissions, myPermissions, selected, onChange }: TableProps) {
  const grid = permissionGrid(permissions);
  const available = permissions.map((permission) => permission.key);
  return (
    <Table withTableBorder withColumnBorders verticalSpacing={6} aria-label="Permissions">
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Feature</Table.Th>
          {grid.actions.map((action) => (
            <Table.Th key={action.key} ta="center" w={96}>
              {action.label}
            </Table.Th>
          ))}
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {grid.rows.map((row) => (
          <Table.Tr key={row.feature}>
            <Table.Td>
              <Text size="sm">{row.label}</Text>
            </Table.Td>
            {grid.actions.map((action) => {
              const permission = row.permissions.get(action.key);
              if (!permission) {
                return (
                  <Table.Td key={action.key} ta="center">
                    <Text size="sm" c="dimmed" aria-hidden>
                      –
                    </Text>
                  </Table.Td>
                );
              }
              const allowed = mayGrant(myPermissions, permission.key);
              return (
                <Table.Td key={action.key}>
                  <Tooltip label={allowed ? permission.description : "You do not hold this permission"}>
                    <Center>
                      <Checkbox
                        aria-label={`${row.label}: ${action.label}`}
                        checked={selected.includes(permission.key)}
                        disabled={!allowed}
                        onChange={(event) => onChange(toggle(selected, permission.key, event.currentTarget.checked,
                          available))}
                      />
                    </Center>
                  </Tooltip>
                </Table.Td>
              );
            })}
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function RoleForm({ role, permissions, myPermissions, onClose, onSubmit }: Omit<Props, "opened">) {
  const creating = role === null;
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useForm<RoleInput>({
    initialValues: { id: role?.id ?? "", description: role?.description ?? "", permissions: role?.permissions ?? [] },
    validate: {
      id: roleIdError,
      description: (value) => (value.trim().length <= 255 ? null : "At most 255 characters"),
      permissions: (value) => (value.length ? null : "Pick at least one permission"),
    },
  });

  const submit = form.onSubmit(async (values) => {
    setBusy(true);
    setError(null);
    try {
      await onSubmit({ id: values.id.trim(), description: values.description.trim(), permissions: values.permissions });
      onClose();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not save the role");
    } finally {
      setBusy(false);
    }
  });

  return (
    <form onSubmit={submit} noValidate>
      <Stack>
        {error && (
          <Alert color="red" variant="light">
            {error}
          </Alert>
        )}
        <TextInput label="Id" description="How users are given the role. Cannot change later." disabled={!creating}
                   placeholder="operator" data-autofocus={creating || undefined} {...form.getInputProps("id")} />
        <TextInput label="Description" placeholder="What the role is for" {...form.getInputProps("description")} />
        <div>
          <Text size="sm" fw={500}>
            Permissions
          </Text>
          <Text size="xs" c="dimmed" mb={6}>
            What users holding the role may do. Modifying something includes viewing it. You can grant only what you
            hold yourself.
          </Text>
          <PermissionTable permissions={permissions} myPermissions={myPermissions}
                           selected={form.getValues().permissions}
                           onChange={(next) => form.setFieldValue("permissions", next)} />
          {form.errors.permissions && (
            <Text size="xs" c="red" mt={4}>
              {form.errors.permissions}
            </Text>
          )}
        </div>
        {!creating && (
          <Text size="xs" c="dimmed">
            Users holding the role get the new permissions on their next request.
          </Text>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={busy}>
            {creating ? "Add role" : "Save"}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
