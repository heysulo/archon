import { ActionIcon, Alert, Badge, Button, Checkbox, Code, CopyButton, Group, Loader, Modal, Select, Stack, Table,
  Text, TextInput, Tooltip } from "@mantine/core";
import { IconCheck, IconCopy, IconKey, IconTrash } from "@tabler/icons-react";
import { useState } from "react";
import type { ApplicationDefinition } from "./applicationsApi";
import { joinEnvironment, tokenState, useJoinTokenMutations, useJoinTokens, useTlsStatus, type CreatedJoinToken }
  from "./joinTokensApi";
import { key } from "./model";

const LIFETIMES = [
  { value: "1", label: "1 hour" },
  { value: "24", label: "1 day" },
  { value: "168", label: "7 days" },
  { value: "720", label: "30 days" },
  { value: "never", label: "Never" },
];

function Copyable({ value, label }: { value: string; label: string }) {
  return (
    <CopyButton value={value}>
      {({ copied, copy }) => (
        <Tooltip label={copied ? "Copied" : `Copy ${label}`}>
          <ActionIcon variant="subtle" color={copied ? "teal" : "gray"} onClick={copy} aria-label={`Copy ${label}`}>
            {copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
          </ActionIcon>
        </Tooltip>
      )}
    </CopyButton>
  );
}

const when = (value?: string) => (value ? new Date(value).toLocaleString() : "–");

interface Props {
  /** The application to manage tokens of, or null when closed. */
  target: ApplicationDefinition | null;
  onClose: () => void;
}

/** Join tokens let instances of an application get their first certificate when TLS is on. */
export function JoinTokensModal({ target, onClose }: Props) {
  return (
    <Modal opened={target !== null} onClose={onClose} size="xl"
           title={target ? `Join tokens for ${key(target.group, target.name)}` : ""}>
      {target && <JoinTokens key={key(target.group, target.name)} target={target} />}
    </Modal>
  );
}

function JoinTokens({ target }: { target: ApplicationDefinition }) {
  const tls = useTlsStatus();
  const tokens = useJoinTokens(target.group, target.name);
  const { create, revoke } = useJoinTokenMutations(target.group, target.name);
  const [description, setDescription] = useState("");
  const [reusable, setReusable] = useState(false);
  const [lifetime, setLifetime] = useState("24");
  const [created, setCreated] = useState<CreatedJoinToken | null>(null);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setError(null);
    try {
      setCreated(await create.mutateAsync({ description: description.trim(), reusable,
        expiresInHours: lifetime === "never" ? null : Number(lifetime) }));
      setDescription("");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not create the token");
    }
  };

  const status = tls.data;
  return (
    <Stack>
      <Text size="sm" c="dimmed">
        With TLS on, an instance joins by presenting a join token for its application. A registry then certifies the
        key the instance generated, and the instance renews that certificate by itself. Anyone holding a token can
        start an instance of this application, so treat it like a password.
      </Text>

      {status && (
        <Group gap="xs" wrap="nowrap">
          <Text size="sm" fw={500}>CA fingerprint</Text>
          {status.fingerprint ? (
            <>
              <Code style={{ wordBreak: "break-all" }}>{status.fingerprint}</Code>
              <Copyable value={status.fingerprint} label="fingerprint" />
            </>
          ) : (
            <Text size="sm" c="dimmed">No CA yet: start a registry with ARCHON_TLS set</Text>
          )}
          <Badge variant="light" color={status.mode === "required" ? "green" : status.mode === "optional" ? "yellow"
            : "gray"} radius="sm">
            TLS {status.mode}
          </Badge>
        </Group>
      )}

      {created && (
        <Alert color="green" variant="light" icon={<IconKey size={18} />} title="Copy the token now"
               withCloseButton onClose={() => setCreated(null)}>
          <Stack gap="xs">
            <Text size="sm">It is not shown again. Start instances with:</Text>
            <Group gap="xs" align="flex-start" wrap="nowrap">
              <Code block style={{ flex: 1, wordBreak: "break-all", whiteSpace: "pre-wrap" }}>
                {joinEnvironment(status?.mode ?? "off", status?.fingerprint, created.token)}
              </Code>
              <Copyable value={joinEnvironment(status?.mode ?? "off", status?.fingerprint, created.token)}
                        label="environment" />
            </Group>
          </Stack>
        </Alert>
      )}

      {error && (
        <Alert color="red" variant="light">
          {error}
        </Alert>
      )}

      <Group align="flex-end" gap="sm">
        <TextInput style={{ flex: 1, minWidth: 200 }} label="Description" placeholder="Staging cluster"
                   value={description} onChange={(event) => setDescription(event.currentTarget.value)} />
        <Select label="Expires after" data={LIFETIMES} value={lifetime} allowDeselect={false} w={140}
                onChange={(value) => setLifetime(value ?? "24")} />
        <Checkbox label="Reusable" mb={8} checked={reusable}
                  onChange={(event) => setReusable(event.currentTarget.checked)} />
        <Button loading={create.isPending} onClick={() => void submit()}>
          Create token
        </Button>
      </Group>
      <Text size="xs" c="dimmed" mt={-8}>
        A single-use token lets one instance join. A reusable one suits instances that scale out; revoke it when done.
      </Text>

      {tokens.isPending ? (
        <Loader size="sm" />
      ) : tokens.isError ? (
        <Alert color="red" variant="light">{tokens.error.message}</Alert>
      ) : tokens.data.length === 0 ? (
        <Text size="sm" c="dimmed">No tokens yet</Text>
      ) : (
        <div style={{ overflowX: "auto" }}>
          <Table striped verticalSpacing="xs" miw={640} style={{ whiteSpace: "nowrap" }}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Token</Table.Th>
                <Table.Th>State</Table.Th>
                <Table.Th>Expires</Table.Th>
                <Table.Th>Last used</Table.Th>
                <Table.Th>Created</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {tokens.data.map((token) => {
                const state = tokenState(token);
                return (
                  <Table.Tr key={token.id}>
                    <Table.Td>
                      <Text size="sm">{token.description || `Token ${token.id}`}</Text>
                      <Text size="xs" c="dimmed">{token.reusable ? `Reusable · ${token.useCount} joined` : "Single use"}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Badge variant="light" color={state.color} radius="sm">{state.label}</Badge>
                    </Table.Td>
                    <Table.Td><Text size="sm">{token.expiresAt ? when(token.expiresAt) : "Never"}</Text></Table.Td>
                    <Table.Td><Text size="sm">{when(token.usedAt)}</Text></Table.Td>
                    <Table.Td>
                      <Text size="sm">{when(token.createdAt)}</Text>
                      <Text size="xs" c="dimmed">by {token.createdBy}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Tooltip label="Revoke: no more instances can join with it">
                        <ActionIcon variant="subtle" color="red" loading={revoke.isPending}
                                    aria-label={`Revoke ${token.description || `token ${token.id}`}`}
                                    onClick={() => revoke.mutate(token.id)}>
                          <IconTrash size={16} />
                        </ActionIcon>
                      </Tooltip>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </div>
      )}
    </Stack>
  );
}
