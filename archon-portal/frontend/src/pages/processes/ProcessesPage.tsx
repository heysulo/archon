import { ActionIcon, Alert, Badge, Box, Button, Center, Group, Loader, MultiSelect, Paper, SimpleGrid, Stack,
  Switch, Table, Text, TextInput, Tooltip, UnstyledButton } from "@mantine/core";
import { useLocalStorage } from "@mantine/hooks";
import { IconAdjustmentsHorizontal, IconAlertTriangle, IconChevronDown, IconChevronRight, IconChevronUp,
  IconFileText, IconSearch, IconSelector, IconServer2 } from "@tabler/icons-react";
import { useMemo, useState, type ReactNode } from "react";
import { useSearchParams } from "react-router";
import { useProcessStream } from "../../api/processStream";
import type { ProcessInfo } from "../../api/types";
import { useAuth } from "../../auth/AuthProvider";
import { PERMISSIONS } from "../../auth/permissions";
import { EmptyState } from "../../ui/EmptyState";
import { PageHeader } from "../../ui/PageHeader";
import { ServiceChip } from "../../ui/ServiceChip";
import { TableCard } from "../../ui/TableCard";
import { TONE, type Tone } from "../../ui/tones";
import { ProcessDetails, type DetailTab } from "./details/ProcessDetails";
import { applicationOf, countIssues, DEFAULT_SORT, filterProcesses, groupByApplication, isSecure,
  NEAR_LIMIT_PERCENT, roleOf, serviceLabel, stateLabel, stateTone, summarize, type ApplicationGroup, type Issue,
  type Sort, type SortColumn } from "./model";
import { CpuCell, MemoryCell } from "./ResourceCells";

const ISSUE_TILES: { issue: Issue; label: string; hint: string; tone: Tone }[] = [
  { issue: "disconnected", label: "Disconnected", hint: "Lost their registry link", tone: TONE.danger },
  { issue: "notReady", label: "Not ready", hint: "Starting or stopping", tone: TONE.warn },
  { issue: "unencrypted", label: "Not encrypted", hint: "Serving without TLS", tone: TONE.danger },
  { issue: "nearLimit", label: "Near a limit", hint: `CPU or memory at ${NEAR_LIMIT_PERCENT}% or more`,
    tone: TONE.strained },
];

/** What a group's header says about each issue its instances have. */
const ISSUE_WORDS: Record<Issue, string> = {
  disconnected: "disconnected",
  notReady: "not ready",
  unencrypted: "not encrypted",
  nearLimit: "near a limit",
};

const figure = { fontVariantNumeric: "tabular-nums" } as const;

function Tile({ label, hint, value, accent, children }: {
  label: string;
  hint: string;
  value: number | string;
  /** A coloured edge, for a count that wants attention. */
  accent?: string;
  children?: ReactNode;
}) {
  return (
    <Paper withBorder radius="md" px="md" py="sm" h="100%"
           style={accent ? { borderLeft: `3px solid var(--mantine-color-${accent}-6)` } : undefined}>
      <Group justify="space-between" wrap="nowrap" align="flex-start" gap="xs">
        <div style={{ minWidth: 0 }}>
          <Text fz={24} fw={600} lh={1.2} style={figure}>
            {value}
          </Text>
          <Text size="sm" fw={500}>
            {label}
          </Text>
          <Text size="xs" c="dimmed">
            {hint}
          </Text>
        </div>
        {children}
      </Group>
    </Paper>
  );
}

