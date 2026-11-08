import { ActionIcon, Alert, Badge, Button, Card, Center, Code, Group, Loader, Menu, Modal, Stack, Table, Text,
  TextInput, Title, Tooltip } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { IconAlertTriangle, IconDots, IconLock, IconPencil, IconPlayerPlay, IconPlus, IconSearch, IconTrash }
  from "@tabler/icons-react";
import { useMemo, useState } from "react";
import { useProcessStream } from "../../api/processStream";
import { useAuth } from "../../auth/AuthProvider";
import { PERMISSIONS } from "../../auth/permissions";
import { announcements, instancesByService, matches, MODE_LABELS, takenOffsets, undefinedServices,
  type ServiceInstance } from "./model";
import { ServiceFormModal } from "./ServiceFormModal";
import { useServiceMutations, useServices, type Service, type ServiceInput } from "./servicesApi";

function Instances({ instances }: { instances: ServiceInstance[] | undefined }) {
  if (!instances?.length) {
    return (
      <Text size="sm" c="dimmed">
        Not running
      </Text>
    );
  }
  return (
    <Group gap={6}>
      {instances.map((instance) => (
        <Tooltip key={`${instance.rank}-${instance.host}-${instance.port}`}
                 label={`${instance.hostName ?? instance.host}, ${instance.serving ? "serving" : "standing by"}`}>
          <Badge variant={instance.serving ? "light" : "outline"} color={instance.serving ? "green" : "gray"}
                 radius="sm" tt="none" ff="monospace">
            #{instance.rank} {instance.host}:{instance.port}
          </Badge>
        </Tooltip>
      ))}
    </Group>
  );
}

