import { readFileSync } from "node:fs";
import { fileURLToPath, URL } from "node:url";
import { defineConfig, loadEnv } from "vite";
import uniModule from "@dcloudio/vite-plugin-uni";
import UnoCSS from "unocss/vite";

// 与 c-app/vite.config.mts 同一套写法；那边每一条都有注释说为什么，这里只写不同的地方。
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const uni = ((uniModule as any).default ?? uniModule) as () => any;

const MANIFEST = fileURLToPath(new URL("./src/manifest.json", import.meta.url));
const VERSION_NAME =
  /"versionName"\s*:\s*"([^"]+)"/.exec(readFileSync(MANIFEST, "utf8"))?.[1] ?? "0.0.0";
const D = new Date(Date.now() + 8 * 3600 * 1000);
const pad = (n: number) => String(n).padStart(2, "0");
const BUILD_STAMP =
  `${pad(D.getUTCMonth() + 1)}${pad(D.getUTCDate())}-${pad(D.getUTCHours())}${pad(D.getUTCMinutes())}`;

export default defineConfig(({ mode }) => {
  // 代理目标可在 .env.local（不入库）里改：本机主系统不在 8081、或临时起一套验证实例时用
  const env = loadEnv(mode, process.cwd(), "");
  return {
    define: {
      __BUILD_VERSION__: JSON.stringify(`${VERSION_NAME} · ${BUILD_STAMP}`),
    },
    base: process.env.H5_BASE || "/",
    resolve: {
      preserveSymlinks: true,
      alias: {
        "@shared": fileURLToPath(new URL("../packages/shared/src", import.meta.url)),
      },
    },
    optimizeDeps: { exclude: ["@ai-shop/ui"] },
    server: {
      port: Number(process.env.PORT) || 5177,
      strictPort: false,
      watch: { ignored: ["!**/packages/**"] },
      fs: { allow: [fileURLToPath(new URL("..", import.meta.url))] },
      /*
       * **两个后端**：元器件自己的接口在 elec-svc（8085），登录借主系统（/mp/user/*）。
       * 生产上两者同域，由 nginx 分流（`/elec/` → 8085）；开发期由这里分流，效果一样。
       * 主系统端口跟 elec-svc 认令牌的那个一致（ELEC_MAIN_URL，默认 8081）——
       * 令牌在哪个实例签的，就得回哪个实例认。
       */
      proxy: {
        "/elec": { target: env.ELEC_TARGET || "http://localhost:8085", changeOrigin: true },
        "/mp": { target: env.MAIN_TARGET || "http://localhost:8081", changeOrigin: true },
      },
    },
    plugins: [uni(), UnoCSS()],
  };
});
