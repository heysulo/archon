import { fireEvent, screen } from "@testing-library/react";
import { ProcessStreamContext, type ProcessStreamState } from "../../api/processStream";
import { mockPortal, renderWith } from "../../test/render";
import { ApplicationsPage } from "./ApplicationsPage";

const DEFINITIONS = [
  { group: "Archon", name: "Portal", description: "Part of Archon", builtIn: true },
  { group: "Group", name: "Application", description: "The example", builtIn: false },
  { group: "Shop", name: "Cart", description: "", builtIn: false },
];

const STREAM = {
  status: "live",
  error: null,
  snapshot: {
    registry: "10.21.3.202",
    term: 3,
    processes: [
      { group: "Group", name: "Application", rank: 1, runLevel: "RUNNING", running: true, connected: true,
        services: [] },
      { group: "Old", name: "Thing", rank: 1, runLevel: "RUNNING", running: true, connected: true, services: [] },
    ],
  },
} as unknown as ProcessStreamState;

function renderPage(options?: Parameters<typeof renderWith>[1]) {
  return renderWith(
    <ProcessStreamContext.Provider value={STREAM}>
      <ApplicationsPage />
    </ProcessStreamContext.Provider>,
    options,
  );
}

describe("ApplicationsPage", () => {
  beforeEach(() => mockPortal({ "GET /api/applications": { body: DEFINITIONS }, "GET /api/services": { body: [] } }));

  it("lists definitions with their running instances, Archon's own locked", async () => {
    renderPage();
    expect(await screen.findByText("Group:Application")).toBeInTheDocument();
    expect(screen.getByText("1 instance")).toBeInTheDocument();
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
});
