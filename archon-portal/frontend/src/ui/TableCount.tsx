import { Text } from "@mantine/core";

/** The line under a table saying how many rows it has, and of how many when a search hides some. */
export function TableCount({ shown, total, one, many }: { shown: number; total: number; one: string; many: string }) {
  return (
    <Text size="xs" c="dimmed" px="md" py="xs">
      {shown === total ? `${total} ${total === 1 ? one : many}` : `${shown} of ${total} ${many} shown`}
    </Text>
  );
}
