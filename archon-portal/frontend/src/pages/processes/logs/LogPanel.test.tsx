import { act, fireEvent, screen } from "@testing-library/react";
import { renderWith } from "../../../test/render";
import { snapshot } from "../../../test/fixtures";
import { LogPanel, logsUrl } from "./LogPanel";

class FakeSource extends EventTarget {
  closed = false;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  close() {
    this.closed = true;
  }
  send(type: string, data: unknown) {
    this.dispatchEvent(new MessageEvent(type, { data: JSON.stringify(data) }));
  }
}

const LEADER = snapshot.processes[0]!;
const line = (time: number, message: string, level = "INFO") =>
  ({ time, level, logger: "com.example.Main", thread: "main", message });

function open() {
  const source = new FakeSource();
  const urls: string[] = [];
  const view = renderWith(<LogPanel process={LEADER} source={(url) => {
    urls.push(url);
    return source as unknown as EventSource;
  }} />);
  act(() => source.onopen?.());
  return { source, urls, view };
}

describe("LogPanel", () => {
  it("streams an instance's lines", async () => {
    const { source, urls } = open();
    expect(urls).toEqual(["/api/processes/Group/Application/1/logs"]);
    act(() => source.send("lines", { lines: [line(1, "Warming up")], dropped: 0, history: true }));
    act(() => source.send("lines", { lines: [line(2, "Ping failed", "ERROR")], dropped: 2, history: false }));
    expect(await screen.findByText("Warming up")).toBeInTheDocument();
    expect(screen.getByText("Ping failed")).toBeInTheDocument();
    expect(screen.getByText(/2 lines dropped/)).toBeInTheDocument();
    expect(screen.getByText("Live")).toBeInTheDocument();
  });

  it("holds lines while paused and shows them on resuming", async () => {
    const { source } = open();
    fireEvent.click(await screen.findByLabelText("Pause"));
    act(() => source.send("lines", { lines: [line(1, "Later")], dropped: 0, history: false }));
    expect(screen.queryByText("Later")).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("Resume"));
    expect(await screen.findByText("Later")).toBeInTheDocument();
  });

  it("filters by text", async () => {
    const { source } = open();
    act(() => source.send("lines", { lines: [line(1, "alpha"), line(2, "beta")], dropped: 0, history: false }));
    fireEvent.change(await screen.findByLabelText("Filter log lines"), { target: { value: "beta" } });
    expect(screen.queryByText("alpha")).not.toBeInTheDocument();
    expect(screen.getByText("beta")).toBeInTheDocument();
  });

  it("stops when the session ends", () => {
    const { source } = open();
    act(() => source.send("signedout", {}));
    expect(source.closed).toBe(true);
    expect(screen.getByText(/Your session ended/)).toBeInTheDocument();
  });

  it("builds the stream's address", () => {
    expect(logsUrl({ group: "My Group", name: "App", rank: 2 })).toBe("/api/processes/My%20Group/App/2/logs");
  });
});
