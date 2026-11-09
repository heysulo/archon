import { ActionIcon, Alert, Badge, Box, Button, Drawer, Group, MultiSelect, ScrollArea, Stack, Text, TextInput,
  Tooltip } from "@mantine/core";
import { IconArrowDown, IconDownload, IconEraser, IconPlayerPause, IconPlayerPlay, IconSearch }
  from "@tabler/icons-react";
import { useEffect, useMemo, useRef, useState } from "react";
import type { ProcessInfo } from "../../../api/types";
import { addBatch, clock, EMPTY, LEVELS, levelColor, matches, shortLogger, toText, type LogBatch, type LogLine,
  type LogState } from "./logsModel";

type SourceFactory = (url: string) => EventSource;
const openEventSource: SourceFactory = (url) => new EventSource(url);

/** Where an instance's live logs stream from. */
export const logsUrl = (process: Pick<ProcessInfo, "group" | "name" | "rank">) =>
  `/api/processes/${encodeURIComponent(process.group)}/${encodeURIComponent(process.name)}/${process.rank}/logs`;

/** Renders at most this many of the matching lines, the newest, to stay quick. */
const SHOWN = 2_000;

interface Props {
  /** The instance to watch, or null when closed. */
  process: ProcessInfo | null;
  onClose: () => void;
  /** Lets tests stand in for the browser's EventSource. */
  source?: SourceFactory;
}

export function LogViewer({ process, onClose, source = openEventSource }: Props) {
  const title = process ? `${process.group}:${process.name} #${process.rank}${process.details?.host
    ? ` · ${process.details.host}` : ""}` : "";
  return (
    <Drawer opened={process !== null} onClose={onClose} position="right" size="80%" title={title}
            styles={{ body: { height: "calc(100% - 60px)", display: "flex", flexDirection: "column" } }}>
      {process && <Logs key={logsUrl(process)} process={process} source={source} />}
    </Drawer>
  );
}

type Connection = "connecting" | "live" | "reconnecting" | "signedout";