/** A count of instances with an issue; pressing it shows only those. */
function IssueTile({ label, hint, count, tone, active, onToggle }: {
  label: string;
  hint: string;
  count: number | undefined;
  tone: Tone;
  active: boolean;
  onToggle: () => void;
}) {
  return (
    <UnstyledButton onClick={onToggle} aria-pressed={active} aria-label={`${label}: ${count ?? "unknown"}. ${
      active ? "Showing only these" : "Show only these"}`} disabled={!count && !active}
                    style={{ borderRadius: "var(--mantine-radius-md)", outline: active
                      ? "2px solid var(--mantine-primary-color-filled)" : undefined, cursor: count || active
                      ? "pointer" : "default" }}>
      <Tile label={label} hint={hint} value={count ?? "–"} accent={count ? tone : undefined}>
        {active && (
          <Badge size="sm" variant="filled">
            Filtering
          </Badge>
        )}
      </Tile>
    </UnstyledButton>
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
      <UnstyledButton onClick={() => onSort(column)} aria-label={`Sort by ${column}`} py={4}>
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

/** Quiet while all is well, so that what is not stands out. */
function State({ process }: { process: ProcessInfo }) {
  const healthy = process.runLevel === "RUNNING" && process.connected;
  return (
    <Group gap={6} wrap="nowrap">
      {healthy ? (
        <Group gap={6} wrap="nowrap">
          <Box w={8} h={8} bg={`${TONE.ok}.6`} style={{ borderRadius: "50%" }} aria-hidden />
          <Text size="sm">{stateLabel(process.runLevel)}</Text>
        </Group>
      ) : (
        <Badge color={stateTone(process)}>{stateLabel(process.runLevel)}</Badge>
      )}
      {process.running && !process.connected && <Badge color={TONE.danger}>Disconnected</Badge>}
    </Group>
  );
}

function Row({ process, onOpen, onLogs }: { process: ProcessInfo; onOpen: () => void; onLogs?: () => void }) {
  const role = roleOf(process);
  const name = `${applicationOf(process)} #${process.rank}`;
  return (
    <Table.Tr onClick={onOpen} style={{ cursor: "pointer" }}>
      <Table.Td pl="xl">
        {/* A real button, so the details open from the keyboard too; the click reaches the row */}
        <UnstyledButton aria-label={`Details of ${name}`}>
          <Text size="sm" ff="monospace" fw={500} c={role.former ? "dimmed" : undefined}>
            {process.details?.host ?? `#${process.rank}`}
          </Text>
        </UnstyledButton>
        {process.details?.address && (
          <Text size="xs" ff="monospace" c="dimmed">
            {process.details.address}
          </Text>
        )}
      </Table.Td>
      <Table.Td>
        <Group gap={6} wrap="nowrap">
          <Text size="sm" fw={role.lead ? 600 : 400} c={role.former ? "dimmed" : undefined}>
            {role.label}
          </Text>
          <Text size="sm" c="dimmed" style={figure}>
            #{process.rank}
          </Text>
        </Group>
      </Table.Td>
      <Table.Td>
        <State process={process} />
      </Table.Td>
      <Table.Td>
        <CpuCell metrics={process.metrics} />
      </Table.Td>
      <Table.Td>
        <MemoryCell metrics={process.metrics} />
      </Table.Td>
      <Table.Td>
        {process.services.length ? (
          <Group gap={4} wrap="nowrap">
            {process.services.map((service) => (
              <ServiceChip key={service} label={serviceLabel(service, process.details?.address)} detail={service}
                           secure={isSecure(process, service)} />
            ))}
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
              <ActionIcon variant="subtle" color="gray" size="lg" aria-label={`Logs of ${name}`}
                          onClick={(event) => {
                            // The row opens the overview; this goes straight to the logs
                            event.stopPropagation();
                            onLogs();
                          }}>
                <IconFileText size={18} />
              </ActionIcon>
            </Tooltip>
          )}
        </Table.Td>
      )}
    </Table.Tr>
  );
}

