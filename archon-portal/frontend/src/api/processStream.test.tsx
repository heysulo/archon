import { act, render, screen } from "@testing-library/react";
import { ProcessStreamProvider, useProcessStream } from "./processStream";
import { snapshot } from "../test/fixtures";

/** Stands in for EventSource so the test decides what the portal sends. */
class FakeSource extends EventTarget {
  closed = false;
  close() {
    this.closed = true;
  }
  send(type: string, data?: unknown) {
    this.dispatchEvent(new MessageEvent(type, { data: data === undefined ? undefined : JSON.stringify(data) }));
  }
}

function Probe() {
  const { status, snapshot: current, error } = useProcessStream();
  return (
    <div>
      <span data-testid="status">{status}</span>
      <span data-testid="registry">{current?.registry ?? "none"}</span>
      <span data-testid="error">{error ?? ""}</span>
    </div>
  );
}

function renderWithSource() {
  const source = new FakeSource();
  const view = render(
    <ProcessStreamProvider createSource={() => source as unknown as EventSource}>
      <Probe />
    </ProcessStreamProvider>,
  );
  return { source, view };
}

describe("ProcessStreamProvider", () => {
  it("goes live with each pushed snapshot", () => {
    const { source } = renderWithSource();
    expect(screen.getByTestId("status")).toHaveTextContent("connecting");

    act(() => source.send("snapshot", snapshot));
    expect(screen.getByTestId("status")).toHaveTextContent("live");
    expect(screen.getByTestId("registry")).toHaveTextContent("10.21.3.202");

    act(() => source.send("snapshot", { ...snapshot, registry: "10.21.3.203" }));
    expect(screen.getByTestId("registry")).toHaveTextContent("10.21.3.203");
  });

  it("keeps the last snapshot while the registry is offline", () => {
    const { source } = renderWithSource();
    act(() => source.send("snapshot", snapshot));
    act(() => source.send("offline", { error: "No primary registry is answering" }));

    expect(screen.getByTestId("status")).toHaveTextContent("offline");
    expect(screen.getByTestId("error")).toHaveTextContent("No primary registry is answering");
    expect(screen.getByTestId("registry")).toHaveTextContent("10.21.3.202");
  });

  it("reports a broken stream as reconnecting, and closes it when unmounted", () => {
    const { source, view } = renderWithSource();
    act(() => source.send("error"));
    expect(screen.getByTestId("status")).toHaveTextContent("reconnecting");

    view.unmount();
    expect(source.closed).toBe(true);
  });
});
