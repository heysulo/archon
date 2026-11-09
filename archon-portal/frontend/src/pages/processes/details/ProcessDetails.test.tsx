import { fireEvent, screen, waitFor } from "@testing-library/react";
import { mockPortal, renderWith } from "../../../test/render";
import { snapshot } from "../../../test/fixtures";
import { ProcessDetails } from "./ProcessDetails";
import { editNotice, overridePreset, sourceLabel } from "./detailsModel";

const LEADER = snapshot.processes[0]!;
const config = (id: number, level: "GLOBAL" | "GROUP" | "APPLICATION", value: string, name = "ping.intervalMillis") =>
  ({ id, level, group: level === "GLOBAL" ? "" : "Group", application: level === "APPLICATION" ? "Application" : "",
    name, type: "INTEGER" as const, value, readOnly: false, description: "" });
const EFFECTIVE = [
  { effective: config(3, "APPLICATION", "250"), overridden: [config(1, "GLOBAL", "500")] },
  { effective: config(2, "GLOBAL", "1000", "archon.logs.memoryLines"), overridden: [] },
];
const ROUTE = "GET /api/configs/effective";

describe("ProcessDetails", () => {
  it("shows where the instance runs and what it sees", async () => {
    mockPortal({ [ROUTE]: { body: EFFECTIVE } });
    renderWith(<ProcessDetails process={LEADER} onClose={() => {}} />);
    expect(screen.getByText("hello-world-java-4vsbc")).toBeInTheDocument();
    expect(screen.getByText("1 h 5 min")).toBeInTheDocument();
    expect(screen.getByText("100 MB of 256 MB")).toBeInTheDocument();
    expect(await screen.findByText("250")).toBeInTheDocument();
    expect(screen.getByText("Global: 500")).toBeInTheDocument();
    expect(screen.getByText("archon.logs.memoryLines")).toBeInTheDocument();
  });

  it("overrides a global configuration for the application alone", async () => {
    const calls = mockPortal({ [ROUTE]: { body: EFFECTIVE }, "POST /api/configs": { status: 201, body: {} } });
    renderWith(<ProcessDetails process={LEADER} onClose={() => {}} />);
    fireEvent.click(await screen.findByLabelText("Actions for archon.logs.memoryLines"));
    fireEvent.click(await screen.findByRole("menuitem", { name: "Override for Application" }));
    expect(await screen.findByText(/Only Group:Application sees this value/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Value"), { target: { value: "50" } });
    fireEvent.click(screen.getByRole("button", { name: "Add configuration" }));
    await waitFor(() => expect(calls.find((call) => call.method === "POST")?.body).toMatchObject(
      { level: "APPLICATION", group: "Group", application: "Application", name: "archon.logs.memoryLines",
        value: "50" }));
  });

  it("removes an override, going back to the value below", async () => {
    const calls = mockPortal({ [ROUTE]: { body: EFFECTIVE }, "DELETE /api/configs/3": { status: 204 } });
    renderWith(<ProcessDetails process={LEADER} onClose={() => {}} />);
    fireEvent.click(await screen.findByLabelText("Actions for ping.intervalMillis"));
    fireEvent.click(await screen.findByRole("menuitem", { name: "Remove override" }));
    expect(await screen.findByText(/goes back to the global value, 500/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Remove override" }));
    await waitFor(() => expect(calls.some((call) => call.method === "DELETE")).toBe(true));
  });

  it("hides configurations from those who cannot see them", () => {
    const calls = mockPortal({ [ROUTE]: { body: EFFECTIVE } });
    renderWith(<ProcessDetails process={LEADER} onClose={() => {}} />,
      { user: { id: 2, username: "pat", displayName: "Pat", roles: ["ops"], permissions: ["processes:view"] } });
    expect(screen.queryByText("Configurations")).not.toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });
});

describe("details model", () => {
  it("says where a value comes from and what changing it reaches", () => {
    expect(sourceLabel({ level: "GROUP", group: "Shop" })).toBe("Group Shop");
    expect(editNotice(config(1, "GLOBAL", "1"), "Shop:Cart")).toMatch(/every application/);
    expect(editNotice(config(1, "APPLICATION", "1"), "Shop:Cart")).toBeUndefined();
    expect(overridePreset(config(1, "GLOBAL", "500"), "Shop", "Cart"))
      .toMatchObject({ level: "APPLICATION", group: "Shop", application: "Cart", value: "500" });
  });
});
