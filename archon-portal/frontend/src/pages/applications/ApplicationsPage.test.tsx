import { MemoryRouter } from "react-router";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { ProcessStreamContext, type ProcessStreamState } from "../../api/processStream";
import { mockPortal, renderWith } from "../../test/render";
import { ApplicationsPage } from "./ApplicationsPage";

const DEFINITIONS = [
  { group: "Archon", name: "Portal", description: "Part of Archon", builtIn: true },
  { group: "Group", name: "Application", description: "The example", builtIn: false },
  { group: "Shop", name: "Cart", description: "", builtIn: false },
];

const SERVICES = [
  { id: "ping", name: "Ping", portOffset: 1, group: "Group", application: "Application", mode: "PRIMARY_MIRROR",
    managedByArchon: false },
  { id: "carts", name: "Carts", portOffset: 2, group: "Group", application: "Application", mode: "LOAD_BALANCE",
    managedByArchon: false },
  { id: "archon-config", name: "Config", portOffset: 0, group: "Archon", application: "Config",
    mode: "PRIMARY_MIRROR", managedByArchon: true },
];

const STREAM = {
  status: "live",
  error: null,
  snapshot: {
    registry: "10.21.3.202",
    term: 3,
    processes: [
      { group: "Group", name: "Application", rank: 1, runLevel: "RUNNING", running: true, connected: true,
        services: ["ping@10.0.0.5:9001"] },
      { group: "Old", name: "Thing", rank: 1, runLevel: "RUNNING", running: true, connected: true, services: [] },
    ],
  },
} as unknown as ProcessStreamState;

function renderPage(options?: Parameters<typeof renderWith>[1]) {
  return renderWith(
    <ProcessStreamContext.Provider value={STREAM}>
      <MemoryRouter><ApplicationsPage /></MemoryRouter>
    </ProcessStreamContext.Provider>,
    options,
  );
}

describe("ApplicationsPage", () => {
  beforeEach(() => mockPortal({ "GET /api/applications": { body: DEFINITIONS }, "GET /api/services": { body: SERVICES } }));

  it("lists definitions with their running instances, Archon's own locked", async () => {
    renderPage();
    expect(await screen.findByText("Group:Application")).toBeInTheDocument();
    expect(screen.getByText("1 instance").closest("a")).toHaveAttribute("href",
      "/processes?application=Group%3AApplication");
    expect(screen.getByLabelText("Part of Archon")).toBeInTheDocument();
    expect(screen.queryByLabelText("Actions for Archon:Portal")).not.toBeInTheDocument();
    expect(screen.getByText(/Running but not defined: Old:Thing/)).toBeInTheDocument();
  });

  it("does not offer removing a running application", async () => {
    renderPage();
    fireEvent.click(await screen.findByLabelText("Actions for Group:Application"));
    expect(await screen.findByRole("menuitem", { name: /Remove \(1 instance running\)/ })).toBeDisabled();
  });

  it("is read-only to those who can only view", async () => {
    renderPage({ user: { id: 9, username: "vera", displayName: "Vera", roles: ["auditor"],
      permissions: ["applications:view"] } });
    expect(await screen.findByText("Shop:Cart")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Define application" })).not.toBeInTheDocument();
    expect(screen.queryByText("Not running")).not.toBeInTheDocument();
  });

  it("gives services to an application, taking them from their owner", async () => {
    const calls = mockPortal({
      "GET /api/applications": { body: DEFINITIONS },
      "GET /api/services": { body: SERVICES },
      "PUT /api/services/carts/owner": { body: { ...SERVICES[1], group: "Shop", application: "Cart" } },
    });
    renderPage();
    fireEvent.click(await screen.findByLabelText("Actions for Shop:Cart"));
    fireEvent.click(await screen.findByRole("menuitem", { name: "Assign services" }));
    // Running, so it keeps its owner; Archon's own are never offered
    expect(await screen.findByRole("checkbox", { name: /ping/ })).toBeDisabled();
    expect(screen.queryByRole("checkbox", { name: /archon-config/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: /carts/ }));
    fireEvent.click(screen.getByRole("button", { name: "Assign" }));
    await waitFor(() => expect(calls.some((call) => call.method === "PUT")).toBe(true));
    expect(calls.find((call) => call.method === "PUT")).toMatchObject(
      { path: "/api/services/carts/owner", body: { group: "Shop", application: "Cart" } });
  });
});
