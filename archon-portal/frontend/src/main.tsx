import "@mantine/core/styles.css";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router";
import { ProcessStreamProvider } from "./api/processStream";
import { router } from "./router";
import { theme } from "./theme";

// For request/response features to come; live process data arrives through ProcessStreamProvider instead
const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } } });

const root = document.getElementById("root");
if (!root) {
  throw new Error("Missing #root element");
}

createRoot(root).render(
  <StrictMode>
    <MantineProvider theme={theme} defaultColorScheme="auto">
      <QueryClientProvider client={queryClient}>
        <ProcessStreamProvider>
          <RouterProvider router={router} />
        </ProcessStreamProvider>
      </QueryClientProvider>
    </MantineProvider>
  </StrictMode>,
);
