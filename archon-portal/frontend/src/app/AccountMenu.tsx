import { Alert, Avatar, Button, Group, Menu, Modal, PasswordInput, Stack, Text, UnstyledButton, useComputedColorScheme,
  useMantineColorScheme } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useDisclosure } from "@mantine/hooks";
import { notifications } from "@mantine/notifications";
import { IconKey, IconLogout, IconMoon, IconSun } from "@tabler/icons-react";
import { useState } from "react";
import { useNavigate } from "react-router";
import { api } from "../api/http";
import { useAuth } from "../auth/AuthProvider";
import { passwordError } from "../pages/auth/passwordRules";

function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
}

function ChangePasswordModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useForm({
    initialValues: { currentPassword: "", newPassword: "", confirm: "" },
    validate: {
      currentPassword: (value) => (value ? null : "Enter your current password"),
      newPassword: passwordError,
      confirm: (value, values) => (value === values.newPassword ? null : "The passwords differ"),
    },
  });
  const close = () => {
    form.reset();
    setError(null);
    onClose();
  };
  const submit = form.onSubmit(async ({ currentPassword, newPassword }) => {
    setBusy(true);
    setError(null);
    try {
      await api("/api/auth/password", { method: "POST", body: { currentPassword, newPassword } });
      notifications.show({ color: "green", message: "Password changed. Other sessions were signed out." });
      close();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not change the password");
    } finally {
      setBusy(false);
    }
  });
  return (
    <Modal opened={opened} onClose={close} title="Change password">
      <form onSubmit={submit} noValidate>
        <Stack>
          {error && (
            <Alert color="red" variant="light">
              {error}
            </Alert>
          )}
          <PasswordInput label="Current password" autoComplete="current-password" data-autofocus
                         {...form.getInputProps("currentPassword")} />
          <PasswordInput label="New password" autoComplete="new-password" {...form.getInputProps("newPassword")} />
          <PasswordInput label="Repeat new password" autoComplete="new-password" {...form.getInputProps("confirm")} />
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              Cancel
            </Button>
            <Button type="submit" loading={busy}>
              Change password
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

/** Who is signed in, with the colour scheme, changing the password and signing out. */
export function AccountMenu() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [passwordOpened, password] = useDisclosure(false);
  const { setColorScheme } = useMantineColorScheme();
  const dark = useComputedColorScheme("light", { getInitialValueInEffect: true }) === "dark";
  if (!user) return null;
  const signOut = async () => {
    try {
      await logout();
    } finally {
      navigate("/login", { replace: true });
    }
  };
  return (
    <>
      <Menu position="bottom-end" width={220}>
        <Menu.Target>
          <UnstyledButton aria-label="Account">
            <Group gap={8} wrap="nowrap">
              <Avatar size={32} radius="xl" color="indigo">
                {initials(user.displayName || user.username)}
              </Avatar>
              <Text size="sm" visibleFrom="sm">
                {user.displayName}
              </Text>
            </Group>
          </UnstyledButton>
        </Menu.Target>
        <Menu.Dropdown>
          <Menu.Label>Signed in as {user.username}</Menu.Label>
          <Menu.Item leftSection={dark ? <IconSun size={16} /> : <IconMoon size={16} />}
                     onClick={() => setColorScheme(dark ? "light" : "dark")}>
            {dark ? "Switch to light mode" : "Switch to dark mode"}
          </Menu.Item>
          <Menu.Item leftSection={<IconKey size={16} />} onClick={password.open}>
            Change password
          </Menu.Item>
          <Menu.Item leftSection={<IconLogout size={16} />} onClick={() => void signOut()}>
            Sign out
          </Menu.Item>
        </Menu.Dropdown>
      </Menu>
      <ChangePasswordModal opened={passwordOpened} onClose={password.close} />
    </>
  );
}
