import type { InstanceMetrics } from "../../api/types";
import { cpuPercent, formatBytes, formatPercent, formatUptime, loadColor, memoryLimit, memoryUsed, share }
  from "./resources";

const MB = 1024 * 1024;
const METRICS: InstanceMetrics = { sampledAt: 1, cpuLoad: 0.05, processors: 2, rssBytes: 300 * MB,
  memoryLimit: 1024 * MB, heapUsed: 100 * MB, heapMax: 256 * MB, nonHeapUsed: 50 * MB, threads: 30,
  uptimeMillis: 90_000 };

describe("resources", () => {
  it("measures memory as resident memory against the limit, or the heap where that is unknown", () => {
    expect(memoryUsed(METRICS)).toBe(300 * MB);
    expect(memoryLimit(METRICS)).toBe(1024 * MB);
    const noRss = { ...METRICS, rssBytes: -1 };
    expect(memoryUsed(noRss)).toBe(100 * MB);
    expect(memoryLimit(noRss)).toBe(256 * MB);
    expect(memoryUsed(undefined)).toBeUndefined();
    expect(share(300, 1000)).toBe(30);
    expect(share(300, undefined)).toBeUndefined();
  });

  it("reads CPU as a percentage, unknown when not measured", () => {
    expect(cpuPercent(METRICS)).toBeCloseTo(5);
    expect(cpuPercent({ ...METRICS, cpuLoad: -1 })).toBeUndefined();
    expect(formatPercent(5)).toBe("5.0%");
    expect(formatPercent(42.4)).toBe("42%");
    expect(loadColor(95)).toBe("red");
    expect(loadColor(75)).toBe("yellow");
    expect(loadColor(10)).toBe("teal");
  });

  it("writes sizes and durations for people", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1536 * MB)).toBe("1.5 GB");
    expect(formatBytes(300 * MB)).toBe("300 MB");
    expect(formatBytes(-1)).toBe("–");
    expect(formatUptime(45_000)).toBe("45 s");
    expect(formatUptime(3_900_000)).toBe("1 h 5 min");
    expect(formatUptime(2 * 86_400_000 + 4 * 3_600_000)).toBe("2 d 4 h");
  });
});
