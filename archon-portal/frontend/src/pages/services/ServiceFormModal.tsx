import { Alert, Button, Group, Modal, NumberInput, SegmentedControl, Stack, Text, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useState } from "react";
import { identifierError, MAX_PORT_OFFSET, MODE_DESCRIPTIONS, MODE_LABELS } from "./model";
import type { Service, ServiceInput, ServiceMode } from "./servicesApi";

const MONOSPACE = { input: { fontFamily: "var(--mantine-font-family-monospace)" } };

interface Props {
  opened: boolean;
  /** The service being edited, or null to add one. */
  service: Service | null;
  onClose: () => void;
  onSubmit: (input: ServiceInput) => Promise<void>;
}

export function ServiceFormModal({ opened, service, onClose, onSubmit }: Props) {
  return (
    <Modal opened={opened} onClose={onClose} size="lg" title={service ? `Edit ${service.id}` : "Add service"}>
      {/* Mounted afresh each time, so the form starts from the service being edited */}
      <ServiceForm key={service?.id ?? "new"} service={service} onClose={onClose} onSubmit={onSubmit} />
    </Modal>
  );
}

interface FormValues extends Omit<ServiceInput, "portOffset"> {
  portOffset: number | string;
}

function ServiceForm({ service, onClose, onSubmit }: Omit<Props, "opened">) {
  const creating = service === null;
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useForm<FormValues>({
    initialValues: {
      id: service?.id ?? "",
      name: service?.name ?? "",
      portOffset: service?.portOffset ?? 0,
      group: service?.group ?? "",
      application: service?.application ?? "",
      mode: service?.mode ?? "PRIMARY_MIRROR",
    },
    validate: {
      id: identifierError,
      name: (value) => (value.trim() ? null : "Give the service a name"),
      group: identifierError,
      application: identifierError,
      portOffset: (value) =>
        typeof value === "number" && Number.isInteger(value) && value >= 0 && value <= MAX_PORT_OFFSET
          ? null
          : `A whole number from 0 to ${MAX_PORT_OFFSET}`,
    },
  });
  const { mode } = form.getValues();

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
          Only instances of this application may start the service.
        </Text>
        <Group grow align="flex-start">
          <TextInput label="Group" placeholder="Group" {...form.getInputProps("group")} />
          <TextInput label="Application" placeholder="Application" {...form.getInputProps("application")} />
        </Group>
        <NumberInput label="Port offset" min={0} max={MAX_PORT_OFFSET} allowDecimal={false} allowNegative={false}
                     description="Added to each instance's ARCHON_BASE_PORT to give the port it serves on"
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
