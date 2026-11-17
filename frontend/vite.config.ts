import { defineConfig } from "vitest/config";

export default defineConfig({
  base: "/app/", // the inbox serves frontend/dist under /app/
  build: { outDir: "dist", emptyOutDir: true },
  test: { include: ["src/**/*.test.ts"] },
});
