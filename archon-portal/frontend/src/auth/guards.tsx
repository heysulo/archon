import { Alert, Button, Center, Loader, Stack, Text } from "@mantine/core";
import type { ReactNode } from "react";
import { Navigate, Outlet, useLocation } from "react-router";
import { useAuth } from "./AuthProvider";
import type { PermissionKey } from "./permissions";

/** Lets signed-in users through; sends everyone else to sign in, or to setup while no users exist. */
export function RequireSignIn() {
  const { status, error, refresh } = useAuth();
  const location = useLocation();
  if (status === "loading") {
    return (
      <Center h="100vh">
        <Loader />
      </Center>
    );
  }
  if (status === "unavailable") {
    return (
      <Center h="100vh" p="md">
        <Stack align="center">
          <Text c="red">{error}</Text>
          <Button variant="light" onClick={() => void refresh()}>
            Try again
          </Button>
        </Stack>
      </Center>
    );
  }
  if (status === "setup") {
    return <Navigate to="/setup" replace />;
  }
  if (status === "signedOut") {
    const next = location.pathname + location.search;
    return <Navigate to={`/login?next=${encodeURIComponent(next)}`} replace />;
  }
  return <Outlet />;
}

/** Shows a page only to users with the permission it needs. */
export function RequirePermission({ permission, children }: { permission: PermissionKey; children: ReactNode }) {
  const { can } = useAuth();
  if (!can(permission)) {
    return (
      <Alert color="yellow" variant="light" title="Not allowed">
        You do not have permission to open this page. Ask an administrator for access.
      </Alert>
    );
  }
  return <>{children}</>;
}
