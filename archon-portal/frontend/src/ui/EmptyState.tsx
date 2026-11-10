import { Center, Stack, Text, ThemeIcon } from "@mantine/core";
import type { Icon } from "@tabler/icons-react";
import type { ReactNode } from "react";

/** What a list shows in place of rows: why it is empty, and the way out where there is one. */
export function EmptyState({ icon: Glyph, title, hint, action }: {
  icon: Icon;
  title: string;
  hint?: string;
  action?: ReactNode;
}) {
  return (
    <Center py={48}>
      <Stack align="center" gap="xs" maw={420}>
        <ThemeIcon variant="light" color="gray" size={44} radius="xl">
          <Glyph size={22} stroke={1.5} aria-hidden />
        </ThemeIcon>
        <Text fw={500}>{title}</Text>
        {hint && (
          <Text size="sm" c="dimmed" ta="center">
            {hint}
          </Text>
        )}
        {action}
      </Stack>
    </Center>
  );
}
