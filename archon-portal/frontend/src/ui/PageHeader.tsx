import { Group, Text, Title } from "@mantine/core";
import { useEffect, type ReactNode } from "react";

/** A page's title, a line saying what the page is for, and its main action. */
export function PageHeader({ title, description, action }: {
  title: string;
  description?: ReactNode;
  action?: ReactNode;
}) {
  // Names the browser tab after the page, so several portal tabs can be told apart
  useEffect(() => {
    document.title = `${title} · Archon Portal`;
  }, [title]);
  return (
    <Group justify="space-between" align="flex-end" gap="sm">
      <div style={{ minWidth: 0 }}>
        <Title order={2}>{title}</Title>
        {description && (
          <Text size="sm" c="dimmed" maw={720}>
            {description}
          </Text>
        )}
      </div>
      {action}
    </Group>
  );
}
