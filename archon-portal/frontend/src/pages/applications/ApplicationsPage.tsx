import { ActionIcon, Alert, Badge, Button, Card, Center, Code, Group, Loader, Menu, Modal, Stack, Table, Text,
  TextInput, Title, Tooltip } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { IconAlertTriangle, IconDots, IconLock, IconPencil, IconPlugConnected, IconPlus, IconSearch, IconTrash }
  from "@tabler/icons-react";
import { useMemo, useState } from "react";
import { useProcessStream } from "../../api/processStream";
import { useAuth } from "../../auth/AuthProvider";
import { PERMISSIONS } from "../../auth/permissions";
import { announcements } from "../services/model";
import { useServices } from "../services/servicesApi";
import { ApplicationFormModal } from "./ApplicationFormModal";
import { AssignServicesModal } from "./AssignServicesModal";
import { useApplicationMutations, useApplications, type ApplicationDefinition, type ApplicationInput }
  from "./applicationsApi";
import { key, matches, runningInstances, servicesByOwner, undefinedRunning } from "./model";

export function ApplicationsPage() {
  const { can } = useAuth();
  const manage = can(PERMISSIONS.applicationsManage);
  const seesProcesses = can(PERMISSIONS.processesView);
  const seesServices = can(PERMISSIONS.servicesView);
  const assigns = can(PERMISSIONS.servicesManage);
  const actions = manage || assigns;
  const definitions = useApplications();
  const services = useServices(seesServices);
  const { snapshot } = useProcessStream();
  const { create, update, remove } = useApplicationMutations();
  const [query, setQuery] = useState("");
  // undefined: closed; null: adding; a definition: editing it
  const [editing, setEditing] = useState<ApplicationDefinition | null | undefined>(undefined);
  const [deleting, setDeleting] = useState<ApplicationDefinition | null>(null);
  const [assigning, setAssigning] = useState<ApplicationDefinition | null>(null);

  const all = useMemo(() => definitions.data ?? [], [definitions.data]);
  const running = useMemo(() => runningInstances(snapshot?.processes ?? []), [snapshot]);
  const owned = useMemo(() => servicesByOwner(services.data ?? []), [services.data]);
  const announced = useMemo(() => announcements(snapshot?.processes ?? []), [snapshot]);
  const strays = useMemo(() => undefinedRunning(running, all), [running, all]);
  const shown = useMemo(() => all.filter((definition) => matches(definition, query)), [all, query]);

  const save = async (input: ApplicationInput) => {
    if (editing === null) {
      await create.mutateAsync(input);
      notifications.show({ color: "green", message: `Defined ${input.group}:${input.name}` });
    } else if (editing) {
      await update.mutateAsync(input);
      notifications.show({ color: "green", message: `Saved ${input.group}:${input.name}` });
    }
  };

  const confirmDelete = async () => {
    if (!deleting) return;
    try {
      await remove.mutateAsync({ group: deleting.group, name: deleting.name });
      notifications.show({ color: "green", message: `Removed ${deleting.group}:${deleting.name}` });
      setDeleting(null);
    } catch (failure) {
      notifications.show({ color: "red", message: failure instanceof Error ? failure.message : "Remove failed" });
    }
  };

  // Why a definition cannot be removed now, if it cannot
  const blocker = (definition: ApplicationDefinition): string | null => {
    const id = key(definition.group, definition.name);
    const instances = running.get(id) ?? 0;
    if (instances > 0) return `${instances === 1 ? "1 instance" : `${instances} instances`} running`;
    const services = owned.get(id);
    if (services?.length) return `Owns ${services.join(", ")}`;
    return null;
  };

  return (
    <Stack gap="md">
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={2}>Applications</Title>
          <Text size="sm" c="dimmed">
            Only instances of a defined application may register; others are told to stop. Registries pick up
            changes within 10 seconds.
          </Text>
        </div>
        {manage && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing(null)}>
            Define application
          </Button>
        )}
      </Group>

      <TextInput
        placeholder="Search by group, name or description"
        leftSection={<IconSearch size={16} />}
        value={query}
        onChange={(event) => setQuery(event.currentTarget.value)}
        aria-label="Search applications"
      />

      {definitions.isError && (
        <Alert color="red" variant="light">
          {definitions.error.message}
        </Alert>
      )}
      {seesProcesses && strays.length > 0 && (
        <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={18} />}>
          Running but not defined: {strays.join(", ")}. The registry refuses these when they next reconnect.
        </Alert>
      )}

      {definitions.isPending ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : shown.length === 0 ? (
        <Center py="xl">
          <Text c="dimmed">{query ? "No applications match" : "No applications defined yet"}</Text>
        </Center>
      ) : (
        <Card withBorder padding={0} radius="md">
          <div style={{ overflowX: "auto" }}>
            <Table verticalSpacing="sm" highlightOnHover miw={720}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Application</Table.Th>
                  {seesProcesses && <Table.Th>Running</Table.Th>}
                  {seesServices && <Table.Th>Services</Table.Th>}
                  {actions && <Table.Th />}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {shown.map((definition) => {
                  const id = key(definition.group, definition.name);
                  const instances = running.get(id) ?? 0;
                  const why = blocker(definition);
                  return (
                    <Table.Tr key={id}>
                      <Table.Td>
                        <Group gap={6} wrap="nowrap">
                          <Text size="sm" ff="monospace" fw={500}>
                            {id}
                          </Text>
                          {definition.builtIn && (
                            <Tooltip label="Part of Archon: always defined">
                              <IconLock size={14} aria-label="Part of Archon" />
                            </Tooltip>
                          )}
                        </Group>
                        {definition.description && (
                          <Text size="xs" c="dimmed">
                            {definition.description}
                          </Text>
                        )}
                      </Table.Td>
                      {seesProcesses && (
                        <Table.Td>
                          {instances ? (
                            <Badge variant="light" color="green" radius="sm">
                              {instances === 1 ? "1 instance" : `${instances} instances`}
                            </Badge>
                          ) : (
                            <Text size="sm" c="dimmed">
                              Not running
                            </Text>
                          )}
                        </Table.Td>
                      )}
                      {seesServices && (
                        <Table.Td>
                          {owned.get(id)?.length ? (
                            <Group gap={4}>
                              {owned.get(id)!.map((service) => (
                                <Code key={service}>{service}</Code>
                              ))}
                            </Group>
                          ) : (
                            <Text size="sm" c="dimmed">
                              None
                            </Text>
                          )}
                        </Table.Td>
                      )}
                      {actions && (
                        <Table.Td>
                          {!definition.builtIn && (
                            <Menu position="bottom-end">
                              <Menu.Target>
                                <ActionIcon variant="subtle" color="gray" aria-label={`Actions for ${id}`}>
                                  <IconDots size={16} />
                                </ActionIcon>
                              </Menu.Target>
                              <Menu.Dropdown>
                                {assigns && (
                                  <Menu.Item leftSection={<IconPlugConnected size={16} />}
                                             onClick={() => setAssigning(definition)}>
                                    Assign services
                                  </Menu.Item>
                                )}
                                {manage && (
                                  <>
                                    <Menu.Item leftSection={<IconPencil size={16} />}
                                               onClick={() => setEditing(definition)}>
                                      Edit
                                    </Menu.Item>
                                    <Menu.Item color="red" leftSection={<IconTrash size={16} />}
                                               disabled={why !== null} onClick={() => setDeleting(definition)}>
                                      {why ? `Remove (${why.toLowerCase()})` : "Remove"}
                                    </Menu.Item>
                                  </>
                                )}
                              </Menu.Dropdown>
                            </Menu>
                          )}
                        </Table.Td>
                      )}
                    </Table.Tr>
                  );
                })}
              </Table.Tbody>
            </Table>
          </div>
        </Card>
      )}

      <AssignServicesModal target={assigning} services={services.data ?? []} announced={announced}
                           onClose={() => setAssigning(null)}
                           onAssigned={(ids) => notifications.show({ color: "green", message: `Assigned ${ids.join(", ")}` })} />
      <ApplicationFormModal opened={editing !== undefined} definition={editing ?? null}
                            onClose={() => setEditing(undefined)} onSubmit={save} />
      <Modal opened={deleting !== null} onClose={() => setDeleting(null)}
             title={`Remove ${deleting ? key(deleting.group, deleting.name) : ""}?`}>
        <Stack>
          <Text size="sm">
            Its instances will no longer be allowed to register. Nothing runs now, so nothing stops.
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setDeleting(null)}>
              Cancel
            </Button>
            <Button color="red" loading={remove.isPending} onClick={() => void confirmDelete()}>
              Remove definition
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
