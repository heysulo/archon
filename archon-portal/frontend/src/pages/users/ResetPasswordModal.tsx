import { Alert, Button, Group, Modal, PasswordInput, Stack, Text } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useState } from "react";
import { passwordError } from "../auth/passwordRules";
import type { User } from "./usersApi";

interface Props {
  user: User | null;
  onClose: () => void;
  onSubmit: (password: string) => Promise<void>;
}

export function ResetPasswordModal({ user, onClose, onSubmit }: Props) {
  return (
    <Modal opened={user !== null} onClose={onClose} title={`Reset password of ${user?.username ?? ""}`}>
      {/* Mounted afresh each time, so no earlier password lingers in the form */}
      <ResetPasswordForm key={user?.id ?? "none"} onClose={onClose} onSubmit={onSubmit} />
    </Modal>
  );
}

function ResetPasswordForm({ onClose, onSubmit }: Omit<Props, "user">) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useForm({
    initialValues: { password: "", confirm: "" },
    validate: {
      password: passwordError,
      confirm: (value, values) => (value === values.password ? null : "The passwords differ"),
    },
  });

  const submit = form.onSubmit(async ({ password }) => {
    setBusy(true);
    setError(null);
    try {
      await onSubmit(password);
      onClose();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not reset the password");
    } finally {
      setBusy(false);
    }
  });

  return (
    <form onSubmit={submit} noValidate>
      <Stack>
        <Text size="sm" c="dimmed">
          The user is signed out everywhere and signs in with the new password.
        </Text>
        {error && (
          <Alert color="red" variant="light">
            {error}
          </Alert>
        )}
        <PasswordInput label="New password" autoComplete="new-password" data-autofocus
                       {...form.getInputProps("password")} />
        <PasswordInput label="Repeat new password" autoComplete="new-password" {...form.getInputProps("confirm")} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={busy}>
            Reset password
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
