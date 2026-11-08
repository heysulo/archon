import { screen, within } from "@testing-library/react";
import { fireEvent } from "@testing-library/react";
import { mockPortal, renderWith } from "../../test/render";
import { UsersPage } from "./UsersPage";

const USERS = [
  { id: 1, username: "alice", displayName: "Alice Admin", enabled: true, roles: ["admin"],
    createdAt: "2026-11-08T12:00:00Z", lastLoginAt: "2026-11-08T13:00:00Z" },
  { id: 2, username: "bob", displayName: "Bob", enabled: false, roles: ["admin"], createdAt: "2026-11-08T12:05:00Z" },
];

describe("UsersPage", () => {
  beforeEach(() => {
    mockPortal({
      "GET /api/users": { body: USERS },
      "GET /api/roles": { body: { roles: [{ id: "admin", description: "Every permission", permissions: ["*"] }],
        permissions: [] } },
    });
  });

  it("lists users with their roles and status, marking the signed-in user", async () => {
    renderWith(<UsersPage />);
    const alice = (await screen.findByText("alice")).closest("tr")!;
    expect(within(alice).getByText("You")).toBeInTheDocument();
    expect(within(alice).getByText("Enabled")).toBeInTheDocument();
    const bob = screen.getByText("bob").closest("tr")!;
    expect(within(bob).getByText("Disabled")).toBeInTheDocument();
    expect(within(bob).getByText("Never")).toBeInTheDocument();
  });

  it("does not offer deleting yourself", async () => {
    renderWith(<UsersPage />);
    fireEvent.click(await screen.findByLabelText("Actions for alice"));
    const remove = await screen.findByRole("menuitem", { name: "Delete" });
    expect(remove).toBeDisabled();
  });
});
