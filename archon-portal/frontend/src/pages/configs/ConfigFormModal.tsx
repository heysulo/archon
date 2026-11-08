import { Alert, Button, Group, Modal, SegmentedControl, Select, Stack, Switch, Text, Textarea, TextInput }
  from "@mantine/core";
import { useForm } from "@mantine/form";
import { useState } from "react";

// Only the typed text is monospace, not the label above it
const MONOSPACE = { input: { fontFamily: "var(--mantine-font-family-monospace)" } };
import type { Config, ConfigInput, ConfigLevel, ConfigType } from "./configsApi";
import { identifierError, LEVEL_LABELS, valueError } from "./model";

interface Props {
  opened: boolean;
  /** The configuration being edited, or null to create one. */
  config: Config | null;
  onClose: () => void;
  onSubmit: (input: ConfigInput) => Promise<void>;
}

export function ConfigFormModal({ opened, config, onClose, onSubmit }: Props) {
  return (
    <Modal opened={opened} onClose={onClose} size="lg"
           title={config ? `Edit ${config.name}` : "Add configuration"}>
      {/* Mounted afresh each time the dialog opens, so the form starts from the configuration being edited */}
      <ConfigForm key={config?.id ?? "new"} config={config} onClose={onClose} onSubmit={onSubmit} />
    </Modal>
  );
}

function ConfigForm({ config, onClose, onSubmit }: Omit<Props, "opened">) {
  const creating = config === null;
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useForm<ConfigInput>({
    initialValues: {
      level: config?.level ?? "GLOBAL",
      group: config?.group ?? "",
      application: config?.application ?? "",
      name: config?.name ?? "",
      type: config?.type ?? "STRING",
      value: config?.value ?? "",
      readOnly: config?.readOnly ?? false,
      description: config?.description ?? "",
    },
    validate: {
      group: (value, values) => (values.level !== "GLOBAL" ? identifierError(value) : null),
      application: (value, values) => (values.level === "APPLICATION" ? identifierError(value) : null),
      name: identifierError,
      value: (value, values) => valueError(values.type, value),
    },
  });
  const { level, type } = form.getValues();

  const submit = form.onSubmit(async (values) => {
    setBusy(true);
    setError(null);
    try {
      await onSubmit({
        ...values,
        group: values.level === "GLOBAL" ? "" : values.group.trim(),
        application: values.level === "APPLICATION" ? values.application.trim() : "",
        name: values.name.trim(),
      });
      onClose();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not save the configuration");
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
        <div>
          <Text size="sm" fw={500} mb={4}>
            Level
          </Text>
          <SegmentedControl
            fullWidth
            disabled={!creating}
            data={(Object.keys(LEVEL_LABELS) as ConfigLevel[]).map((value) => ({ value, label: LEVEL_LABELS[value] }))}
            {...form.getInputProps("level")}
          />
          <Text size="xs" c="dimmed" mt={4}>
            {level === "GLOBAL" && "Every application sees it."}
            {level === "GROUP" && "Every application in the group sees it, over a global one with the same name."}
            {level === "APPLICATION" && "Only this application sees it, over its group's and the global one."}
          </Text>
        </div>
        {level !== "GLOBAL" && (
          <TextInput label="Group" disabled={!creating} placeholder="Group" {...form.getInputProps("group")} />
        )}
        {level === "APPLICATION" && (
          <TextInput label="Application" disabled={!creating} placeholder="Application"
                     {...form.getInputProps("application")} />
        )}
        <TextInput label="Name" disabled={!creating} placeholder="ping.intervalMillis" styles={MONOSPACE}
                   data-autofocus={creating || undefined} {...form.getInputProps("name")} />
        <Select
          label="Type"
          data={["STRING", "INTEGER", "DECIMAL", "BOOLEAN"]}
          allowDeselect={false}
          {...form.getInputProps("type")}
          onChange={(next) => {
            form.setFieldValue("type", (next ?? "STRING") as ConfigType);
            if (next === "BOOLEAN" && form.getValues().value !== "true") form.setFieldValue("value", "false");
          }}
        />
        {type === "BOOLEAN" ? (
          <SegmentedControl data={["true", "false"]} {...form.getInputProps("value")} />
        ) : type === "STRING" ? (
          <Textarea label="Value" autosize minRows={1} maxRows={6} {...form.getInputProps("value")} />
        ) : (
          <TextInput label="Value" styles={MONOSPACE} {...form.getInputProps("value")} />
        )}
        <Switch label="Read only" description="Applications cannot change it; only the portal can"
                {...form.getInputProps("readOnly", { type: "checkbox" })} />
        <Textarea label="Description" autosize minRows={1} maxRows={4} {...form.getInputProps("description")} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={busy}>
            {creating ? "Add configuration" : "Save"}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
