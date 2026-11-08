import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// `npm run dev` serves the app with hot reload and forwards the API to a running portal, by default the one
// started locally on 8080. Point it elsewhere with PORTAL_BACKEND, for example http://10.21.3.204.
const backend = process.env.PORTAL_BACKEND ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  build: {
    // Maven copies this into the portal jar, where the Java server serves it
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: true,
    // React and Mantine are most of the bundle (about 170 kB gzipped). Load pages lazily once there are more of
    // them; until then a single chunk is fine for an internal portal, and the default warning only adds noise.
    chunkSizeWarningLimit: 800,
  },
  server: {
    proxy: {
      "/api": { target: backend, changeOrigin: true },
    },
  },
});
