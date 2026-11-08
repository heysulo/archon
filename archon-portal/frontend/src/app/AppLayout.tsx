import { ActionIcon, AppShell, Burger, Group, NavLink, Text, useComputedColorScheme, useMantineColorScheme } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { IconAdjustments, IconMoon, IconPlugConnected, IconServer2, IconSun, IconUsers } from "@tabler/icons-react";
import { Link, Outlet, useLocation } from "react-router";
import { ProcessStreamProvider } from "../api/processStream";
import { useAuth } from "../auth/AuthProvider";
import { PERMISSIONS, type PermissionKey } from "../auth/permissions";
import { AccountMenu } from "./AccountMenu";
import { LiveStatus } from "./LiveStatus";

/**
 * Every page, in the navigation, with the permission it needs. A new feature adds a route in router.tsx and an
 * entry here; users only see entries they are allowed to open.
 */
const PAGES: { path: string; label: string; icon: typeof IconServer2; permission: PermissionKey }[] = [
  { path: "/processes", label: "Processes", icon: IconServer2, permission: PERMISSIONS.processesView },
  { path: "/services", label: "Services", icon: IconPlugConnected, permission: PERMISSIONS.servicesView },
  { path: "/configs", label: "Configurations", icon: IconAdjustments, permission: PERMISSIONS.configsView },
  { path: "/users", label: "Users", icon: IconUsers, permission: PERMISSIONS.usersManage },
];

function ColorSchemeToggle() {
  const { setColorScheme } = useMantineColorScheme();
  const computed = useComputedColorScheme("light", { getInitialValueInEffect: true });
  const dark = computed === "dark";
  return (
    <ActionIcon
      variant="default"
      size="lg"
      aria-label={dark ? "Switch to light mode" : "Switch to dark mode"}
      onClick={() => setColorScheme(dark ? "light" : "dark")}
    >
      {dark ? <IconSun size={18} /> : <IconMoon size={18} />}
    </ActionIcon>
  );
}

export function AppLayout() {
  const { can } = useAuth();
  const viewsProcesses = can(PERMISSIONS.processesView);
  // The live process stream needs a session allowed to see processes; open it only then
  return viewsProcesses ? (
    <ProcessStreamProvider>
      <Shell showLiveStatus />
    </ProcessStreamProvider>
  ) : (
    <Shell showLiveStatus={false} />
  );
}

function Shell({ showLiveStatus }: { showLiveStatus: boolean }) {
  const { can } = useAuth();
  const [opened, { toggle, close }] = useDisclosure();
  const location = useLocation();
  const pages = PAGES.filter((page) => can(page.permission));

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 220, breakpoint: "sm", collapsed: { mobile: !opened } }}
      padding="md"
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Group gap="sm">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="Toggle navigation" />
            <Group gap={8}>
              <img src="/favicon.svg" alt="" width={24} height={24} />
              <Text fw={700}>Archon</Text>
              <Text c="dimmed">Portal</Text>
            </Group>
          </Group>
          <Group gap="sm">
            {showLiveStatus && <LiveStatus />}
            <ColorSchemeToggle />
            <AccountMenu />
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        {pages.map(({ path, label, icon: Icon }) => (
          <NavLink
            key={path}
            component={Link}
            to={path}
            label={label}
            leftSection={<Icon size={18} stroke={1.6} />}
            active={location.pathname.startsWith(path)}
            onClick={close}
          />
        ))}
      </AppShell.Navbar>

      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}