/** An application's line above its instances: how many there are, and what is wrong with any of them. */
function GroupHeader({ group, columns, collapsed, onToggle }: {
  group: ApplicationGroup;
  columns: number;
  collapsed: boolean;
  onToggle: () => void;
}) {
  const running = group.processes.filter((process) => process.running).length;
  const stopped = group.processes.length - running;
  const issues = countIssues(group.processes);
  const Chevron = collapsed ? IconChevronRight : IconChevronDown;
  return (
    <Table.Tr bg="var(--mantine-color-default-hover)">
      <Table.Th colSpan={columns} scope="colgroup" py={0}>
        <UnstyledButton onClick={onToggle} aria-expanded={!collapsed} w="100%" py={8}>
          <Group gap="sm" wrap="nowrap">
            <Chevron size={16} stroke={1.5} aria-hidden />
            <Text size="sm" ff="monospace" fw={700}>
              {group.application}
            </Text>
            <Text size="xs" c="dimmed" fw={400}>
              {running} running{stopped ? `, ${stopped} stopped` : ""}
            </Text>
            {ISSUE_TILES.filter(({ issue }) => issues[issue] > 0).map(({ issue, tone }) => (
              <Badge key={issue} size="sm" color={tone}>
                {issues[issue]} {ISSUE_WORDS[issue]}
              </Badge>
            ))}
          </Group>
        </UnstyledButton>
      </Table.Th>
    </Table.Tr>
  );
}

