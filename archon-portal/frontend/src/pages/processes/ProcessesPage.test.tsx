import { MantineProvider } from "@mantine/core";
import { fireEvent, render, screen } from "@testing-library/react";
import { ProcessStreamContext, type ProcessStreamState } from "../../api/processStream";
import { snapshot } from "../../test/fixtures";
import { ProcessesPage } from "./ProcessesPage";

function renderPage(state: ProcessStreamState) {
  return render(
    <MantineProvider>
      <ProcessStreamContext.Provider value={state}>
        <ProcessesPage />
      </ProcessStreamContext.Provider>
    </MantineProvider>,
  );
}

describe("ProcessesPage", () => {
  beforeEach(() => localStorage.clear());

  it("lists running instances with where they run", () => {
    renderPage({ snapshot, status: "live", error: null });

    expect(screen.getByText("hello-world-java-4vsbc")).toBeInTheDocument();
    expect(screen.getByText("192.168.192.78")).toBeInTheDocument();
    expect(screen.getByText("ping:9001")).toBeInTheDocument();
    expect(screen.getByText("archon-registry-1")).toBeInTheDocument();
    expect(screen.queryByText("hello-world-java-dead1")).not.toBeInTheDocument();
  });

  it("shows stopped instances when switched on", () => {
    renderPage({ snapshot, status: "live", error: null });
    fireEvent.click(screen.getByLabelText("Show stopped"));
    expect(screen.getByText("hello-world-java-dead1")).toBeInTheDocument();
    expect(screen.getByText("Former standby")).toBeInTheDocument();
  });

  it("filters by search", () => {
    renderPage({ snapshot, status: "live", error: null });
    fireEvent.change(screen.getByLabelText("Search processes"), { target: { value: "registry-0" } });
    expect(screen.getByText("archon-registry-0")).toBeInTheDocument();
    expect(screen.queryByText("hello-world-java-4vsbc")).not.toBeInTheDocument();
  });

  it("shows every application in one table, sorted by a clicked column", () => {
    renderPage({ snapshot, status: "live", error: null });
    expect(screen.getAllByRole("table")).toHaveLength(1);
    const firstHost = () => screen.getAllByRole("row")[1]!.textContent;
    expect(firstHost()).toContain("hello-world-java-4vsbc");
    fireEvent.click(screen.getByLabelText("Sort by host"));
    expect(firstHost()).toContain("archon-registry-0");
    fireEvent.click(screen.getByLabelText("Sort by host"));
    expect(firstHost()).toContain("hello-world-java-94fdt");
  });

  it("warns when the registry is unavailable but keeps the last state", () => {
    renderPage({ snapshot, status: "offline", error: "No primary registry is answering" });
    expect(screen.getByText(/No primary registry is answering/)).toBeInTheDocument();
    expect(screen.getByText(/Showing the last known state/)).toBeInTheDocument();
    expect(screen.getByText("hello-world-java-4vsbc")).toBeInTheDocument();
  });
});
