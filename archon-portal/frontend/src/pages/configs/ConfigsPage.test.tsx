import { screen } from "@testing-library/react";
import { mockPortal, renderWith } from "../../test/render";
import { ConfigsPage } from "./ConfigsPage";
import { configsPath } from "./configsApi";

const CONFIGS = [
  { id: 1, level: "GLOBAL", group: "", application: "", name: "ping.intervalMillis", type: "INTEGER", value: "1000",
    readOnly: false, description: "Default ping interval", updatedAt: "2026-11-08T12:00:00Z", updatedBy: "admin" },
  { id: 2, level: "APPLICATION", group: "Group", application: "Application", name: "ping.intervalMillis",
    type: "INTEGER", value: "500", readOnly: true, description: "", updatedAt: "2026-11-08T12:00:00Z",
    updatedBy: "admin" },
];

describe("ConfigsPage", () => {
  it("asks the portal for one page, filtered by level", async () => {
    expect(configsPath({ query: " ping ", level: "GROUP", page: 3, pageSize: 50 }))
      .toBe("/api/configs?offset=100&limit=50&q=ping&level=GROUP");
    expect(configsPath({ query: "", level: null, page: 1, pageSize: 50 })).toBe("/api/configs?offset=0&limit=50");
  });

  it("shows where the page is in the whole list", async () => {
    mockPortal({ "GET /api/configs": { body: { items: CONFIGS, total: 120, offset: 0, limit: 50 } } });
    renderWith(<ConfigsPage />);
    expect(await screen.findByText(/of 120/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "3" })).toBeInTheDocument();
  });

  beforeEach(() => mockPortal({ "GET /api/configs": { body: { items: CONFIGS, total: 2, offset: 0, limit: 50 } } }));

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
