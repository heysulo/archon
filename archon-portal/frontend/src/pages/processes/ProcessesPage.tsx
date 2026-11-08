import { Alert, Center, Group, Loader, Paper, SimpleGrid, Stack, Switch, Text, TextInput, Title } from "@mantine/core";
import { useLocalStorage } from "@mantine/hooks";
import { IconAlertTriangle, IconSearch } from "@tabler/icons-react";
import { useMemo, useState } from "react";
import { useProcessStream } from "../../api/processStream";
import { ApplicationCard } from "./ApplicationCard";
import { groupProcesses, summarize } from "./model";

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

export function ProcessesPage() {
  const { snapshot, status, error } = useProcessStream();
  const [query, setQuery] = useState("");
  const [showStopped, setShowStopped] = useLocalStorage({ key: "archon.processes.showStopped", defaultValue: false });

  const processes = useMemo(() => snapshot?.processes ?? [], [snapshot]);
  const summary = useMemo(() => summarize(processes), [processes]);
  const groups = useMemo(() => groupProcesses(processes, { showStopped, query }), [processes, showStopped, query]);

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

      <Group>
        <TextInput
          style={{ flex: 1, minWidth: 220 }}
          placeholder="Search by name, pod, address, node or service"
          leftSection={<IconSearch size={16} />}
          value={query}
          onChange={(event) => setQuery(event.currentTarget.value)}
          aria-label="Search processes"
        />
        <Switch
          label="Show stopped"
          checked={showStopped}
          onChange={(event) => setShowStopped(event.currentTarget.checked)}
        />
      </Group>

      {!snapshot ? (
        <Center py="xl">{status === "connecting" ? <Loader /> : <Text c="dimmed">No data yet</Text>}</Center>
      ) : groups.length === 0 ? (
        <Center py="xl">
          <Text c="dimmed">{query ? "No processes match the search" : "No running processes"}</Text>
        </Center>
      ) : (
        groups.map((group) => <ApplicationCard key={group.key} group={group} />)
      )}
    </Stack>
  );
}
