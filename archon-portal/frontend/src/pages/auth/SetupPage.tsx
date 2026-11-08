import { Alert, Button, Code, PasswordInput, Stack, Text, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useState } from "react";
import { Navigate, useNavigate } from "react-router";
import { useAuth } from "../../auth/AuthProvider";
import { AuthCard } from "./AuthCard";
import { passwordError, usernameError } from "./passwordRules";

/** Creates the first admin. Only reachable while no users exist; the token comes from a portal pod's log. */
export function SetupPage() {
  const { status, setup } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useForm({
    initialValues: { token: "", username: "", displayName: "", password: "", confirm: "" },
    validate: {
      token: (value) => (value.trim() ? null : "Paste the setup token from the portal's log"),
      username: usernameError,
      password: passwordError,
      confirm: (value, values) => (value === values.password ? null : "The passwords differ"),
    },
  });

  if (status === "signedIn") return <Navigate to="/processes" replace />;
  if (status === "signedOut") return <Navigate to="/login" replace />;

  const submit = form.onSubmit(async ({ token, username, displayName, password }) => {
    setBusy(true);
    setError(null);
    try {
      await setup(token.trim(), username.trim(), displayName.trim(), password);
      navigate("/users", { replace: true });
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Setup failed");
    } finally {
      setBusy(false);
    }
  });

  return (
    <AuthCard title="Create the first admin" subtitle="No portal users exist yet">
      <Text size="sm">
        Each portal pod logs a one-time setup token while no users exist. Find it with:
      </Text>
      <Code block>kubectl logs deployment/archon-portal | grep "setup token"</Code>
      <form onSubmit={submit} noValidate>
        <Stack>
          {error && (
            <Alert color="red" variant="light">
              {error}
            </Alert>
          )}
          <TextInput label="Setup token" autoComplete="off" autoFocus {...form.getInputProps("token")} />
          <TextInput label="Username" autoComplete="username" {...form.getInputProps("username")} />
          <TextInput label="Name" placeholder="Shown in the portal" {...form.getInputProps("displayName")} />
          <PasswordInput label="Password" autoComplete="new-password" {...form.getInputProps("password")} />
          <PasswordInput label="Repeat password" autoComplete="new-password" {...form.getInputProps("confirm")} />
          <Button type="submit" loading={busy} fullWidth>
            Create admin and sign in
          </Button>
        </Stack>
      </form>
    </AuthCard>
  );
}
