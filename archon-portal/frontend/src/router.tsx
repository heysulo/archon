import { createBrowserRouter, Navigate, type RouteObject } from "react-router";
import { AppLayout } from "./app/AppLayout";
import { NotFoundPage } from "./pages/NotFoundPage";
import { ProcessesPage } from "./pages/processes/ProcessesPage";

export const routes: RouteObject[] = [
  {
    path: "/",
    element: <AppLayout />,
    children: [
      { index: true, element: <Navigate to="/processes" replace /> },
      { path: "processes", element: <ProcessesPage /> },
      { path: "*", element: <NotFoundPage /> },
    ],
  },
];

export const router = createBrowserRouter(routes);
