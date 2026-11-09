import { ActionIcon, Alert, Badge, Box, Button, Code, Divider, Drawer, Group, Loader, Menu, Modal, SimpleGrid,
  Stack, Table, Text, Title, Tooltip } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { IconArrowBackUp, IconDots, IconFileText, IconLock, IconPencil, IconPlus } from "@tabler/icons-react";
import { useState, type ReactNode } from "react";
import type { InstanceMetrics, ProcessInfo } from "../../../api/types";
import { useAuth } from "../../../auth/AuthProvider";
import { PERMISSIONS } from "../../../auth/permissions";
import { ConfigFormModal } from "../../configs/ConfigFormModal";
import { useConfigMutations, useEffectiveConfigs, type Config, type ConfigInput, type EffectiveConfig }
  from "../../configs/configsApi";
import { LEVEL_COLORS, LEVEL_LABELS } from "../../configs/model";
import { applicationOf, isSecure, roleOf, serviceLabel, stateTone } from "../model";
import { CpuCell, MemoryCell } from "../ResourceCells";
import { formatBytes, formatUptime } from "../resources";
import { editNotice, overridePreset, sourceLabel } from "./detailsModel";

interface Props {
  /** The instance to show, or null when closed. */
  process: ProcessInfo | null;
  onClose: () => void;
  /** Opens the instance's live logs, for those allowed to watch them. */
  onLogs?: (process: ProcessInfo) => void;
}

/** One instance: where and how it runs, and every configuration it sees, which can be changed from here. */
export function ProcessDetails({ process, onClose, onLogs }: Props) {
  return (
    <Drawer opened={process !== null} onClose={onClose} position="right" size="xl"
            title={process ? `${applicationOf(process)} #${process.rank}` : ""}>
      {process && <Details key={`${applicationOf(process)}-${process.rank}`} process={process} onLogs={onLogs} />}
    </Drawer>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed">{label}</Text>
      <Box fz="sm">{children}</Box>
    </div>
  );
}

function Details({ process, onLogs }: { process: ProcessInfo; onLogs?: (process: ProcessInfo) => void }) {
  const { can } = useAuth();
  const role = roleOf(process);
  return (
    <Stack gap="lg">
      <SimpleGrid cols={{ base: 2, sm: 3 }} spacing="md">
        <Field label="Role">{role.label}</Field>
        <Field label="State">
          <Badge variant="light" color={stateTone(process)} radius="sm">{process.runLevel}</Badge>
        </Field>
        <Field label="Registry link">{process.connected ? "Connected" : "Disconnected"}</Field>
        <Field label="Host"><Text ff="monospace" fz="sm">{process.details?.host ?? "–"}</Text></Field>
        <Field label="Address"><Text ff="monospace" fz="sm">{process.details?.address ?? "–"}</Text></Field>
        <Field label="Node"><Text ff="monospace" fz="sm">{process.details?.node ?? "–"}</Text></Field>
      </SimpleGrid>
      <Field label="Services">
        {process.services.length ? (
          <Group gap={4} mt={2}>
            {process.services.map((service) => {
              const secure = isSecure(process, service);
              return (
                <Code key={service} color={secure ? "green.1" : "red.1"} c={secure ? "green.9" : "red.9"}
                      title={`${service} · ${secure ? "TLS" : "Not encrypted"}`}>
                  {serviceLabel(service, process.details?.address)}
                </Code>
              );
            })}
          </Group>
        ) : "None"}
      </Field>
      <Resources metrics={process.metrics} />
      {onLogs && process.running && process.connected && (
        <Group>
          <Button variant="default" leftSection={<IconFileText size={16} />} onClick={() => onLogs(process)}>
            Watch logs
          </Button>
        </Group>
      )}
      {can(PERMISSIONS.configsView) && (
        <>
          <Divider />
          <EffectiveConfigs group={process.group} application={process.name}
                            manage={can(PERMISSIONS.configsManage)} />
        </>
      )}
    </Stack>
  );
}

