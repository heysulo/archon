import { ActionIcon, Alert, Badge, Box, Button, Card, Center, Code, Group, Loader, MultiSelect, Paper, Select,
  SimpleGrid, Stack, Switch, Table, Text, TextInput, Title, Tooltip, UnstyledButton } from "@mantine/core";
import { useLocalStorage } from "@mantine/hooks";
import { IconAlertTriangle, IconChevronDown, IconChevronUp, IconFileText, IconSearch, IconSelector }
  from "@tabler/icons-react";
import { useAuth } from "../../auth/AuthProvider";
import { PERMISSIONS } from "../../auth/permissions";
import { ProcessDetails } from "./details/ProcessDetails";
import { LogViewer } from "./logs/LogViewer";
import { useMemo, useState, type ReactNode } from "react";
import { useProcessStream } from "../../api/processStream";
import type { ProcessInfo } from "../../api/types";
import { applicationOf, DEFAULT_SORT, filterProcesses, isSecure, roleOf, serviceLabel, sortProcesses, stateTone, summarize,
  type LinkFilter, type Sort, type SortColumn } from "./model";

function Stat({ label, value }: { label: string; value: number | string }) {
  return (
    <Paper withBorder radius="md" p="md">
      <Text fz={26} fw={600} style={{ fontVariantNumeric: "tabular-nums" }}>
        {value}
      </Text>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
    </Paper>
  );
}

function Detail({ value }: { value: string | undefined }) {
  return value ? (
    <Text size="xs" ff="monospace">
      {value}
    </Text>
  ) : (
    <Text size="sm" c="dimmed">
      –
    </Text>
  );
}

function SortableHeader({ column, sort, onSort, children }: {
  column: SortColumn;
  sort: Sort;
  onSort: (column: SortColumn) => void;
  children: ReactNode;
}) {
  const active = sort.column === column;
  const Icon = !active ? IconSelector : sort.descending ? IconChevronDown : IconChevronUp;
  return (
    <Table.Th aria-sort={active ? (sort.descending ? "descending" : "ascending") : "none"}>
      <UnstyledButton onClick={() => onSort(column)} aria-label={`Sort by ${String(children).toLowerCase()}`}>
        <Group gap={4} wrap="nowrap">
          <Text size="sm" fw={600}>
            {children}
          </Text>
          <Icon size={14} stroke={1.5} />
        </Group>
      </UnstyledButton>
    </Table.Th>
  );
}

function Row({ process, showNode, onOpen, onLogs }: {
  process: ProcessInfo;
  showNode: boolean;
  onOpen: () => void;
  onLogs?: () => void;
}) {
  const role = roleOf(process);
  return (
    <Table.Tr onClick={onOpen} style={{ cursor: "pointer", ...(role.former ? { opacity: 0.55 } : {}) }}>
      <Table.Td>
        <Text size="sm" ff="monospace" fw={500}>
          {applicationOf(process)}
        </Text>
      </Table.Td>
      <Table.Td>
        <Text size="sm" ff="monospace">
          {process.rank}
        </Text>
      </Table.Td>
      <Table.Td>
        <Text size="sm" fw={role.lead ? 600 : 400} c={role.lead ? "indigo" : role.former ? "dimmed" : undefined}>
          {role.label}
        </Text>
      </Table.Td>
      {/* Wide enough for the longest state, so the badge never truncates */}
      <Table.Td miw={120}>
        <Badge variant="light" color={stateTone(process)} radius="sm" style={{ overflow: "visible" }}>
          {process.runLevel}
        </Badge>
      </Table.Td>
      <Table.Td>
        <Detail value={process.details?.host} />
      </Table.Td>
      <Table.Td>
        <Detail value={process.details?.address} />
      </Table.Td>
      {showNode && (
        <Table.Td>
          <Detail value={process.details?.node} />
        </Table.Td>
      )}
      <Table.Td>
        <Group gap={6} wrap="nowrap">
          <Box w={8} h={8} bg={process.connected ? "green.6" : "red.6"} style={{ borderRadius: "50%" }} />
          <Text size="sm">{process.connected ? "Connected" : "Disconnected"}</Text>
        </Group>
      </Table.Td>
      <Table.Td>
        {process.services.length ? (
          <Group gap={4} wrap="nowrap">
            {process.services.map((service) => {
              const secure = isSecure(process, service);
              return (
                <Tooltip key={service} label={`${service} · ${secure ? "TLS" : "Not encrypted"}`}>
                  <Code color={secure ? "green.1" : "red.1"} c={secure ? "green.9" : "red.9"}
                        aria-label={`${serviceLabel(service, process.details?.address)}, ${secure ? "TLS"
                          : "not encrypted"}`}>
                    {serviceLabel(service, process.details?.address)}
                  </Code>
                </Tooltip>
              );
            })}
          </Group>
        ) : (
          <Text size="sm" c="dimmed">
            –
          </Text>
        )}
      </Table.Td>
      {onLogs !== undefined && (
        <Table.Td>
          {process.running && process.connected && (
            <Tooltip label="Watch logs live">
              <ActionIcon variant="subtle" color="gray" onClick={(event) => {
                // The row opens the details; this opens the logs alone
                event.stopPropagation();
                onLogs();
              }}
                          aria-label={`Logs of ${applicationOf(process)} #${process.rank}`}>
                <IconFileText size={16} />
              </ActionIcon>
            </Tooltip>
          )}
        </Table.Td>
      )}
    </Table.Tr>
  );
}

