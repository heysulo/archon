import { fireEvent, screen } from "@testing-library/react";
import { mockPortal, renderWith } from "../../test/render";
import { RolesPage } from "./RolesPage";

const ROLES = {
  roles: [
    { id: "admin", description: "Every permission", permissions: ["*"], builtIn: true, users: ["alice"] },
    { id: "operator", description: "Runs the system", permissions: ["processes:view", "configs:view"],
      builtIn: false, users: ["olga", "oscar"] },
    { id: "spare", description: "", permissions: ["services:view"], builtIn: false, users: [] },
  ],
  permissions: [
    { key: "processes:view", description: "See running processes" },
    { key: "configs:view", description: "See configurations" },
    { key: "services:view", description: "See service definitions" },
    { key: "configs:manage", description: "Create, change and delete configurations" },
    { key: "users:manage", description: "Create, change and delete users and assign them roles" },
  ],
};

const ROLE_MANAGER = { id: 3, username: "rita", displayName: "Rita", roles: ["role-admin"],
  permissions: ["roles:manage", "processes:view", "configs:view"] };

describe("RolesPage", () => {
  beforeEach(() => mockPortal({ "GET /api/roles": { body: ROLES } }));

  it("lists roles, their permissions and holders", async () => {
    renderWith(<RolesPage />);
    expect(await screen.findByText("operator")).toBeInTheDocument();
    expect(screen.getByText("Every permission", { selector: ".mantine-Badge-label" })).toBeInTheDocument();
    expect(screen.getByText("2 users")).toBeInTheDocument();
    expect(screen.getByText("Processes: View")).toBeInTheDocument();
    expect(screen.getByText("None")).toBeInTheDocument();
  });

  it("locks the built-in admin role", async () => {
    renderWith(<RolesPage />);
    expect(await screen.findByLabelText("Built in")).toBeInTheDocument();
    expect(screen.queryByLabelText("Actions for admin")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Actions for operator")).toBeInTheDocument();
  });

  it("shows roles read-only to those who can only view them", async () => {
    renderWith(<RolesPage />, { user: { id: 9, username: "vera", displayName: "Vera", roles: ["auditor"],
      permissions: ["roles:view"] } });
    expect(await screen.findByText("operator")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add role" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Actions for operator")).not.toBeInTheDocument();
  });

  it("offers only permissions you hold", async () => {
    renderWith(<RolesPage />, { user: ROLE_MANAGER });
    fireEvent.click(await screen.findByRole("button", { name: "Add role" }));
    expect(await screen.findByRole("checkbox", { name: "Processes: View" })).toBeEnabled();
    expect(screen.getByRole("checkbox", { name: "Users: Modify" })).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: "Services: View" })).toBeDisabled();
  });

  it("ticks view along with modify", async () => {
    renderWith(<RolesPage />);
    fireEvent.click(await screen.findByRole("button", { name: "Add role" }));
    fireEvent.click(await screen.findByRole("checkbox", { name: "Configurations: Modify" }));
    expect(screen.getByRole("checkbox", { name: "Configurations: View" })).toBeChecked();
  });
});
