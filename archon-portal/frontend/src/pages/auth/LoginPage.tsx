import { Alert, Button, PasswordInput, Stack, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useState } from "react";
import { Navigate, useNavigate, useSearchParams } from "react-router";
import { useAuth } from "../../auth/AuthProvider";
import { AuthCard } from "./AuthCard";
import { safeNext } from "./safeNext";

export function LoginPage() {
  const { status, login } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useForm({
    initialValues: { username: "", password: "" },
    validate: {
      username: (value) => (value.trim() ? null : "Enter your username"),
      password: (value) => (value ? null : "Enter your password"),
    },
  });
  const next = safeNext(params.get("next"));

  if (status === "signedIn") return <Navigate to={next} replace />;
  if (status === "setup") return <Navigate to="/setup" replace />;

  const submit = form.onSubmit(async ({ username, password }) => {
    setBusy(true);
    setError(null);
    try {
      await login(username, password);
      navigate(next, { replace: true });
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not sign in");
      form.setFieldValue("password", "");
    } finally {
      setBusy(false);
    }
  });

  return (
    <AuthCard title="Sign in" subtitle="Use your Archon portal account">
      <form onSubmit={submit} noValidate>
        <Stack>
          {error && (
            <Alert color="red" variant="light">
              {error}
            </Alert>
          )}
          <TextInput label="Username" autoComplete="username" autoFocus {...form.getInputProps("username")} />
          <PasswordInput label="Password" autoComplete="current-password" {...form.getInputProps("password")} />
          <Button type="submit" loading={busy} fullWidth>
            Sign in
          </Button>
        </Stack>
      </form>
    </AuthCard>
  );
}
