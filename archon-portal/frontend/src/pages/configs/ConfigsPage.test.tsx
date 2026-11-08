import { screen } from "@testing-library/react";
import { mockPortal, renderWith } from "../../test/render";
import { ConfigsPage } from "./ConfigsPage";

const CONFIGS = [
  { id: 1, level: "GLOBAL", group: "", application: "", name: "ping.intervalMillis", type: "INTEGER", value: "1000",
    readOnly: false, description: "Default ping interval", updatedAt: "2026-11-08T12:00:00Z", updatedBy: "admin" },
  { id: 2, level: "APPLICATION", group: "Group", application: "Application", name: "ping.intervalMillis",
    type: "INTEGER", value: "500", readOnly: true, description: "", updatedAt: "2026-11-08T12:00:00Z",
    updatedBy: "admin" },
];

describe("ConfigsPage", () => {
  beforeEach(() => mockPortal({ "GET /api/configs": { body: CONFIGS } }));

  it("lists configurations with who sees them", async () => {
    renderWith(<ConfigsPage />);
    expect(await screen.findByText("Everyone")).toBeInTheDocument();
    expect(screen.getByText("Group / Application")).toBeInTheDocument();
    expect(screen.getByLabelText("Read only")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add configuration" })).toBeInTheDocument();
    // Rows with the same name at different levels are told apart
    expect(screen.getByLabelText("Actions for ping.intervalMillis (Everyone)")).toBeInTheDocument();
    expect(screen.getByLabelText("Actions for ping.intervalMillis (Group / Application)")).toBeInTheDocument();
  });

  it("offers no changes to users who can only view", async () => {
    renderWith(<ConfigsPage />, {
      user: { id: 2, username: "victor", displayName: "Victor", roles: ["viewer"], permissions: ["configs:view"] },
    });
    expect(await screen.findByText("Everyone")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add configuration" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Actions for/)).not.toBeInTheDocument();
  });
});
