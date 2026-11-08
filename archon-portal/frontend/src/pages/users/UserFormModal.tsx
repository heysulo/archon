import { Alert, Button, Group, Modal, MultiSelect, PasswordInput, Stack, Switch, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useState } from "react";
import { passwordError, usernameError } from "../auth/passwordRules";
import type { Role, User } from "./usersApi";

export interface UserFormValues {
  username: string;
  displayName: string;
  password: string;
  confirm: string;
  enabled: boolean;
  roles: string[];
}

interface Props {
  opened: boolean;
  /** The user being edited, or null to create one. */
  user: User | null;
  /** Whether the user being edited is the signed-in user, who cannot disable themselves. */
  isSelf: boolean;
  roles: Role[];
  onClose: () => void;
  onSubmit: (values: UserFormValues) => Promise<void>;
}

export function UserFormModal({ opened, user, onClose, ...rest }: Props) {
  return (
    <Modal opened={opened} onClose={onClose} title={user ? `Edit ${user.username}` : "Add user"}>
      {/* Mounted afresh each time the dialog opens, so the form starts from the user being edited */}
      <UserForm key={user?.id ?? "new"} user={user} onClose={onClose} {...rest} />
    </Modal>
  );
}

function UserForm({ user, isSelf, roles, onClose, onSubmit }: Omit<Props, "opened">) {
  const creating = user === null;
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useForm<UserFormValues>({
    initialValues: {
      username: user?.username ?? "",
      displayName: user?.displayName ?? "",
      password: "",
      confirm: "",
      enabled: user?.enabled ?? true,
      roles: user?.roles ?? [],
    },
    validate: {
      username: (value) => (creating ? usernameError(value) : null),
      password: (value) => (creating ? passwordError(value) : null),
      confirm: (value, values) => (creating && value !== values.password ? "The passwords differ" : null),
      roles: (value) => (value.length ? null : "Pick at least one role"),
    },
  });

  const submit = form.onSubmit(async (values) => {
    setBusy(true);
    setError(null);
    try {
      await onSubmit(values);
      onClose();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not save the user");
    } finally {
      setBusy(false);
    }
  });

  const roleOptions = roles.map((role) => ({ value: role.id, label: `${role.id} – ${role.description}` }));

  return (
    <form onSubmit={submit} noValidate>
      <Stack>
        {error && (
          <Alert color="red" variant="light">
            {error}
          </Alert>
        )}
        <TextInput label="Username" disabled={!creating} data-autofocus={creating || undefined} autoComplete="off"
                   {...form.getInputProps("username")} />
        <TextInput label="Name" placeholder="Shown in the portal" {...form.getInputProps("displayName")} />
        {creating && (
          <>
            <PasswordInput label="Password" autoComplete="new-password" {...form.getInputProps("password")} />
            <PasswordInput label="Repeat password" autoComplete="new-password" {...form.getInputProps("confirm")} />
          </>
        )}
        <MultiSelect label="Roles" data={roleOptions} {...form.getInputProps("roles")} />
        {!creating && (
          <Switch
            label="Enabled"
            description={isSelf ? "You cannot disable yourself" : "Disabled users cannot sign in"}
            disabled={isSelf}
            {...form.getInputProps("enabled", { type: "checkbox" })}
          />
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={busy}>
            {creating ? "Add user" : "Save"}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