function Logs({ process, source }: { process: ProcessInfo; source: SourceFactory }) {
  const [state, setState] = useState<LogState>(EMPTY);
  const [connection, setConnection] = useState<Connection>("connecting");
  const [paused, setPaused] = useState(false);
  const [levels, setLevels] = useState<string[]>([]);
  const [text, setText] = useState("");
  const [follow, setFollow] = useState(true);
  // Batches that arrive while paused wait here, so pausing never loses lines
  const pending = useRef<LogBatch[]>([]);
  const [waiting, setWaiting] = useState(0);
  const pausedRef = useRef(paused);
  useEffect(() => {
    pausedRef.current = paused;
  }, [paused]);
  const viewport = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const events = source(logsUrl(process));
    events.onopen = () => setConnection("live");
    events.onerror = () => setConnection((current) => (current === "signedout" ? current : "reconnecting"));
    events.addEventListener("lines", (event) => {
      const batch = JSON.parse((event as MessageEvent).data) as LogBatch;
      if (pausedRef.current) {
        pending.current.push(batch);
        setWaiting((count) => count + batch.lines.length);
      } else {
        setState((current) => addBatch(current, batch));
      }
    });
    events.addEventListener("signedout", () => {
      setConnection("signedout");
      events.close();
    });
    return () => events.close();
  }, [process, source]);

  const pause = () => {
    pausedRef.current = true;
    setPaused(true);
  };

  const resume = () => {
    const held = pending.current;
    pending.current = [];
    pausedRef.current = false;
    setWaiting(0);
    setState((current) => held.reduce(addBatch, current));
    setPaused(false);
  };

  const shown = useMemo(() => {
    const filtered = state.entries.filter((entry) => entry.kind === "note" || matches(entry.line, levels, text));
    return filtered.length > SHOWN ? filtered.slice(filtered.length - SHOWN) : filtered;
  }, [state.entries, levels, text]);

  useEffect(() => {
    if (follow && viewport.current) viewport.current.scrollTop = viewport.current.scrollHeight;
  }, [shown, follow]);

  const download = () => {
    const lines = state.entries.flatMap((entry) =>
      entry.kind === "line" && matches(entry.line, levels, text) ? [entry.line] : []);
    const blob = new Blob([toText(lines)], { type: "text/plain" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = `${process.group}-${process.name}-${process.rank}.log`;
    link.click();
    URL.revokeObjectURL(link.href);
  };

  const lineCount = state.entries.filter((entry) => entry.kind === "line").length;
  return (
    <Stack gap="sm" style={{ flex: 1, minHeight: 0 }}>
      <Group gap="sm" align="flex-end">
        <TextInput style={{ flex: 1, minWidth: 180 }} placeholder="Filter by text, logger or thread"
                   leftSection={<IconSearch size={16} />} value={text}
                   onChange={(event) => setText(event.currentTarget.value)} aria-label="Filter log lines" />
        <MultiSelect style={{ minWidth: 200 }} data={LEVELS} value={levels} onChange={setLevels} clearable
                     placeholder={levels.length ? undefined : "All levels"} aria-label="Filter by level" />
        <Tooltip label={paused ? "Resume" : "Pause: new lines wait until you resume"}>
          <ActionIcon variant="default" size="lg" onClick={() => (paused ? resume() : pause())}
                      aria-label={paused ? "Resume" : "Pause"}>
            {paused ? <IconPlayerPlay size={16} /> : <IconPlayerPause size={16} />}
          </ActionIcon>
        </Tooltip>
        <Tooltip label={follow ? "Following new lines" : "Follow new lines"}>
          <ActionIcon variant={follow ? "filled" : "default"} size="lg" onClick={() => setFollow(!follow)}
                      aria-label="Follow new lines" aria-pressed={follow}>
            <IconArrowDown size={16} />
          </ActionIcon>
        </Tooltip>
        <Tooltip label="Clear the screen">
          <ActionIcon variant="default" size="lg" onClick={() => setState((current) => ({ ...EMPTY,
            nextId: current.nextId }))} aria-label="Clear">
            <IconEraser size={16} />
          </ActionIcon>
        </Tooltip>
        <Button variant="default" leftSection={<IconDownload size={16} />} onClick={download}
                disabled={lineCount === 0}>
          Download
        </Button>
      </Group>

      <Group gap="xs">
        <Badge variant="light" radius="sm" color={connection === "live" ? "green" : connection === "signedout"
          ? "gray" : "yellow"}>
          {connection === "live" ? "Live" : connection === "signedout" ? "Signed out" : connection === "connecting"
            ? "Connecting" : "Reconnecting"}
        </Badge>
        {paused && (
          <Badge variant="light" radius="sm" color="yellow">
            Paused{waiting ? ` · ${waiting} waiting` : ""}
          </Badge>
        )}
        <Text size="xs" c="dimmed">
          {lineCount} lines{state.dropped ? ` · ${state.dropped} dropped` : ""}
          {shown.length < state.entries.length ? ` · ${shown.length} shown` : ""}
        </Text>
      </Group>

      {connection === "signedout" && (
        <Alert color="gray" variant="light">Your session ended. Sign in again to keep watching.</Alert>
      )}

      <ScrollArea viewportRef={viewport} style={{ flex: 1, minHeight: 0 }} type="auto"
                  onScrollPositionChange={({ y }) => {
                    const element = viewport.current;
                    if (element && follow && y + element.clientHeight < element.scrollHeight - 40) setFollow(false);
                  }}>
        <Box ff="monospace" fz="xs" style={{ lineHeight: 1.5 }} aria-label="Log lines" role="log">
          {shown.length === 0 ? (
            <Text size="sm" c="dimmed" p="sm">
              {connection === "live" ? "Waiting for lines" : "Connecting to the instance"}
            </Text>
          ) : (
            shown.map((entry) => entry.kind === "note" ? (
              <Text key={entry.id} size="xs" c="dimmed" fs="italic" py={2}>— {entry.text}</Text>
            ) : (
              <Line key={entry.id} line={entry.line} />
            ))
          )}
        </Box>
      </ScrollArea>
    </Stack>
  );
}

function Line({ line }: { line: LogLine }) {
  return (
    <Group gap={8} wrap="nowrap" align="flex-start" style={{ borderBottom: "1px solid var(--mantine-color-default-border)" }}>
      <Text span ff="monospace" fz="xs" c="dimmed" style={{ flexShrink: 0 }}>{clock(line.time)}</Text>
      <Text span ff="monospace" fz="xs" fw={600} c={levelColor(line.level)} w={52} style={{ flexShrink: 0 }}>
        {line.level}
      </Text>
      {line.logger && (
        <Tooltip label={`${line.logger}${line.thread ? ` · ${line.thread}` : ""}`} openDelay={400}>
          <Text span ff="monospace" fz="xs" c="dimmed" maw={180} truncate style={{ flexShrink: 0 }}>
            {shortLogger(line.logger)}
          </Text>
        </Tooltip>
      )}
      <Text span ff="monospace" fz="xs" style={{ whiteSpace: "pre-wrap", wordBreak: "break-word", minWidth: 0 }}>
        {line.message}
      </Text>
    </Group>
  );
}
