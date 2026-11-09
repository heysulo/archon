import { ActionIcon, Alert, Badge, Button, Card, Center, Code, Group, Loader, Menu, Modal, Pagination,
  SegmentedControl, Stack, Table, Text, TextInput, Title, Tooltip } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { notifications } from "@mantine/notifications";
import { IconDots, IconLock, IconPencil, IconPlus, IconSearch, IconTrash } from "@tabler/icons-react";
import { useState } from "react";
import { useAuth } from "../../auth/AuthProvider";
import { PERMISSIONS } from "../../auth/permissions";
import { ConfigFormModal } from "./ConfigFormModal";
import { useConfigMutations, useConfigs, type Config, type ConfigInput, type ConfigLevel } from "./configsApi";
import { LEVEL_COLORS, LEVEL_LABELS, scopeOf } from "./model";

const PAGE_SIZE = 50;
// Long enough to wait for the next keystroke, short enough to feel immediate
const SEARCH_DELAY_MS = 250;


export function ConfigsPage() {
  const { can } = useAuth();
  const manage = can(PERMISSIONS.configsManage);
  const { create, update, remove } = useConfigMutations();
  const [query, setQuery] = useState("");
  const [level, setLevel] = useState<"ALL" | ConfigLevel>("ALL");
  const [page, setPage] = useState(1);
  const [debouncedQuery] = useDebouncedValue(query, SEARCH_DELAY_MS);
  const configs = useConfigs({
    query: debouncedQuery,
    level: level === "ALL" ? null : level,
    page,
    pageSize: PAGE_SIZE,
  });
  // undefined: closed; null: adding; a configuration: editing it
  const [editing, setEditing] = useState<Config | null | undefined>(undefined);
  const [deleting, setDeleting] = useState<Config | null>(null);

  const shown = configs.data?.items ?? [];
  const total = configs.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const firstShown = (page - 1) * PAGE_SIZE + 1;

  const save = async (input: ConfigInput) => {
    if (editing === null) {
      await create.mutateAsync(input);
      notifications.show({ color: "green", message: `Added ${input.name}` });
    } else if (editing) {
      await update.mutateAsync({ id: editing.id, config: input });
      notifications.show({ color: "green", message: `Saved ${editing.name}` });
    }
  };

  const confirmDelete = async () => {
    if (!deleting) return;
    try {
      await remove.mutateAsync(deleting.id);
      notifications.show({ color: "green", message: `Deleted ${deleting.name}` });
      setDeleting(null);
    } catch (failure) {
      notifications.show({ color: "red", message: failure instanceof Error ? failure.message : "Delete failed" });
    }
  };

  return (
    <Stack gap="md">
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={2}>Configurations</Title>
          <Text size="sm" c="dimmed">
            Served to applications through the SDK. The most specific level wins: application, group, then global.
          </Text>
        </div>
        {manage && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing(null)}>
            Add configuration
          </Button>
        )}
      </Group>

      <Group>
        <TextInput
          style={{ flex: 1, minWidth: 220 }}
          placeholder="Search by name, group, application, value or description"
          leftSection={<IconSearch size={16} />}
          value={query}
          onChange={(event) => {
            setQuery(event.currentTarget.value);
            setPage(1);
          }}
          aria-label="Search configurations"
        />
        <SegmentedControl
          value={level}
          onChange={(value) => {
            setLevel(value as "ALL" | ConfigLevel);
            setPage(1);
          }}
          data={[{ value: "ALL", label: "All" }, ...(Object.keys(LEVEL_LABELS) as ConfigLevel[]).map((value) => ({
            value,
            label: LEVEL_LABELS[value],
          }))]}
        />
      </Group>

      {configs.isError && (
        <Alert color="red" variant="light">
          {configs.error.message}
        </Alert>
      )}

      {configs.isPending ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : shown.length === 0 ? (
        <Center py="xl">
          <Text c="dimmed">{debouncedQuery || level !== "ALL" ? "No configurations match" : "No configurations yet"}</Text>
        </Center>
      ) : (
        <Card withBorder padding={0} radius="md">
          <div style={{ overflowX: "auto" }}>
            <Table verticalSpacing="sm" highlightOnHover miw={900}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Level</Table.Th>
                  <Table.Th>Seen by</Table.Th>
                  <Table.Th>Name</Table.Th>
                  <Table.Th>Type</Table.Th>
                  <Table.Th>Value</Table.Th>
                  <Table.Th>Changed</Table.Th>
                  {manage && <Table.Th />}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {shown.map((config) => (
                  <Table.Tr key={config.id}>
                    <Table.Td>
                      <Badge variant="light" color={LEVEL_COLORS[config.level]} radius="sm">
                        {LEVEL_LABELS[config.level]}
                      </Badge>
                    </Table.Td>
                    <Table.Td style={{ whiteSpace: "nowrap" }}>{scopeOf(config)}</Table.Td>
                    <Table.Td>
                      <Group gap={6} wrap="nowrap">
                        <Text size="sm" ff="monospace">
                          {config.name}
                        </Text>
                        {config.readOnly && (
                          <Tooltip label="Read only: applications cannot change it">
                            <IconLock size={14} aria-label="Read only" />
                          </Tooltip>
                        )}
                      </Group>
                      {config.description && (
                        <Text size="xs" c="dimmed">
                          {config.description}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Text size="xs" c="dimmed">
                        {config.type}
                      </Text>
                    </Table.Td>
                    <Table.Td maw={320}>
                      <Code style={{ whiteSpace: "pre-wrap", wordBreak: "break-word" }}>{config.value}</Code>
                    </Table.Td>
                    <Table.Td style={{ whiteSpace: "nowrap" }}>
                      <Text size="sm">{config.updatedAt ? new Date(config.updatedAt).toLocaleString() : "–"}</Text>
                      <Text size="xs" c="dimmed">
                        {config.updatedBy}
                      </Text>
                    </Table.Td>
                    {manage && (
                      <Table.Td>
                        <Menu position="bottom-end">
                          <Menu.Target>
                            <ActionIcon variant="subtle" color="gray" aria-label={`Actions for ${config.name} (${scopeOf(config)})`}>
                              <IconDots size={16} />
                            </ActionIcon>
                          </Menu.Target>
                          <Menu.Dropdown>
                            <Menu.Item leftSection={<IconPencil size={16} />} onClick={() => setEditing(config)}>
                              Edit
                            </Menu.Item>
                            <Menu.Item color="red" leftSection={<IconTrash size={16} />}
                                       onClick={() => setDeleting(config)}>
                              Delete
                            </Menu.Item>
                          </Menu.Dropdown>
                        </Menu>
                      </Table.Td>
                    )}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </div>
        </Card>
      )}

      {total > 0 && (
        <Group justify="space-between">
          <Text size="sm" c="dimmed">
            {firstShown.toLocaleString()}–{(firstShown + shown.length - 1).toLocaleString()} of{" "}
            {total.toLocaleString()}
          </Text>
          {pages > 1 && <Pagination total={pages} value={page} onChange={setPage} size="sm" />}
        </Group>
      )}

      <ConfigFormModal opened={editing !== undefined} config={editing ?? null} onClose={() => setEditing(undefined)}
                       onSubmit={save} />
      <Modal opened={deleting !== null} onClose={() => setDeleting(null)} title={deleting ? `Delete ${deleting.name} for ${scopeOf(deleting)}?` : ""}>
        <Stack>
          <Text size="sm">
            Applications that see it are told at once, and fall back to a less specific configuration with the same
            name if there is one.
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setDeleting(null)}>
              Cancel
            </Button>
            <Button color="red" loading={remove.isPending} onClick={() => void confirmDelete()}>
              Delete configuration
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
