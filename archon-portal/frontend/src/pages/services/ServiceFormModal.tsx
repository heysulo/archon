import { Alert, Button, Group, Modal, NumberInput, SegmentedControl, Select, Stack, Text, TextInput }
  from "@mantine/core";
import { useForm } from "@mantine/form";
import { useState } from "react";
import { firstFreeOffset, identifierError, MAX_PORT_OFFSET, MODE_DESCRIPTIONS, MODE_LABELS, offsetError,
  ownerChoices, splitOwner } from "./model";
import type { Service, ServiceInput, ServiceMode } from "./servicesApi";

const MONOSPACE = { input: { fontFamily: "var(--mantine-font-family-monospace)" } };

interface Props {
  opened: boolean;
  /** The service being edited, or null to add one. */
  service: Service | null;
  /** Which service holds each port offset. */
  taken: Map<number, string>;
  /**
   * The defined applications, to pick the owner from; null when they cannot be read, as without the
   * applications:view permission, and the owner is typed instead.
   */
  definitions: { group: string; name: string; builtIn: boolean }[] | null;
  onClose: () => void;
  onSubmit: (input: ServiceInput) => Promise<void>;
}

export function ServiceFormModal({ opened, service, taken, definitions, onClose, onSubmit }: Props) {
  return (
    <Modal opened={opened} onClose={onClose} size="lg" title={service ? `Edit ${service.id}` : "Add service"}>
      {/* Mounted afresh each time, so the form starts from the service being edited */}
      <ServiceForm key={service?.id ?? "new"} service={service} taken={taken} definitions={definitions}
                   onClose={onClose} onSubmit={onSubmit} />
    </Modal>
  );
}

interface FormValues extends Omit<ServiceInput, "portOffset"> {
  portOffset: number | string;
}

function ServiceForm({ service, taken, definitions, onClose, onSubmit }: Omit<Props, "opened">) {
  const creating = service === null;
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useForm<FormValues>({
    initialValues: {
      id: service?.id ?? "",
      name: service?.name ?? "",
      portOffset: service?.portOffset ?? firstFreeOffset(taken),
      group: service?.group ?? "",
      application: service?.application ?? "",
      mode: service?.mode ?? "PRIMARY_MIRROR",
    },
    validate: {
      id: identifierError,
      name: (value) => (value.trim() ? null : "Give the service a name"),
      group: (value) => (definitions && !value ? "Choose the application that owns it" : identifierError(value)),
      application: (value) => (definitions && !value ? null : identifierError(value)),
      portOffset: (value) => offsetError(value, taken, service?.id ?? null),
    },
  });
  const { mode, group, application } = form.getValues();
  const owners = definitions && ownerChoices(definitions, service ?? undefined);

  const submit = form.onSubmit(async (values) => {
    setBusy(true);
    setError(null);
    try {
      await onSubmit({
        id: values.id.trim(),
        name: values.name.trim(),
        portOffset: Number(values.portOffset),
        group: values.group.trim(),
        application: values.application.trim(),
        mode: values.mode,
      });
      onClose();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not save the service");
    } finally {
      setBusy(false);
    }
  });

  return (
    <form onSubmit={submit} noValidate>
      <Stack>
        {error && (
          <Alert color="red" variant="light">
            {error}
          </Alert>
        )}
        <TextInput label="Id" description="What applications start and look the service up by. Case sensitive."
                   disabled={!creating} placeholder="ping" styles={MONOSPACE} data-autofocus={creating || undefined}
                   {...form.getInputProps("id")} />
        <TextInput label="Name" placeholder="Ping" {...form.getInputProps("name")} />
        <Text size="sm" fw={500} mb={-8}>
          Owner
        </Text>
        <Text size="xs" c="dimmed" mt={-8}>
          A service has one owner: only instances of that application may start it.
        </Text>
        {owners ? (
          <Select
            aria-label="Owner"
            placeholder={owners.length ? "Choose an application" : "Define an application first"}
            data={owners}
            searchable
            nothingFoundMessage="No defined application matches"
            value={group ? `${group}:${application}` : null}
            onChange={(value) => form.setValues(splitOwner(value ?? ""))}
            error={form.errors.group}
            styles={MONOSPACE}
          />
        ) : (
          <Group grow align="flex-start">
            <TextInput label="Group" placeholder="Group" {...form.getInputProps("group")} />
            <TextInput label="Application" placeholder="Application" {...form.getInputProps("application")} />
          </Group>
        )}
        <NumberInput label="Port offset" min={0} max={MAX_PORT_OFFSET} allowDecimal={false} allowNegative={false}
                     description="Added to each instance's ARCHON_BASE_PORT to give the port it serves on. Each service
                       needs its own."
                     {...form.getInputProps("portOffset")} />
        <div>
          <Text size="sm" fw={500} mb={4}>
            Mode
          </Text>
          <SegmentedControl
            fullWidth
            data={(Object.keys(MODE_LABELS) as ServiceMode[]).map((value) => ({ value, label: MODE_LABELS[value] }))}
            {...form.getInputProps("mode")}
          />
          <Text size="xs" c="dimmed" mt={4}>
            {MODE_DESCRIPTIONS[mode]}
          </Text>
        </div>
        {!creating && (
          <Text size="xs" c="dimmed">
            Registries pick up the change within 10 seconds.
          </Text>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={busy}>
            {creating ? "Add service" : "Save"}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