const LINKS: { value: LinkFilter; label: string }[] = [
  { value: "any", label: "Any registry link" },
  { value: "connected", label: "Connected" },
  { value: "disconnected", label: "Disconnected" },
];

export function ProcessesPage() {
  const { snapshot, status, error } = useProcessStream();
  const { can } = useAuth();
  const seesLogs = can(PERMISSIONS.logsView);
  const [watching, setWatching] = useState<ProcessInfo | null>(null);
  // Held by identity, so the drawer follows the instance as new snapshots arrive
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [applications, setApplications] = useState<string[]>([]);
  const [states, setStates] = useState<string[]>([]);
  const [link, setLink] = useState<LinkFilter>("any");
  const [showStopped, setShowStopped] = useLocalStorage({ key: "archon.processes.showStopped", defaultValue: false });
  const [sort, setSort] = useLocalStorage<Sort>({ key: "archon.processes.sort", defaultValue: DEFAULT_SORT });

  const processes = useMemo(() => snapshot?.processes ?? [], [snapshot]);
  const summary = useMemo(() => summarize(processes), [processes]);
  const applicationChoices = useMemo(() => [...new Set(processes.map(applicationOf))].sort(), [processes]);
  const stateChoices = useMemo(() => [...new Set(processes.map((process) => process.runLevel))].sort(), [processes]);
  const rows = useMemo(
    () => sortProcesses(filterProcesses(processes, { query, showStopped, applications, states, link }), sort),
    [processes, query, showStopped, applications, states, link, sort],
  );
  const showNode = rows.some((process) => process.details?.node);
  const keyOf = (process: ProcessInfo) => `${applicationOf(process)}#${process.rank}#${process.details?.host ?? ""}`;
  const selected = processes.find((process) => keyOf(process) === selectedKey) ?? null;
  const filtered = query.trim() !== "" || applications.length > 0 || states.length > 0 || link !== "any";

  // A new column sorts ascending; the same column again flips it
  const onSort = (column: SortColumn) =>
    setSort((current) => ({ column, descending: current.column === column ? !current.descending : false }));
  const clearFilters = () => {
    setQuery("");
    setApplications([]);
    setStates([]);
    setLink("any");
  };
  const header = (column: SortColumn, label: string) => (
    <SortableHeader column={column} sort={sort} onSort={onSort}>
      {label}
    </SortableHeader>
  );

  return (
    <Stack gap="md">
      <div>
        <Title order={2}>Processes</Title>
        <Text size="sm" c="dimmed">
          {snapshot
            ? `Primary registry ${snapshot.registry} · term ${snapshot.term} · changed ${new Date(
                snapshot.generatedAt,
              ).toLocaleTimeString()}`
            : "Waiting for the registry"}
        </Text>
      </div>

      {error && status !== "live" && (
        <Alert color="red" variant="light" icon={<IconAlertTriangle size={18} />}>
          {error}
          {snapshot ? ". Showing the last known state." : "."}
        </Alert>
      )}

      <SimpleGrid cols={{ base: 1, xs: 3 }}>
        <Stat label="Running processes" value={snapshot ? summary.running : "–"} />
        <Stat label="Applications" value={snapshot ? summary.applications : "–"} />
        <Stat label="Services served" value={snapshot ? summary.services : "–"} />
      </SimpleGrid>

      <Group align="flex-end" gap="sm">
        <TextInput
          style={{ flex: 2, minWidth: 220 }}
          placeholder="Search by name, pod, address, node or service"
          leftSection={<IconSearch size={16} />}
          value={query}
          onChange={(event) => setQuery(event.currentTarget.value)}
          aria-label="Search processes"
        />
        <MultiSelect
          style={{ flex: 1, minWidth: 200 }}
          placeholder={applications.length ? undefined : "All applications"}
          data={applicationChoices}
          value={applications}
          onChange={setApplications}
          searchable
          clearable
          aria-label="Filter by application"
        />
        <MultiSelect
          style={{ flex: 1, minWidth: 160 }}
          placeholder={states.length ? undefined : "All states"}
          data={stateChoices}
          value={states}
          onChange={setStates}
          clearable
          aria-label="Filter by state"
        />
        <Select
          style={{ minWidth: 170 }}
          data={LINKS}
          value={link}
          onChange={(value) => setLink((value as LinkFilter | null) ?? "any")}
          allowDeselect={false}
          aria-label="Filter by registry link"
        />
        <Switch
          label="Show stopped"
          checked={showStopped}
          onChange={(event) => setShowStopped(event.currentTarget.checked)}
          mb={8}
        />
      </Group>

      {!snapshot ? (
        <Center py="xl">{status === "connecting" ? <Loader /> : <Text c="dimmed">No data yet</Text>}</Center>
      ) : rows.length === 0 ? (
        <Center py="xl">
          <Stack align="center" gap="xs">
            <Text c="dimmed">{filtered ? "No processes match the filters" : "No running processes"}</Text>
            {filtered && (
              <Button variant="subtle" size="xs" onClick={clearFilters}>
                Clear filters
              </Button>
            )}
          </Stack>
        </Center>
      ) : (
        <Card withBorder padding={0} radius="md">
          {/* Narrow screens scroll the table sideways rather than wrapping pod names and squeezing badges */}
          <Box style={{ overflowX: "auto" }}>
            <Table verticalSpacing="xs" striped highlightOnHover miw={1000} style={{ whiteSpace: "nowrap" }}>
              <Table.Thead>
                <Table.Tr>
                  {header("application", "Application")}
                  {header("rank", "Rank")}
                  {header("role", "Role")}
                  {header("state", "State")}
                  {header("host", "Host")}
                  {header("address", "Address")}
                  {showNode && header("node", "Node")}
                  {header("link", "Registry link")}
                  {header("services", "Services")}
                  {seesLogs && <Table.Th aria-label="Logs" />}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((process) => (
                  <Row key={`${applicationOf(process)}-${process.rank}-${process.details?.host ?? ""}-${process.runLevel}`}
                       process={process} showNode={showNode} onOpen={() => setSelectedKey(keyOf(process))}
                       onLogs={seesLogs ? () => setWatching(process) : undefined} />
                ))}
              </Table.Tbody>
            </Table>
          </Box>
          <Text size="xs" c="dimmed" px="md" py="xs">
            {rows.length === 1 ? "1 process" : `${rows.length} processes`}
            {filtered || !showStopped ? ` shown of ${processes.length}` : ""}
          </Text>
        </Card>
      )}
      <ProcessDetails process={selected} onClose={() => setSelectedKey(null)}
                      onLogs={seesLogs ? (process) => {
                        setSelectedKey(null);
                        setWatching(process);
                      } : undefined} />
      <LogViewer process={watching} onClose={() => setWatching(null)} />
    </Stack>
  );
}
