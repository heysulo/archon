import { ActionIcon, AppShell, Burger, Group, NavLink, Text, useComputedColorScheme, useMantineColorScheme } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { IconMoon, IconServer2, IconSun } from "@tabler/icons-react";
import { Link, Outlet, useLocation } from "react-router";
import { LiveStatus } from "./LiveStatus";

/** Every page, in the navigation. A new feature adds a route in router.tsx and an entry here. */
const PAGES = [{ path: "/processes", label: "Processes", icon: IconServer2 }];

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
  const [opened, { toggle, close }] = useDisclosure();
  const location = useLocation();

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
            <LiveStatus />
            <ColorSchemeToggle />
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        {PAGES.map(({ path, label, icon: Icon }) => (
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