export function ProcessesPage() {
  const { snapshot, status, error } = useProcessStream();
  const { can } = useAuth();
  const seesLogs = can(PERMISSIONS.logsView);
  // Held by identity, so the drawer follows the instance as new snapshots arrive
  const [opened, setOpened] = useState<{ key: string; tab: DetailTab } | null>(null);
  const [query, setQuery] = useState("");
  // Other pages link here with ?application=Group:Name to show one application's instances
  const [params] = useSearchParams();
  const [applications, setApplications] = useState<string[]>(() => params.getAll("application"));
  const [states, setStates] = useState<string[]>([]);
  const [issue, setIssue] = useState<Issue | null>(null);
  // Narrow screens keep the less used filters folded away, so the list starts higher
  const [moreFilters, setMoreFilters] = useState(false);
  const [showStopped, setShowStopped] = useLocalStorage({ key: "archon.processes.showStopped", defaultValue: false });
  const [sort, setSort] = useLocalStorage<Sort>({ key: "archon.processes.sort", defaultValue: DEFAULT_SORT });
  const [collapsed, setCollapsed] = useLocalStorage<string[]>({ key: "archon.processes.collapsed", defaultValue: [] });

  const processes = useMemo(() => snapshot?.processes ?? [], [snapshot]);
  const summary = useMemo(() => summarize(processes), [processes]);
  const issues = useMemo(() => countIssues(processes), [processes]);
  const applicationChoices = useMemo(() => [...new Set(processes.map(applicationOf))].sort(), [processes]);
  const stateChoices = useMemo(() => [...new Set(processes.map((process) => process.runLevel))].sort()
    .map((value) => ({ value, label: stateLabel(value) })), [processes]);
  const groups = useMemo(
    () => groupByApplication(filterProcesses(processes, { query, showStopped, applications, states, issue }), sort),
    [processes, query, showStopped, applications, states, issue, sort],
  );
  const shown = groups.reduce((count, group) => count + group.processes.length, 0);
  const keyOf = (process: ProcessInfo) => `${applicationOf(process)}#${process.rank}#${process.details?.host ?? ""}`;
  const selected = opened ? processes.find((process) => keyOf(process) === opened.key) ?? null : null;
  const filtered = query.trim() !== "" || applications.length > 0 || states.length > 0 || issue !== null;
  const columns = seesLogs ? 7 : 6;

  // A new column sorts ascending; the same column again flips it
  const onSort = (column: SortColumn) =>
    setSort((current) => ({ column, descending: current.column === column ? !current.descending : false }));
  const toggleGroup = (application: string) =>
    setCollapsed((current) => current.includes(application) ? current.filter((name) => name !== application)
      : [...current, application]);
  const clearFilters = () => {
    setQuery("");
    setApplications([]);
    setStates([]);
    setIssue(null);
  };
  const header = (column: SortColumn, label: string) => (
    <SortableHeader column={column} sort={sort} onSort={onSort}>
      {label}
    </SortableHeader>
  );

  return (
    <Stack gap="md">
      <PageHeader title="Processes" description={snapshot
        ? `Primary registry ${snapshot.registry} · term ${snapshot.term} · changed ${new Date(
            snapshot.generatedAt,
          ).toLocaleTimeString()}`
        : "Waiting for the registry"} />

      {error && status !== "live" && (
        <Alert color="red" variant="light" icon={<IconAlertTriangle size={18} />}>
          {error}
          {snapshot ? ". Showing the last known state." : "."}
        </Alert>
      )}

      <SimpleGrid cols={{ base: 2, sm: 3, lg: 5 }} spacing="sm">
        <Tile label="Running" value={snapshot ? summary.running : "–"}
              hint={snapshot ? `In ${summary.applications} applications, serving ${summary.services} services`
                : "No data yet"} />
        {ISSUE_TILES.map((tile) => (
          <IssueTile key={tile.issue} label={tile.label} hint={tile.hint} tone={tile.tone}
                     count={snapshot ? issues[tile.issue] : undefined} active={issue === tile.issue}
                     onToggle={() => setIssue(issue === tile.issue ? null : tile.issue)} />
        ))}
      </SimpleGrid>

      <Group align="center" gap="sm">
        <TextInput
          style={{ flex: 2, minWidth: 200 }}
          placeholder="Search by name, pod, address, node or service"
          leftSection={<IconSearch size={16} />}
          value={query}
          onChange={(event) => setQuery(event.currentTarget.value)}
          aria-label="Search processes"
        />
        <ActionIcon variant={moreFilters ? "light" : "default"} size="input-sm" hiddenFrom="sm"
                    aria-label="More filters" aria-expanded={moreFilters}
                    onClick={() => setMoreFilters(!moreFilters)}>
          <IconAdjustmentsHorizontal size={18} />
        </ActionIcon>
        <Box display={{ base: moreFilters ? "contents" : "none", sm: "contents" }}>
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
          <Switch
            label="Show stopped"
            checked={showStopped}
            onChange={(event) => setShowStopped(event.currentTarget.checked)}
          />
        </Box>
      </Group>

      {!snapshot ? (
        <Center py="xl">{status === "connecting" ? <Loader /> : <Text c="dimmed">No data yet</Text>}</Center>
      ) : shown === 0 ? (
        filtered ? (
          <EmptyState icon={IconSearch} title="No processes match the filters"
                      action={<Button variant="subtle" size="xs" onClick={clearFilters}>Clear filters</Button>} />
        ) : (
          <EmptyState icon={IconServer2} title="No running processes"
                      hint="Instances appear here as soon as they register with the registry." />
        )
      ) : (
        <TableCard footer={
          <Group justify="space-between" px="md" py="xs">
            <Text size="xs" c="dimmed">
              {shown === 1 ? "1 process" : `${shown} processes`}
              {filtered || !showStopped ? ` shown of ${processes.length}` : ""}
            </Text>
            {filtered && (
              <Button variant="subtle" size="compact-xs" onClick={clearFilters}>
                Clear filters
              </Button>
            )}
          </Group>
        }>
          <Table verticalSpacing={6} miw={820} style={{ whiteSpace: "nowrap" }}>
            <Table.Thead>
              <Table.Tr>
                {header("host", "Instance")}
                {header("rank", "Role")}
                {header("state", "State")}
                {header("cpu", "CPU")}
                {header("memory", "Memory")}
                {header("services", "Services")}
                {seesLogs && <Table.Th aria-label="Logs" w={48} />}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {groups.flatMap((group) => {
                const folded = collapsed.includes(group.application);
                return [
                  <GroupHeader key={group.application} group={group} columns={columns} collapsed={folded}
                               onToggle={() => toggleGroup(group.application)} />,
                  ...(folded ? [] : group.processes.map((process) => (
                    <Row key={`${keyOf(process)}-${process.runLevel}`} process={process}
                         onOpen={() => setOpened({ key: keyOf(process), tab: "overview" })}
                         onLogs={seesLogs ? () => setOpened({ key: keyOf(process), tab: "logs" }) : undefined} />
                  ))),
                ];
              })}
            </Table.Tbody>
          </Table>
        </TableCard>
      )}
      <ProcessDetails process={selected} initialTab={opened?.tab} onClose={() => setOpened(null)} />
    </Stack>
  );
}
