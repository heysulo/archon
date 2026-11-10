import { Text, Tooltip } from "@mantine/core";

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** How long ago, for people: just now, 12 min ago, 3 h ago, 5 d ago; older than a month, the date. */
export function ago(timestamp: string, now: number = Date.now()): string {
  const then = new Date(timestamp);
  const elapsed = now - then.getTime();
  if (Number.isNaN(elapsed)) return timestamp;
  if (elapsed < MINUTE) return "just now";
  if (elapsed < HOUR) return `${Math.floor(elapsed / MINUTE)} min ago`;
  if (elapsed < DAY) return `${Math.floor(elapsed / HOUR)} h ago`;
  if (elapsed < 30 * DAY) return `${Math.floor(elapsed / DAY)} d ago`;
  return then.toLocaleDateString();
}

/** A moment as how long ago it was, with the exact time on hover. */
export function When({ value, never = "–" }: { value: string | undefined; never?: string }) {
  if (!value) {
    return (
      <Text size="sm" c="dimmed">
        {never}
      </Text>
    );
  }
  return (
    <Tooltip label={new Date(value).toLocaleString()} openDelay={200}>
      <Text size="sm" span>
        {ago(value)}
      </Text>
    </Tooltip>
  );
}
