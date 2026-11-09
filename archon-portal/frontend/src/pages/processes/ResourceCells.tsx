import { Progress, Stack, Text, Tooltip } from "@mantine/core";
import type { InstanceMetrics } from "../../api/types";
import { cpuPercent, formatBytes, formatPercent, loadColor, memoryLimit, memoryUsed, share } from "./resources";

const NONE = (
  <Text size="sm" c="dimmed">
    –
  </Text>
);

/** A figure above a thin bar showing how close it is to its limit. */
function Gauge({ text, percent, label }: { text: string; percent: number | undefined; label: string }) {
  return (
    <Tooltip label={label} openDelay={300}>
      <Stack gap={2} miw={72}>
        <Text size="xs" ff="monospace" style={{ whiteSpace: "nowrap" }}>
          {text}
        </Text>
        <Progress size="xs" radius="xl" value={percent ?? 0} color={loadColor(percent)} aria-label={label} />
      </Stack>
    </Tooltip>
  );
}

/** CPU use, as a share of the CPUs available to the process. */
export function CpuCell({ metrics }: { metrics: InstanceMetrics | undefined }) {
  const percent = cpuPercent(metrics);
  if (percent === undefined || !metrics) return NONE;
  const cpus = metrics.processors === 1 ? "1 CPU" : `${metrics.processors} CPUs`;
  return <Gauge text={formatPercent(percent)} percent={percent} label={`CPU ${formatPercent(percent)} of ${cpus}`} />;
}

/** Memory use against its limit: resident memory where known, else the heap. */
export function MemoryCell({ metrics }: { metrics: InstanceMetrics | undefined }) {
  const used = memoryUsed(metrics);
  if (used === undefined || !metrics) return NONE;
  const limit = memoryLimit(metrics);
  const what = metrics.rssBytes >= 0 ? "Resident" : "Heap";
  const label = `${what} ${formatBytes(used)}${limit ? ` of ${formatBytes(limit)}` : ""} · heap ${
    formatBytes(metrics.heapUsed)}${metrics.heapMax > 0 ? ` of ${formatBytes(metrics.heapMax)}` : ""}`;
  return <Gauge text={formatBytes(used)} percent={share(used, limit)} label={label} />;
}