/** What the instance last reported using, refreshed every few seconds. */
function Resources({ metrics }: { metrics: InstanceMetrics | undefined }) {
  if (!metrics) {
    return (
      <Field label="Resources">
        <Text size="sm" c="dimmed">Not reported yet</Text>
      </Field>
    );
  }
  const cpus = metrics.processors === 1 ? "1 CPU" : `${metrics.processors} CPUs`;
  return (
    <div>
      <Text size="xs" c="dimmed" mb={4}>Resources, reported every few seconds</Text>
      <SimpleGrid cols={{ base: 2, sm: 3 }} spacing="md">
        <Field label={`CPU, of ${cpus}`}><CpuCell metrics={metrics} /></Field>
        <Field label={metrics.rssBytes >= 0 ? "Resident memory" : "Memory (heap)"}>
          <MemoryCell metrics={metrics} />
        </Field>
        <Field label="Memory limit">{formatBytes(metrics.memoryLimit)}</Field>
        <Field label="Heap">
          {formatBytes(metrics.heapUsed)}{metrics.heapMax > 0 ? ` of ${formatBytes(metrics.heapMax)}` : ""}
        </Field>
        <Field label="Outside the heap">{formatBytes(metrics.nonHeapUsed)}</Field>
        <Field label="Threads">{metrics.threads}</Field>
        <Field label="Uptime">{formatUptime(metrics.uptimeMillis)}</Field>
      </SimpleGrid>
    </div>
  );
}

type Editing =
  | { kind: "edit"; config: Config }
  | { kind: "override"; preset: ConfigInput; from: Config }
  | { kind: "add"; preset: ConfigInput };

