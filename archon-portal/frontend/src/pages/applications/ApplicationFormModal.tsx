import { Alert, Button, Group, Modal, Stack, Text, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useState } from "react";
import type { ApplicationDefinition, ApplicationInput } from "./applicationsApi";
import { identifierError } from "./model";

interface Props {
  opened: boolean;
  /** The definition being edited, or null to add one. */
  definition: ApplicationDefinition | null;
  onClose: () => void;
  onSubmit: (input: ApplicationInput) => Promise<void>;
}

export function ApplicationFormModal({ opened, definition, onClose, onSubmit }: Props) {
  return (
    <Modal opened={opened} onClose={onClose}
           title={definition ? `Edit ${definition.group}:${definition.name}` : "Define application"}>
      {/* Mounted afresh each time, so the form starts from the definition being edited */}
      <ApplicationForm key={definition ? `${definition.group}:${definition.name}` : "new"} definition={definition}
                       onClose={onClose} onSubmit={onSubmit} />
    </Modal>
  );
}

function ApplicationForm({ definition, onClose, onSubmit }: Omit<Props, "opened">) {
  const creating = definition === null;
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useForm<ApplicationInput>({
    initialValues: {
      group: definition?.group ?? "",
      name: definition?.name ?? "",
      description: definition?.description ?? "",
    },
    validate: {
      group: (value) => identifierError(value) ?? (value.trim() === "Archon" ? "Reserved for Archon" : null),
      name: identifierError,
      description: (value) => (value.trim().length <= 255 ? null : "At most 255 characters"),
    },
  });

  const submit = form.onSubmit(async (values) => {
    setBusy(true);
    setError(null);
    try {
      await onSubmit({ group: values.group.trim(), name: values.name.trim(), description: values.description.trim() });
      onClose();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not save the definition");
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
        <Group grow align="flex-start">
          <TextInput label="Group" placeholder="Shop" disabled={!creating} data-autofocus={creating || undefined}
                     {...form.getInputProps("group")} />
          <TextInput label="Name" placeholder="Cart" disabled={!creating} {...form.getInputProps("name")} />
        </Group>
        <Text size="xs" c="dimmed" mt={-8}>
          {creating
            ? "Instances register with exactly this group and name, as passed to ArchonInterface.initialize. Case " +
              "sensitive. Registries admit them within 10 seconds."
            : "The group and name are the application's identity and cannot change."}
        </Text>
        <TextInput label="Description" placeholder="What the application does" {...form.getInputProps("description")} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={busy}>
            {creating ? "Define application" : "Save"}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
