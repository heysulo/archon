import { screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { renderWith } from "../test/render";
import { RequirePermission, RequireSignIn } from "./guards";
import { PERMISSIONS } from "./permissions";

function Where() {
  const location = useLocation();
  return <span data-testid="where">{location.pathname + location.search}</span>;
}

function app(start: string) {
  return (
    <MemoryRouter initialEntries={[start]}>
      <Routes>
        <Route path="/login" element={<Where />} />
        <Route path="/setup" element={<Where />} />
        <Route element={<RequireSignIn />}>
          <Route path="/users" element={<span>users page</span>} />
        </Route>
      </Routes>
    </MemoryRouter>
  );
}

describe("RequireSignIn", () => {
  it("sends signed-out visitors to sign in, remembering where they were going", () => {
    renderWith(app("/users"), { status: "signedOut" });
    expect(screen.getByTestId("where")).toHaveTextContent("/login?next=%2Fusers");
  });

  it("sends everyone to setup while no users exist", () => {
    renderWith(app("/users"), { status: "setup" });
    expect(screen.getByTestId("where")).toHaveTextContent("/setup");
  });

  it("lets signed-in users through", () => {
    renderWith(app("/users"));
    expect(screen.getByText("users page")).toBeInTheDocument();
  });
});

describe("RequirePermission", () => {
  it("hides pages from users without the permission", () => {
    renderWith(
      <RequirePermission permission={PERMISSIONS.usersManage}>
        <span>secret</span>
      </RequirePermission>,
      { user: { id: 2, username: "victor", displayName: "Victor", roles: ["viewer"], permissions: ["processes:view"] } },
    );
    expect(screen.queryByText("secret")).not.toBeInTheDocument();
    expect(screen.getByText(/do not have permission/)).toBeInTheDocument();
  });
});
