/**
 * 往构建产物里补一份 `project.private.config.json`。
 *
 * <p><b>为什么 `manifest.json` 里已经写了 `urlCheck: false` 还要这一步。</b>
 * 那个值进的是 `project.config.json`，而开发者工具**以自己那份私有配置为准** ——
 * 私有配置缺失时它回落到「开启域名校验」，于是模拟器里所有请求都
 * `request:fail url not in domain list`。
 *
 * <p>坑在于这个失败**长得像后端挂了**：页面弹的是「没能加载附近的自提点，
 * 多半是网络不通」。查了 API 地址、nginx 前缀、生产可达性一路下来，
 * 才落到这个开关上 —— 而 curl 从头到尾都是通的。
 *
 * <p>产物目录每次构建都重建，所以这一步挂在 build 之后，不能只手写一次。
 */
import { writeFileSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const OUT = resolve(dirname(fileURLToPath(import.meta.url)), "../dist/build/mp-weixin");
if (!existsSync(OUT)) {
  console.error(`[mp] 产物目录不存在：${OUT}（先跑 build:mp-weixin）`);
  process.exit(1);
}
writeFileSync(
  resolve(OUT, "project.private.config.json"),
  JSON.stringify(
    {
      projectname: "ai-shop-c",
      // 后端是 http + IP（备案未过，见 docs），小程序默认不放行；模拟器里必须关掉校验
      setting: { urlCheck: false, compileHotReLoad: false },
    },
    null,
    2,
  ) + "\n",
);
console.log("[mp] 已写入 project.private.config.json（urlCheck=false）");