function EffectiveConfigs({ group, application, manage }: { group: string; application: string; manage: boolean }) {
  const configs = useEffectiveConfigs(group, application);
  const { create, update, remove } = useConfigMutations();
  const [editing, setEditing] = useState<Editing | null>(null);
  const [removing, setRemoving] = useState<EffectiveConfig | null>(null);
  const app = `${group}:${application}`;

  const save = async (input: ConfigInput) => {
    if (!editing) return;
    if (editing.kind === "edit") {
      await update.mutateAsync({ id: editing.config.id, config: input });
      notifications.show({ color: "green", message: `Saved ${input.name}` });
    } else {
      await create.mutateAsync(input);
      notifications.show({ color: "green", message: `${input.name} set for ${app}` });
    }
  };

  const confirmRemove = async () => {
    if (!removing) return;
    try {
      await remove.mutateAsync(removing.effective.id);
      notifications.show({ color: "green", message: removing.overridden.length
        ? `${app} now sees the ${LEVEL_LABELS[removing.overridden[0]!.level].toLowerCase()} ${removing.effective.name}`
        : `Removed ${removing.effective.name}` });
      setRemoving(null);
    } catch (failure) {
      notifications.show({ color: "red", message: failure instanceof Error ? failure.message : "Remove failed" });
    }
  };

  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={4}>Configurations</Title>
          <Text size="xs" c="dimmed">
            What every instance of {app} sees: its own configuration of a name, else its group&apos;s, else the
            global one.
          </Text>
        </div>
        {manage && (
          <Button size="xs" variant="default" leftSection={<IconPlus size={14} />}
                  onClick={() => setEditing({ kind: "add", preset: { level: "APPLICATION", group, application,
                    name: "", type: "STRING", value: "", readOnly: false, description: "" } })}>
            Add for {application}
          </Button>
        )}
      </Group>

      {configs.isPending ? (
        <Loader size="sm" />
      ) : configs.isError ? (
        <Alert color="red" variant="light">{configs.error.message}</Alert>
      ) : configs.data.length === 0 ? (
        <Text size="sm" c="dimmed">It sees no configurations</Text>
      ) : (
        <Table verticalSpacing="xs" striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Name</Table.Th>
              <Table.Th>Value</Table.Th>
              <Table.Th style={{ whiteSpace: "nowrap" }}>From</Table.Th>
              {manage && <Table.Th />}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {configs.data.map((entry) => {
              const config = entry.effective;
              return (
                <Table.Tr key={config.name}>
                  <Table.Td>
                    <Group gap={4} wrap="nowrap">
                      <Text size="sm" ff="monospace">{config.name}</Text>
                      {config.readOnly && (
                        <Tooltip label="Read only to applications">
                          <IconLock size={12} aria-label="Read only" />
                        </Tooltip>
                      )}
                    </Group>
                    {config.description && <Text size="xs" c="dimmed">{config.description}</Text>}
                  </Table.Td>
                  <Table.Td>
                    <Code>{config.value}</Code>
                    {entry.overridden.map((hidden) => (
                      <Text key={hidden.id} size="xs" c={`${LEVEL_COLORS[hidden.level]}.6`} td="line-through"
                            style={{ whiteSpace: "nowrap", opacity: 0.75 }}
                            title={`Overridden ${LEVEL_LABELS[hidden.level].toLowerCase()} value`}>
                        {LEVEL_LABELS[hidden.level]}: {hidden.value}
                      </Text>
                    ))}
                  </Table.Td>
                  <Table.Td miw={110}>
                    <Badge variant="light" radius="sm" tt="none" styles={{ label: { overflow: "visible" } }}
                           color={LEVEL_COLORS[config.level]}>
                      {sourceLabel(config)}
                    </Badge>
                  </Table.Td>
                  {manage && (
                    <Table.Td>
                      <Menu position="bottom-end">
                        <Menu.Target>
                          <ActionIcon variant="subtle" color="gray" aria-label={`Actions for ${config.name}`}>
                            <IconDots size={16} />
                          </ActionIcon>
                        </Menu.Target>
                        <Menu.Dropdown>
                          <Menu.Item leftSection={<IconPencil size={16} />}
                                     onClick={() => setEditing({ kind: "edit", config })}>
                            Edit the {LEVEL_LABELS[config.level].toLowerCase()} value
                          </Menu.Item>
                          {config.level !== "APPLICATION" && (
                            <Menu.Item leftSection={<IconPlus size={16} />}
                                       onClick={() => setEditing({ kind: "override", from: config,
                                         preset: overridePreset(config, group, application) })}>
                              Override for {application}
                            </Menu.Item>
                          )}
                          {config.level !== "GLOBAL" && (
                            <Menu.Item color="red" leftSection={<IconArrowBackUp size={16} />}
                                       onClick={() => setRemoving(entry)}>
                              {entry.overridden.length ? "Remove override" : "Delete"}
                            </Menu.Item>
                          )}
                        </Menu.Dropdown>
                      </Menu>
                    </Table.Td>
                  )}
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      )}

      <ConfigFormModal
        opened={editing !== null}
        config={editing?.kind === "edit" ? editing.config : null}
        preset={editing && editing.kind !== "edit" ? editing.preset : undefined}
        nameEditable={editing?.kind === "add"}
        title={editing?.kind === "override" ? `Override ${editing.from.name} for ${app}`
          : editing?.kind === "add" ? `Add a configuration for ${app}` : undefined}
        notice={editing?.kind === "edit" ? editNotice(editing.config, app) : editing?.kind === "override"
          ? `Only ${app} sees this value. Others keep the ${LEVEL_LABELS[editing.from.level].toLowerCase()} one.`
          : undefined}
        onClose={() => setEditing(null)}
        onSubmit={save}
      />
      <Modal opened={removing !== null} onClose={() => setRemoving(null)}
             title={removing ? `${removing.overridden.length ? "Remove the override of" : "Delete"} ${
               removing.effective.name}?` : ""}>
        <Stack>
          <Text size="sm">
            {removing && (removing.overridden.length
              ? `${app} goes back to the ${LEVEL_LABELS[removing.overridden[0]!.level].toLowerCase()} value, `
                + `${removing.overridden[0]!.value}.`
              : `${app} no longer sees ${removing.effective.name}.`)}
            {removing?.effective.level === "GROUP" && " Every application in the group is affected."}
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setRemoving(null)}>Cancel</Button>
            <Button color="red" loading={remove.isPending} onClick={() => void confirmRemove()}>
              {removing?.overridden.length ? "Remove override" : "Delete"}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
