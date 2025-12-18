import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react-swc";
import path from "path";
import { componentTagger } from "lovable-tagger";

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const rpcTarget = (env.VITE_CHAIN_RPC_URL || "http://127.0.0.1:26657").replace(/\/+$/, "");
  const restTarget = (env.VITE_CHAIN_REST_URL || "http://127.0.0.1:1317").replace(/\/+$/, "");

  return {
    server: {
      host: "::",
      port: 8080,
      proxy: {
        "/rpc": {
          target: rpcTarget,
          changeOrigin: true,
          ws: true,
          rewrite: (p) => p.replace(/^\/rpc/, ""),
        },
        "/rest": {
          target: restTarget,
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/rest/, ""),
        },
      },
    },
    plugins: [react(), mode === "development" && componentTagger()].filter(Boolean),
    resolve: {
      alias: {
        "@": path.resolve(__dirname, "./src"),
      },
    },
  };
});
