import { MantineProvider } from "@mantine/core";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { AuthProvider } from "../../auth/AuthProvider";
import { admin, mockPortal } from "../../test/render";
import { LoginPage } from "./LoginPage";

function renderLogin() {
  return render(
    <MantineProvider>
      <AuthProvider>
        <MemoryRouter initialEntries={["/login?next=%2Fusers"]}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/users" element={<span>users page</span>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </MantineProvider>,
  );
}

function fillIn(username: string, password: string) {
  fireEvent.change(screen.getByLabelText("Username"), { target: { value: username } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: password } });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
}

describe("LoginPage", () => {
  it("signs in and continues to where the user was going", async () => {
    const calls = mockPortal({
      "GET /api/auth/state": { body: { setupRequired: false } },
      "POST /api/auth/login": { body: { setupRequired: false, user: admin } },
    });
    renderLogin();
    await screen.findByLabelText("Username");
    fillIn("alice", "correct horse battery");

    expect(await screen.findByText("users page")).toBeInTheDocument();
    const login = calls.find((call) => call.path === "/api/auth/login");
    expect(login?.body).toEqual({ username: "alice", password: "correct horse battery" });
    expect(login?.headers["X-Archon-Request"]).toBe("1");
  });

  it("shows the portal's refusal and clears the password", async () => {
    mockPortal({
      "GET /api/auth/state": { body: { setupRequired: false } },
      "POST /api/auth/login": { status: 401, body: { error: "Wrong username or password" } },
    });
    renderLogin();
    await screen.findByLabelText("Username");
    fillIn("alice", "wrong password");

    expect(await screen.findByText("Wrong username or password")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText("Password")).toHaveValue(""));
  });
});
