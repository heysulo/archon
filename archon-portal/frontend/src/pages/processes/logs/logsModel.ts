/** One line an application instance logged, as the portal relays it. */
export interface LogLine {
  /** Milliseconds since the epoch. */
  time: number;
  /** TRACE, DEBUG, INFO, WARN or ERROR, or STDOUT / STDERR for console output without a level. */
  level: string;
  logger: string;
  thread: string;
  message: string;
}

/** What each `lines` event of the stream holds. */
export interface LogBatch {
  lines: LogLine[];
  /** Lines lost because they came faster than they could be sent. */
  dropped: number;
  /** Whether these are the lines the instance kept from before the stream started. */
  history: boolean;
  /** News about the stream, such as the instance disconnecting. */
  status?: string;
}

/** A row of the viewer: a line, or a note about the stream. */
export type LogEntry = { kind: "line"; id: number; line: LogLine } | { kind: "note"; id: number; text: string };

export interface LogState {
  entries: LogEntry[];
  dropped: number;
  nextId: number;
}

/** Older entries go once there are this many, so a long session does not use up the browser. */
export const MAX_ENTRIES = 5_000;

export const EMPTY: LogState = { entries: [], dropped: 0, nextId: 0 };

const lastLine = (entries: LogEntry[]): LogLine | undefined => {
  for (let i = entries.length - 1; i >= 0; i--) {
    const entry = entries[i]!;
    if (entry.kind === "line") return entry.line;
  }
  return undefined;
};

/**
 * Adds a batch. A history batch after reconnecting repeats lines already shown, so only its lines newer than the
 * last one shown are kept.
 */
export function addBatch(state: LogState, batch: LogBatch): LogState {
  let nextId = state.nextId;
  const added: LogEntry[] = [];
  const last = batch.history ? lastLine(state.entries) : undefined;
  for (const line of batch.lines) {
    if (last && line.time <= last.time) continue;
    added.push({ kind: "line", id: nextId++, line });
  }
  if (batch.dropped > 0) {
    added.push({ kind: "note", id: nextId++, text: `${batch.dropped} lines dropped: they came faster than they could be sent` });
  }
  if (batch.status) added.push({ kind: "note", id: nextId++, text: batch.status });
  if (added.length === 0) return state;
  const entries = [...state.entries, ...added];
  return {
    entries: entries.length > MAX_ENTRIES ? entries.slice(entries.length - MAX_ENTRIES) : entries,
    dropped: state.dropped + batch.dropped,
    nextId,
  };
}

/** The levels in the order filters list them. */
export const LEVELS = ["ERROR", "WARN", "INFO", "DEBUG", "TRACE", "STDERR", "STDOUT"];

export function levelColor(level: string): string {
  switch (level) {
    case "ERROR":
    case "STDERR":
      return "red";
    case "WARN":
      return "yellow";
    case "INFO":
      return "blue";
    case "DEBUG":
    case "TRACE":
      return "gray";
    default:
      return "dark";
  }
}

/** Whether a line passes the filters: one of the chosen levels (any if none chosen), containing the text. */
export function matches(line: LogLine, levels: string[], text: string): boolean {
  if (levels.length > 0 && !levels.includes(line.level)) return false;
  const needle = text.trim().toLowerCase();
  if (!needle) return true;
  return [line.message, line.logger, line.thread].some((field) => field.toLowerCase().includes(needle));
}

/** The logger's last part, as `ArchonInterface` for `dev.heysulo.archon.sdk.ArchonInterface`. */
export function shortLogger(logger: string): string {
  const dot = logger.lastIndexOf(".");
  return dot < 0 ? logger : logger.slice(dot + 1);
}

const pad = (value: number, width = 2) => String(value).padStart(width, "0");

/** Local time with milliseconds, as 14:03:07.215. */
export function clock(time: number): string {
  const date = new Date(time);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`;
}

/** The lines as a text file: ISO time, level, thread, logger and message, one per line. */
export function toText(lines: LogLine[]): string {
  return lines.map((line) => [new Date(line.time).toISOString(), line.level.padEnd(5),
    line.thread ? `[${line.thread}]` : "", line.logger, line.logger ? "-" : "", line.message]
    .filter(Boolean).join(" ")).join("\n") + (lines.length ? "\n" : "");
}
