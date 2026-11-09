import { createBrowserRouter, Navigate, type RouteObject } from "react-router";
import { AppLayout } from "./app/AppLayout";
import { RequirePermission, RequireSignIn } from "./auth/guards";
import { PERMISSIONS } from "./auth/permissions";
import { LoginPage } from "./pages/auth/LoginPage";
import { SetupPage } from "./pages/auth/SetupPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { ProcessesPage } from "./pages/processes/ProcessesPage";
import { ConfigsPage } from "./pages/configs/ConfigsPage";
import { RolesPage } from "./pages/roles/RolesPage";
import { ServicesPage } from "./pages/services/ServicesPage";
import { UsersPage } from "./pages/users/UsersPage";

export const routes: RouteObject[] = [
  // Public: signing in, and creating the first admin while no users exist
  { path: "/login", element: <LoginPage /> },
  { path: "/setup", element: <SetupPage /> },
  {
    element: <RequireSignIn />,
    children: [
      {
        path: "/",
        element: <AppLayout />,
        children: [
          { index: true, element: <Navigate to="/processes" replace /> },
          {
            path: "processes",
            element: (
              <RequirePermission permission={PERMISSIONS.processesView}>
                <ProcessesPage />
              </RequirePermission>
            ),
          },
          {
            path: "services",
            element: (
              <RequirePermission permission={PERMISSIONS.servicesView}>
                <ServicesPage />
              </RequirePermission>
            ),
          },
          {
            path: "configs",
            element: (
              <RequirePermission permission={PERMISSIONS.configsView}>
                <ConfigsPage />
              </RequirePermission>
            ),
          },
          {
            path: "users",
            element: (
              <RequirePermission permission={PERMISSIONS.usersView}>
                <UsersPage />
              </RequirePermission>
            ),
          },
          {
            path: "roles",
            element: (
              <RequirePermission permission={PERMISSIONS.rolesView}>
                <RolesPage />
              </RequirePermission>
            ),
          },
          { path: "*", element: <NotFoundPage /> },
        ],
      },
    ],
  },
];

export const router = createBrowserRouter(routes);
