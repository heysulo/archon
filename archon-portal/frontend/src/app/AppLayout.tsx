import { AppShell, Box, Burger, Group, NavLink, Text } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { IconAdjustments, IconApps, IconPlugConnected, IconServer2, IconShieldLock, IconUsers }
  from "@tabler/icons-react";
import { Link, Outlet, useLocation } from "react-router";
import { ProcessStreamProvider } from "../api/processStream";
import { useAuth } from "../auth/AuthProvider";
import { PERMISSIONS, type PermissionKey } from "../auth/permissions";
import { AccountMenu } from "./AccountMenu";
import { LiveStatus } from "./LiveStatus";

/** The navigation's headings: what runs, apart from who may use the portal. */
const SECTIONS = ["Fleet", "Access"] as const;
type Section = (typeof SECTIONS)[number];

/**
 * Every page, in the navigation, with the permission it needs. A new feature adds a route in router.tsx and an
 * entry here; users only see entries they are allowed to open.
 */
const PAGES: { path: string; label: string; icon: typeof IconServer2; permission: PermissionKey; section: Section }[]
  = [
  { path: "/processes", label: "Processes", icon: IconServer2, permission: PERMISSIONS.processesView, section: "Fleet" },
  { path: "/applications", label: "Applications", icon: IconApps, permission: PERMISSIONS.applicationsView, section: "Fleet" },
  { path: "/services", label: "Services", icon: IconPlugConnected, permission: PERMISSIONS.servicesView, section: "Fleet" },
  { path: "/configs", label: "Configurations", icon: IconAdjustments, permission: PERMISSIONS.configsView, section: "Fleet" },
  { path: "/users", label: "Users", icon: IconUsers, permission: PERMISSIONS.usersView, section: "Access" },
  { path: "/roles", label: "Roles", icon: IconShieldLock, permission: PERMISSIONS.rolesView, section: "Access" },
];

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
        {/* Never wraps: a second row would spill out of the header and over the page */}
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="Toggle navigation" />
            <Group gap={8} wrap="nowrap">
              <img src="/favicon.svg" alt="" width={24} height={24} />
              <Text fw={700}>Archon</Text>
              <Text c="dimmed" visibleFrom="xs">Portal</Text>
            </Group>
          </Group>
          <Group gap="sm" wrap="nowrap">
            {showLiveStatus && <LiveStatus />}
            <AccountMenu />
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        {SECTIONS.filter((section) => pages.some((page) => page.section === section)).map((section) => (
          <Box key={section} mb="sm">
            <Text size="xs" fw={600} c="dimmed" px="sm" py={6}>
              {section}
            </Text>
            {pages.filter((page) => page.section === section).map(({ path, label, icon: Icon }) => (
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
          </Box>
        ))}
      </AppShell.Navbar>

      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}
