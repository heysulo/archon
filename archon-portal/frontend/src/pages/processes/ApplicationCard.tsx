import { Badge, Box, Card, Code, Group, Table, Text } from "@mantine/core";
import type { ProcessInfo } from "../../api/types";
import { roleOf, stateTone, type ApplicationGroup } from "./model";

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

function RegistryLink({ connected }: { connected: boolean }) {
  return (
    <Group gap={6} wrap="nowrap">
      <Box w={8} h={8} bg={connected ? "green.6" : "red.6"} style={{ borderRadius: "50%" }} />
      <Text size="sm">{connected ? "Connected" : "Disconnected"}</Text>
    </Group>
  );
}

function Row({ process, showNode }: { process: ProcessInfo; showNode: boolean }) {
  const role = roleOf(process);
  return (
    <Table.Tr style={role.former ? { opacity: 0.55 } : undefined}>
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
        <RegistryLink connected={process.connected} />
      </Table.Td>
      <Table.Td>
        {process.services.length ? (
          <Group gap={4}>
            {process.services.map((service) => (
              <Code key={service}>{service}</Code>
            ))}
          </Group>
        ) : (
          <Text size="sm" c="dimmed">
            –
          </Text>
        )}
      </Table.Td>
    </Table.Tr>
  );
}

/** One application definition and its instances. */
export function ApplicationCard({ group }: { group: ApplicationGroup }) {
  const showNode = group.processes.some((process) => process.details?.node);
  return (
    <Card withBorder padding={0} radius="md">
      <Group justify="space-between" px="md" py="sm">
        <Group gap={6}>
          <Text c="dimmed">{group.group} /</Text>
          <Text fw={600}>{group.name}</Text>
        </Group>
        <Text size="sm" c="dimmed">
          {group.running} running{group.stopped ? `, ${group.stopped} stopped` : ""}
        </Text>
      </Group>
      {/* Narrow screens scroll the table sideways rather than wrapping pod names and squeezing badges */}
      <Box style={{ overflowX: "auto" }}>
        <Table verticalSpacing="xs" highlightOnHover withTableBorder={false} miw={880} style={{ whiteSpace: "nowrap" }}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Rank</Table.Th>
              <Table.Th>Role</Table.Th>
              <Table.Th>State</Table.Th>
              <Table.Th>Host</Table.Th>
              <Table.Th>Address</Table.Th>
              {showNode && <Table.Th>Node</Table.Th>}
              <Table.Th>Registry link</Table.Th>
              <Table.Th>Services</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {group.processes.map((process) => (
              <Row key={`${process.rank}-${process.details?.host ?? ""}-${process.runLevel}`} process={process}
                   showNode={showNode} />
            ))}
          </Table.Tbody>
        </Table>
      </Box>
    </Card>
  );
}
