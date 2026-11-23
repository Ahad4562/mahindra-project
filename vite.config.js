import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  publicDir: "android/app/src/main/assets/public",
  clearScreen: false,
  server: { host: "0.0.0.0" },
  build: { target: "es2020" },
});
