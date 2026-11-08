import { Badge, Tooltip } from "@mantine/core";
import { useProcessStream, type StreamStatus } from "../api/processStream";

const LOOK: Record<StreamStatus, { color: string; label: string }> = {
  connecting: { color: "gray", label: "Connecting" },
  live: { color: "green", label: "Live" },
  offline: { color: "red", label: "Registry unavailable" },
  reconnecting: { color: "orange", label: "Reconnecting" },
};

/** Whether changes are arriving as they happen, and from which portal replica. */
export function LiveStatus() {
  const { snapshot, status, error } = useProcessStream();
  const look = LOOK[status];
  const servedBy = snapshot?.servedBy;
  const tip =
    status === "live" && servedBy
      ? `Served by ${servedBy.host} (${servedBy.role}${servedBy.rank > 0 ? `, rank ${servedBy.rank}` : ""})`
      : (error ?? look.label);
  return (
    <Tooltip label={tip} withArrow>
      <Badge variant="dot" color={look.color} size="lg" radius="sm">
        {look.label}
      </Badge>
    </Tooltip>
  );
}
