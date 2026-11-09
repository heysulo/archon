import { addBatch, clock, EMPTY, matches, MAX_ENTRIES, shortLogger, toText, type LogLine } from "./logsModel";

const line = (time: number, message: string, level = "INFO"): LogLine =>
  ({ time, level, logger: "com.example.Main", thread: "main", message });

describe("addBatch", () => {
  it("adds lines, and notes for dropped lines and stream news", () => {
    const state = addBatch(EMPTY, { lines: [line(1, "a"), line(2, "b")], dropped: 3, history: false,
      status: "The instance disconnected" });
    expect(state.entries.map((entry) => entry.kind)).toEqual(["line", "line", "note", "note"]);
    expect(state.dropped).toBe(3);
  });

  it("keeps only the new lines of a history batch sent again after reconnecting", () => {
    const first = addBatch(EMPTY, { lines: [line(1, "a"), line(2, "b")], dropped: 0, history: true });
    const again = addBatch(first, { lines: [line(1, "a"), line(2, "b"), line(3, "c")], dropped: 0, history: true });
    expect(again.entries.flatMap((entry) => entry.kind === "line" ? [entry.line.message] : []))
      .toEqual(["a", "b", "c"]);
  });

  it("keeps the newest entries only", () => {
    const many = Array.from({ length: MAX_ENTRIES + 10 }, (_, i) => line(i, `m${i}`));
    const state = addBatch(EMPTY, { lines: many, dropped: 0, history: false });
    expect(state.entries).toHaveLength(MAX_ENTRIES);
    expect(state.entries[0]).toMatchObject({ line: { message: "m10" } });
  });
});

describe("log lines", () => {
  it("filter by level and by text in message, logger or thread", () => {
    expect(matches(line(1, "Started"), [], "")).toBe(true);
    expect(matches(line(1, "Started"), ["ERROR"], "")).toBe(false);
    expect(matches(line(1, "Started", "ERROR"), ["ERROR"], "start")).toBe(true);
    expect(matches(line(1, "Started"), [], "example.main")).toBe(true);
    expect(matches(line(1, "Started"), [], "nothing")).toBe(false);
  });

  it("read well", () => {
    expect(shortLogger("dev.heysulo.archon.sdk.ArchonInterface")).toBe("ArchonInterface");
    expect(clock(new Date(2026, 10, 9, 14, 3, 7, 215).getTime())).toBe("14:03:07.215");
    expect(toText([line(Date.UTC(2026, 10, 9), "Started")]))
      .toBe("2026-11-09T00:00:00.000Z INFO  [main] com.example.Main - Started\n");
  });
});
