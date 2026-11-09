import { Alert, Button, Checkbox, Code, Group, Modal, Stack, Text } from "@mantine/core";
import { useState } from "react";
import { useServiceMutations, type Service } from "../services/servicesApi";
import { key } from "./model";
import type { ApplicationDefinition } from "./applicationsApi";

interface Props {
  /** The application to give services to, or null when closed. */
  target: ApplicationDefinition | null;
  services: Service[];
  /** How many instances announce each service; a service any instance announces cannot change owner. */
  announced: Map<string, number>;
  onClose: () => void;
  onAssigned: (ids: string[]) => void;
}

/**
 * Gives services to one application. A service has exactly one owner, so each one chosen is taken from the
 * application that owned it.
 */
export function AssignServicesModal({ target, services, announced, onClose, onAssigned }: Props) {
  return (
    <Modal opened={target !== null} onClose={onClose} size="lg"
           title={target ? `Assign services to ${key(target.group, target.name)}` : ""}>
      {target && (
        <AssignServices key={key(target.group, target.name)} target={target} services={services}
                        announced={announced} onClose={onClose} onAssigned={onAssigned} />
      )}
    </Modal>
  );
}

function AssignServices({ target, services, announced, onClose, onAssigned }: Omit<Props, "target"> & {
  target: ApplicationDefinition;
}) {
  const { assign } = useServiceMutations();
  const [chosen, setChosen] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const candidates = services.filter((service) => !service.managedByArchon
    && key(service.group, service.application) !== key(target.group, target.name));

  const submit = async () => {
    setBusy(true);
    setError(null);
    const done: string[] = [];
    try {
      for (const id of chosen) {
        await assign.mutateAsync({ id, group: target.group, application: target.name });
        done.push(id);
      }
      onAssigned(done);
      onClose();
    } catch (failure) {
      const reason = failure instanceof Error ? failure.message : "Could not assign";
      setError(done.length ? `Assigned ${done.join(", ")}, then: ${reason}` : reason);
      setChosen((current) => current.filter((id) => !done.includes(id)));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack>
      <Text size="sm" c="dimmed">
        A service has one owner: only instances of that application may start it. Assigning a service here takes it
        from its current owner. A running service keeps its owner until it is stopped.
      </Text>
      {error && (
        <Alert color="red" variant="light">
          {error}
        </Alert>
      )}
      {candidates.length === 0 ? (
        <Text size="sm" c="dimmed">
          There are no other services to assign. Add one on the Services page.
        </Text>
      ) : (
        <Checkbox.Group value={chosen} onChange={setChosen}>
          <Stack gap="xs">
            {candidates.map((service) => {
              const running = announced.get(service.id) ?? 0;
              return (
                <Checkbox
                  key={service.id}
                  value={service.id}
                  disabled={running > 0}
                  label={
                    <Group gap={6}>
                      <Code>{service.id}</Code>
                      <Text size="sm">{service.name}</Text>
                    </Group>
                  }
                  description={running > 0
                    ? `Owned by ${key(service.group, service.application)}; running, so it cannot move`
                    : `Owned by ${key(service.group, service.application)}`}
                />
              );
            })}
          </Stack>
        </Checkbox.Group>
      )}
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>
          Cancel
        </Button>
        <Button loading={busy} disabled={chosen.length === 0} onClick={() => void submit()}>
          {chosen.length > 1 ? `Assign ${chosen.length} services` : "Assign"}
        </Button>
      </Group>
    </Stack>
  );
}