export function ServicesPage() {
  const { can } = useAuth();
  const manage = can(PERMISSIONS.servicesManage);
  const seesProcesses = can(PERMISSIONS.processesView);
  const services = useServices();
  const { snapshot } = useProcessStream();
  const { create, update, remove } = useServiceMutations();
  const [query, setQuery] = useState("");
  // undefined: closed; null: adding; a service: editing it
  const [editing, setEditing] = useState<Service | null | undefined>(undefined);
  const [deleting, setDeleting] = useState<Service | null>(null);

  const all = useMemo(() => services.data ?? [], [services.data]);
  const instances = useMemo(
    () => instancesByService(snapshot?.processes ?? [], new Map(all.map((service) => [service.id, service.mode]))),
    [snapshot, all],
  );
  const strays = useMemo(() => undefinedServices(instances, all), [instances, all]);
  const announced = useMemo(() => announcements(snapshot?.processes ?? []), [snapshot]);
  const taken = useMemo(() => takenOffsets(all), [all]);
  const shown = useMemo(() => all.filter((service) => matches(service, query)), [all, query]);

  const save = async (input: ServiceInput) => {
    if (editing === null) {
      await create.mutateAsync(input);
      notifications.show({ color: "green", message: `Added ${input.id}` });
    } else if (editing) {
      await update.mutateAsync(input);
      notifications.show({ color: "green", message: `Saved ${input.id}` });
    }
  };

  const confirmDelete = async () => {
    if (!deleting) return;
    try {
      await remove.mutateAsync(deleting.id);
      notifications.show({ color: "green", message: `Deleted ${deleting.id}` });
      setDeleting(null);
    } catch (failure) {
      notifications.show({ color: "red", message: failure instanceof Error ? failure.message : "Delete failed" });
    }
  };

  return (
    <Stack gap="md">
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={2}>Services</Title>
          <Text size="sm" c="dimmed">
            What applications can serve and look up. Registries pick up changes within 10 seconds. A running service
            cannot be changed or deleted; stop it first.
          </Text>
        </div>
        {manage && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing(null)}>
            Add service
          </Button>
        )}
      </Group>

      <TextInput
        placeholder="Search by id, name, owner or mode"
        leftSection={<IconSearch size={16} />}
        value={query}
        onChange={(event) => setQuery(event.currentTarget.value)}
        aria-label="Search services"
      />

      {services.isError && (
        <Alert color="red" variant="light">
          {services.error.message}
        </Alert>
      )}
      {seesProcesses && strays.length > 0 && (
        <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={18} />}>
          Still served but no longer defined: {strays.join(", ")}. Clients cannot look these up.
        </Alert>
      )}

      {services.isPending ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : shown.length === 0 ? (
        <Center py="xl">
          <Text c="dimmed">{query ? "No services match" : "No services yet"}</Text>
        </Center>
      ) : (
        <Card withBorder padding={0} radius="md">
          <div style={{ overflowX: "auto" }}>
            <Table verticalSpacing="sm" highlightOnHover miw={920}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Service</Table.Th>
                  <Table.Th>Owner</Table.Th>
                  <Table.Th>Mode</Table.Th>
                  <Table.Th>Port offset</Table.Th>
                  {seesProcesses && <Table.Th>Running</Table.Th>}
                  {manage && <Table.Th />}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {shown.map((service) => (
                  <Table.Tr key={service.id}>
                    <Table.Td>
                      <Group gap={6} wrap="nowrap">
                        <Text size="sm" ff="monospace">
                          {service.id}
                        </Text>
                        {service.managedByArchon && (
                          <Tooltip label="Managed by Archon">
                            <IconLock size={14} aria-label="Managed by Archon" />
                          </Tooltip>
                        )}
                      </Group>
                      <Text size="xs" c="dimmed">
                        {service.name}
                      </Text>
                    </Table.Td>
                    <Table.Td style={{ whiteSpace: "nowrap" }}>
                      {service.group} / {service.application}
                    </Table.Td>
                    <Table.Td>
                      <Badge variant="light" color={service.mode === "LOAD_BALANCE" ? "grape" : "indigo"} radius="sm">
                        {MODE_LABELS[service.mode]}
                      </Badge>
                    </Table.Td>
                    <Table.Td>
                      <Code>+{service.portOffset}</Code>
                    </Table.Td>
                    {seesProcesses && (
                      <Table.Td>
                        <Instances instances={instances.get(service.id)} />
                      </Table.Td>
                    )}
                    {manage && (
                      <Table.Td>
                        {service.managedByArchon ? null : announced.has(service.id) ? (
                          <Tooltip label="Running: stop it before changing or deleting it">
                            <IconPlayerPlay size={16} aria-label={`${service.id} is running`}
                                            color="var(--mantine-color-green-6)" />
                          </Tooltip>
                        ) : (
                          <Menu position="bottom-end">
                            <Menu.Target>
                              <ActionIcon variant="subtle" color="gray" aria-label={`Actions for ${service.id}`}>
                                <IconDots size={16} />
                              </ActionIcon>
                            </Menu.Target>
                            <Menu.Dropdown>
                              <Menu.Item leftSection={<IconPencil size={16} />} onClick={() => setEditing(service)}>
                                Edit
                              </Menu.Item>
                              <Menu.Item color="red" leftSection={<IconTrash size={16} />}
                                         onClick={() => setDeleting(service)}>
                                Delete
                              </Menu.Item>
                            </Menu.Dropdown>
                          </Menu>
                        )}
                      </Table.Td>
                    )}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </div>
        </Card>
      )}

      <ServiceFormModal opened={editing !== undefined} service={editing ?? null} taken={taken}
                        onClose={() => setEditing(undefined)} onSubmit={save} />
      <Modal opened={deleting !== null} onClose={() => setDeleting(null)} title={`Delete ${deleting?.id ?? ""}?`}>
        <Stack>
          <Text size="sm">
            Clients stop finding it within 10 seconds, and no instance can start it again until it is defined again.
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setDeleting(null)}>
              Cancel
            </Button>
            <Button color="red" loading={remove.isPending} onClick={() => void confirmDelete()}>
              Delete service
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
