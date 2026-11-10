import { Badge, Tooltip } from "@mantine/core";
import { IconLock, IconLockOpen } from "@tabler/icons-react";
import { TONE } from "./tones";

interface Props {
  label: string;
  /** Whether it is served with TLS; leave out where that is not known, such as a definition nobody serves. */
  secure?: boolean;
  /** Said on hover in place of the label, such as the full `id@host:port`. */
  detail?: string;
}

/** A service, wherever one is named. Encryption shows as a lock as well as a colour. */
export function ServiceChip({ label, secure, detail }: Props) {
  if (secure === undefined) {
    return (
      <Badge color="gray" ff="monospace" fw={500}>
        {label}
      </Badge>
    );
  }
  const Icon = secure ? IconLock : IconLockOpen;
  return (
    <Tooltip label={`${detail ?? label} · ${secure ? "TLS" : "Not encrypted"}`}>
      <Badge color={secure ? TONE.ok : TONE.danger} ff="monospace" fw={500}
             leftSection={<Icon size={12} aria-hidden />}
             aria-label={`${label}, ${secure ? "TLS" : "not encrypted"}`}>
        {label}
      </Badge>
    </Tooltip>
  );
}
