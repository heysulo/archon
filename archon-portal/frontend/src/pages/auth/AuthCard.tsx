import { Center, Group, Paper, Stack, Text, Title } from "@mantine/core";
import type { ReactNode } from "react";

/** The centred card the sign-in and setup pages share. */
export function AuthCard({ title, subtitle, children }: { title: string; subtitle: string; children: ReactNode }) {
  return (
    <Center mih="100vh" p="md" bg="var(--mantine-color-body)">
      <Paper withBorder radius="md" p="xl" w="100%" maw={420}>
        <Stack gap="lg">
          <Group gap={8}>
            <img src="/favicon.svg" alt="" width={28} height={28} />
            <Text fw={700} size="lg">
              Archon
            </Text>
            <Text c="dimmed" size="lg">
              Portal
            </Text>
          </Group>
          <div>
            <Title order={3}>{title}</Title>
            <Text size="sm" c="dimmed">
              {subtitle}
            </Text>
          </div>
          {children}
        </Stack>
      </Paper>
    </Center>
  );
}
