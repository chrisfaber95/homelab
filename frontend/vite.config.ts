import { defineConfig } from "vitest/config";

// The inbox serves the build under /app/ (STATIC_PREFIX in bin/homelab_inbox/config.py).
export default defineConfig({
  base: "/app/",
  build: { outDir: "dist", emptyOutDir: true },
  test: { include: ["src/**/*.test.ts"] },
});
