import { MemoryRouter } from "react-router";
import { fireEvent, screen } from "@testing-library/react";
import { ProcessStreamContext, type ProcessStreamState } from "../../api/processStream";
import { mockPortal, renderWith } from "../../test/render";
import { ServicesPage } from "./ServicesPage";

const SERVICES = [
  { id: "archon-config", name: "Archon configuration", portOffset: 1, group: "Archon", application: "Config",
    mode: "PRIMARY_MIRROR", managedByArchon: true },
  { id: "ping", name: "Ping", portOffset: 0, group: "Group", application: "Application", mode: "PRIMARY_MIRROR",
    managedByArchon: false },
  { id: "reports", name: "Reports", portOffset: 3, group: "Group", application: "Application", mode: "LOAD_BALANCE",
    managedByArchon: false },
];

const STREAM: ProcessStreamState = {
  status: "live",
  error: null,
  snapshot: {
    registry: "10.21.3.202",
    term: 3,
    processes: [
      { group: "Group", name: "Application", rank: 1, runLevel: "RUNNING", running: true, connected: true,
        services: ["ping@10.0.0.5:9000"] },
      { group: "Group", name: "Application", rank: 2, runLevel: "RUNNING", running: true, connected: true,
        services: ["ping@10.0.0.6:9000", "retired@10.0.0.6:9009"] },
    ],
  } as unknown as ProcessStreamState["snapshot"],
};

function renderPage(options?: Parameters<typeof renderWith>[1]) {
  return renderWith(
    <ProcessStreamContext.Provider value={STREAM}>
      <MemoryRouter><ServicesPage /></MemoryRouter>
    </ProcessStreamContext.Provider>,
    options,
  );
}

describe("ServicesPage", () => {
  beforeEach(() => mockPortal({
    "GET /api/services": { body: SERVICES },
    "GET /api/applications": { body: [
      { group: "Archon", name: "Config", description: "", builtIn: true },
      { group: "Group", name: "Application", description: "", builtIn: false },
      { group: "Shop", name: "Cart", description: "", builtIn: false },
    ] },
  }));

  it("lists services with the instances serving them", async () => {
    renderPage();
    expect(await screen.findByText("ping")).toBeInTheDocument();
    expect(screen.getByText("#1 10.0.0.5:9000")).toBeInTheDocument();
    expect(screen.getByText("#2 10.0.0.6:9000")).toBeInTheDocument();
    expect(screen.getAllByText("Not running")).toHaveLength(2);
    expect(screen.getByText(/no longer defined: retired/)).toBeInTheDocument();
  });

  it("locks Archon's own services", async () => {
    renderPage();
    expect(await screen.findByLabelText("Managed by Archon")).toBeInTheDocument();
    expect(screen.queryByLabelText("Actions for archon-config")).not.toBeInTheDocument();
  });

  it("offers changes only to services nothing runs", async () => {
    renderPage();
    expect(await screen.findByLabelText("ping is running")).toBeInTheDocument();
    expect(screen.queryByLabelText("Actions for ping")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Actions for reports")).toBeInTheDocument();
  });

  it("starts a new service on the lowest free port offset", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Add service" }));
    // ping holds 0, archon-config 1 and reports 3
    expect(await screen.findByLabelText("Port offset")).toHaveValue("2");
  });

  it("picks the owner from the defined applications", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Add service" }));
    fireEvent.click(await screen.findByPlaceholderText("Choose an application"));
    // Rendered while closed too, so hidden ones count
    expect(await screen.findByRole("option", { name: "Shop:Cart", hidden: true })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Group:Application", hidden: true })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Archon:Config", hidden: true })).not.toBeInTheDocument();
  });

  it("offers no changes to users who can only view", async () => {
    renderPage({ user: { id: 2, username: "victor", displayName: "Victor", roles: ["viewer"],
      permissions: ["services:view"] } });
    expect(await screen.findByText("ping")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add service" })).not.toBeInTheDocument();
    expect(screen.queryByText("Not running")).not.toBeInTheDocument();
  });
});
